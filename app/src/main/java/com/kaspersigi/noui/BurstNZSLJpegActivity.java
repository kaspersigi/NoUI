package com.kaspersigi.noui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// adb shell pm grant com.kaspersigi.noui android.permission.CAMERA
// adb shell am start -n com.kaspersigi.noui/.BurstNZSLJpegActivity
// adb shell am force-stop com.kaspersigi.noui
// 触发 JIT profile 收集（Android 9+）
// adb shell cmd package compile -m speed com.kaspersigi.noui

/**
 * 无界面（No UI）相机应用主 Activity。
 * 目标：在穿戴设备上启动后自动打开后置摄像头，等待 AE（自动曝光）收敛后触发连拍（burst），
 * 保存指定数量的 JPEG 图像到外部存储，然后自动退出。
 *
 * 特点：
 * - 无预览界面（使用 dummy SurfaceTexture）
 * - 自动权限检查（CAMERA）
 * - 使用 Camera2 API
 * - 支持 3A 状态监控（当前仅启用 AE 触发）
 * - 连拍完成后自动清理资源并退出
 */
public class BurstNZSLJpegActivity extends Activity {
    private static final String TAG = "NoUI";
    private static final int BURST_COUNT = 5; // 连拍张数

    // Camera2 核心对象
    private CameraDevice mCameraDevice;          // 相机设备实例
    private CameraCaptureSession mCaptureSession; // 捕获会话
    private ImageReader mImageReader;            // 用于接收 JPEG 图像
    private String mCameraId;                    // 选中的后置摄像头 ID

    // 后台线程用于避免阻塞主线程（Camera2 操作必须在非主线程）
    private HandlerThread mBgThread;
    private Handler mBgHandler;

    // 无界面预览所需的虚拟 Surface
    private SurfaceTexture mDummyTexture; // 虚拟纹理（无实际显示）
    private Surface mPreviewSurface;      // 绑定到虚拟纹理的 Surface，用于预览流

    // 3A 收敛状态标志
    private boolean mAeConverged = false;    // AE（自动曝光）是否已收敛
    private boolean m3AConverged = false;    // 3A（AE+AWB+AF）是否全收敛（当前未启用）
    private boolean mBurstTriggered = false; // 是否已触发连拍（防止重复触发）

    // 时间同步：用于将传感器时间戳转换为 UTC 时间
    private long mBootTimeUtcMs = -1; // 系统启动时刻对应的 UTC 毫秒时间

    // 相机管理器和特性
    private CameraManager mCameraManager;
    private CameraCharacteristics mCameraCharacteristics;

    // 预创建的 CaptureRequest 模板，提升性能（避免重复创建）
    private CaptureRequest.Builder mPreviewRequestTemplate; // 预览请求模板
    private CaptureRequest.Builder mStillRequestTemplate;   // 静态拍照请求模板

    // 主线程 Handler，用于安全地 finish Activity
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "NoUI camera started");

        // 启动后台线程处理相机操作
        startBackgroundThread();

        // 创建虚拟 Surface 用于预览（无实际显示）
        mDummyTexture = new SurfaceTexture(0);
        mDummyTexture.setDefaultBufferSize(640, 480); // 设置低分辨率以节省资源
        mPreviewSurface = new Surface(mDummyTexture);

        // 估算系统启动时的 UTC 时间，用于后续时间戳转换
        mBootTimeUtcMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        Log.d(TAG, "Estimated boot UTC time: " + formatUtcTime(mBootTimeUtcMs));

        // 检查 CAMERA 权限（穿戴设备通常需预授权）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing CAMERA permission...");
            finish();
            return;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 现在 Activity 在前台，可以安全打开相机
        mBgHandler.post(() -> openBackCamera());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 可选：在这里 cleanup 或 stop preview
    }

    /**
     * 查找并打开后置摄像头
     */
    private void openBackCamera() {
        try {
            mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : mCameraManager.getCameraIdList()) {
                mCameraCharacteristics = mCameraManager.getCameraCharacteristics(id);
                Integer facing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = id;
                    break;
                }
            }
            if (mCameraId == null) {
                Log.e(TAG, "No back camera found");
                finish();
                return;
            }
            // 异步打开摄像头，回调在 mBgHandler 线程执行
            mCameraManager.openCamera(mCameraId, mStateCallback, mBgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            finish();
        }
    }

    /**
     * CameraDevice 状态回调
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            try {
                // 预创建两种请求模板，避免后续重复创建（性能优化）
                mPreviewRequestTemplate = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mStillRequestTemplate = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to create templates", e);
                mMainHandler.post(BurstNZSLJpegActivity.this::finish);
                return;
            }
            createCaptureSession(); // 创建捕获会话
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cleanup();
            mMainHandler.post(BurstNZSLJpegActivity.this::finish);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cleanup();
            mMainHandler.post(BurstNZSLJpegActivity.this::finish);
        }
    };

    /**
     * 创建 CameraCaptureSession
     * 包含两个输出 Surface：虚拟预览 Surface + ImageReader（用于接收图像）
     */
    private void createCaptureSession() {
        try {
            Size jpegSize = chooseJpegSize();
            // 固定使用高分辨率 JPEG（可根据设备性能调整）
            // Size jpegSize = new Size(4032, 3024);

            // 创建 ImageReader，队列大小 = BURST_COUNT + 缓冲（防止溢出）
            mImageReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(),
                    ImageFormat.JPEG, BURST_COUNT + 5
            );

            // 设置图像可用监听器：每当有新图像，就保存
            mImageReader.setOnImageAvailableListener(reader -> {
                Image image;
                // 使用 acquireLatestImage()：只保留最新一帧（适合预览，但连拍时可能丢帧）
                while ((image = reader.acquireLatestImage()) != null) {
                    saveImage(image);
                }
            }, mBgHandler);

            // 构建预览请求：添加虚拟 Surface，并设置连续自动对焦
            CaptureRequest.Builder previewBuilder = mPreviewRequestTemplate;
            previewBuilder.addTarget(mPreviewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // 会话需要的所有 Surface
            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface());

            // 创建捕获会话
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    try {
                        // 启动重复预览请求，并注册回调以监控 3A 状态
                        session.setRepeatingRequest(previewBuilder.build(), mPreviewCaptureCallback, mBgHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start preview", e);
                        mMainHandler.post(BurstNZSLJpegActivity.this::finish);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Session config failed");
                    mMainHandler.post(BurstNZSLJpegActivity.this::finish);
                }
            }, mBgHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create session", e);
            mMainHandler.post(BurstNZSLJpegActivity.this::finish);
        }
    }

    /**
     * 预览捕获回调：用于监控 3A 状态并决定是否触发连拍
     */
    private final CameraCaptureSession.CaptureCallback mPreviewCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    // 打印部分结果（通常包含 3A 状态）
                    logCaptureResult("Preview Partial Result", partialResult);
                    // 当前仅检查 AE 状态（如需 3A 全收敛，请改为 check3AAndTriggerBurst）
                    checkAeAndTriggerBurst(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult finalResult) {
                    // 打印完整结果（通常与 partialResult 内容一致，但更完整）
                    logCaptureResult("Preview Final Result", finalResult);
                    checkAeAndTriggerBurst(finalResult);
                }
            };

    /**
     * 打印 CaptureResult 中的关键信息：帧号、3A 状态、时间戳等
     */
    private void logCaptureResult(String prefix, CaptureResult result) {
        Long frameNumber = result.getFrameNumber();
        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Long utcMs = sensorTimestampToUtcMs(timestamp);

        StringBuilder sb = new StringBuilder(prefix).append(" #");
        sb.append(frameNumber != null ? frameNumber : -1).append(": ");
        sb.append("AE=").append(aeStateToString(aeState)).append(", ");
        sb.append("AWB=").append(awbStateToString(awbState)).append(", ");
        sb.append("AF=").append(afStateToString(afState)).append(", ");
        sb.append("TIME=").append(timestamp != null ? timestamp : "null");
        if (utcMs != null) {
            sb.append(", UTC=").append(formatUtcTime(utcMs));
        }

        Log.d(TAG, sb.toString());
    }

    /**
     * 仅检查 AE（自动曝光）是否收敛，若收敛则触发连拍
     * 注意：当前逻辑仅依赖 AE，未使用 AWB/AF
     */
    private void checkAeAndTriggerBurst(CaptureResult result) {
        if (mBurstTriggered) return; // 防止重复触发

        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        // AE 状态为 CONVERGED 或 LOCKED 视为 OK；null 表示不支持 AE（罕见）
        boolean aeOK = ae == null ||
                ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED;

        if (aeOK) {
            if (!mAeConverged) {
                mAeConverged = true;
                Log.d(TAG, "AE converged at frame #" + result.getFrameNumber() + ". Triggering burst...");
                triggerBurst();
            }
        } else {
            mAeConverged = false; // AE 未收敛，重置状态
        }
    }

    /**
     * 检查完整的 3A（AE+AWB+AF）是否全部收敛
     * 当前未被调用，如需启用，请在 mPreviewCaptureCallback 中替换调用
     */
    private void check3AAndTriggerBurst(CaptureResult result) {
        if (mBurstTriggered) return;

        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        Integer af = result.get(CaptureResult.CONTROL_AF_STATE);

        boolean aeOK = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED;
        boolean awbOK = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED;
        boolean afOK = af == null ||
                af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED;

        if (aeOK && awbOK && afOK) {
            if (!m3AConverged) {
                m3AConverged = true;
                Log.d(TAG, "3A converged at frame #" + result.getFrameNumber() + ". Triggering burst...");
                triggerBurst();
            }
        } else {
            m3AConverged = false;
        }
    }

    /**
     * 触发连拍：
     * 1. 停止预览循环
     * 2. 构建 BURST_COUNT 个静态拍照请求
     * 3. 发起 burst 捕获
     */
    private void triggerBurst() {
        mBurstTriggered = true;
        try {
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating(); // 停止预览
            }

            // 使用预创建的静态拍照模板
            CaptureRequest.Builder burstBuilder = mStillRequestTemplate;
            burstBuilder.addTarget(mImageReader.getSurface());
            burstBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false); // 显式关闭 ZSL
            burstBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            burstBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 90);

            // 构建连拍请求列表
            List<CaptureRequest> requests = new ArrayList<>();
            for (int i = 0; i < BURST_COUNT; i++) {
                requests.add(burstBuilder.build());
            }

            // 发起 burst 捕获
            mCaptureSession.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                private int captured = 0;

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    captured++;
                    logCaptureResult("Burst Capture", result);
                    Log.d(TAG, "Burst captured #" + captured + " (frame " + result.getFrameNumber() + ")");
                    if (captured >= BURST_COUNT) {
                        Log.d(TAG, "Burst completed. Exiting...");
                        // 延迟 300ms 后清理资源，确保最后一帧图像已保存（经验性做法）
                        mBgHandler.postDelayed(() -> {
                            cleanup();
                            mMainHandler.post(BurstNZSLJpegActivity.this::finish);
                        }, 300);
                    }
                }
            }, mBgHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Burst failed", e);
            mMainHandler.post(BurstNZSLJpegActivity.this::finish);
        }
    }

    // 以下为 3A 状态的字符串转换工具方法（便于日志阅读）

    private String aeStateToString(Integer state) {
        if (state == null) return "UNKNOWN";
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private String awbStateToString(Integer state) {
        if (state == null) return "UNKNOWN";
        switch (state) {
            case CaptureResult.CONTROL_AWB_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AWB_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AWB_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AWB_STATE_LOCKED: return "LOCKED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private String afStateToString(Integer state) {
        if (state == null) return "UNKNOWN";
        switch (state) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: return "PASSIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "PASSIVE_FOCUSED";
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: return "ACTIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: return "FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "NOT_FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "PASSIVE_UNFOCUSED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /**
     * 将毫秒时间戳格式化为 UTC 字符串（用于日志）
     */
    private String formatUtcTime(long utcMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(utcMillis));
    }

    /**
     * 将传感器纳秒时间戳转换为 UTC 毫秒时间
     */
    private Long sensorTimestampToUtcMs(Long sensorTimestampNs) {
        if (mBootTimeUtcMs <= 0 || sensorTimestampNs == null) {
            return null;
        }
        return mBootTimeUtcMs + (sensorTimestampNs / 1_000_000L);
    }

    /**
     * （未使用）自动选择最大 JPEG 尺寸
     */
    private Size chooseJpegSize() throws CameraAccessException {
        StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(1920, 1440);
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 保存 ImageReader 中的 JPEG 图像到外部存储
     */
    private void saveImage(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            File dir = new File(getExternalMediaDirs()[0], "burst");
            dir.mkdirs();
            File file = new File(dir, "burst_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                Log.d(TAG, "Saved: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
        } finally {
            image.close(); // 必须关闭，否则 ImageReader 会阻塞
        }
    }

    /**
     * 清理所有相机相关资源
     */
    private void cleanup() {
        try {
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
            if (mDummyTexture != null) {
                mDummyTexture.release();
                mDummyTexture = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
        stopBackgroundThread();
    }

    /**
     * 启动后台 HandlerThread
     */
    private void startBackgroundThread() {
        mBgThread = new HandlerThread("CameraBg");
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());
    }

    /**
     * 停止后台线程
     */
    private void stopBackgroundThread() {
        if (mBgThread != null) {
            mBgThread.quitSafely();
            try { mBgThread.join(); } catch (InterruptedException ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "Activity destroyed");
        cleanup();
        super.onDestroy();
    }
}