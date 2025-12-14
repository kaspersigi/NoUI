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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// adb shell pm grant com.kaspersigi.noui android.permission.CAMERA
// adb shell am start -n com.kaspersigi.noui/.NeoBurstZSLHeicActivity
// adb shell am force-stop com.kaspersigi.noui
// 触发 JIT profile 收集（Android 9+）
// adb shell cmd package compile -m speed com.kaspersigi.noui

/**
 * Fixed version:
 * 1. Use preview template for ZSL
 * 2. Abort after all ZSL captures complete
 */
public class NeoBurstZSLHeicActivity extends Activity {
    private static final String TAG = "NoUI";
    private static final int BURST_COUNT = 5;
    private static final int INFLIGHT_DEPTH = 6;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private String mCameraId;

    private HandlerThread mBgThread;
    private Handler mBgHandler;

    private SurfaceTexture mDummyTexture;
    private Surface mPreviewSurface;

    private boolean mStoppedPreview = false;
    private long mConvergedFrame = -1;
    private int mZslTriggeredCount = 0; // 新增计数器

    private long mBootTimeUtcMs = -1;

    private CameraManager mCameraManager;
    private CameraCharacteristics mCameraCharacteristics;
    private CaptureRequest.Builder mPreviewRequestTemplate;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Fixed ZSL camera started");

        startBackgroundThread();

        mDummyTexture = new SurfaceTexture(0);
        mDummyTexture.setDefaultBufferSize(640, 480);
        mPreviewSurface = new Surface(mDummyTexture);

        mBootTimeUtcMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        Log.d(TAG, "Estimated boot UTC time: " + formatUtcTime(mBootTimeUtcMs));

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
        mBgHandler.post(() -> openBackCamera());
    }

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
            mCameraManager.openCamera(mCameraId, mStateCallback, mBgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            finish();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            try {
                mPreviewRequestTemplate = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to create preview template", e);
                mMainHandler.post(NeoBurstZSLHeicActivity.this::finish);
                return;
            }
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cleanup();
            mMainHandler.post(NeoBurstZSLHeicActivity.this::finish);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cleanup();
            mMainHandler.post(NeoBurstZSLHeicActivity.this::finish);
        }
    };

    private void createCaptureSession() {
        try {
            Size jpegSize = chooseJpegSize();
            mImageReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(),
                    ImageFormat.HEIC, BURST_COUNT + 2
            );

            mImageReader.setOnImageAvailableListener(reader -> {
                Image image;
                while ((image = reader.acquireLatestImage()) != null) {
                    saveImage(image);
                }
            }, mBgHandler);

            CaptureRequest.Builder previewBuilder = mPreviewRequestTemplate;
            previewBuilder.addTarget(mPreviewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface());

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    try {
                        session.setRepeatingRequest(previewBuilder.build(), mPreviewCallback, mBgHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start preview", e);
                        mMainHandler.post(NeoBurstZSLHeicActivity.this::finish);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Session config failed");
                    mMainHandler.post(NeoBurstZSLHeicActivity.this::finish);
                }
            }, mBgHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create session", e);
            mMainHandler.post(NeoBurstZSLHeicActivity.this::finish);
        }
    }

    private final CameraCaptureSession.CaptureCallback mPreviewCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    logCaptureResult("Preview Final Result", result);

//                    if (!mStoppedPreview && is3AConverged(result)) {
                    if (!mStoppedPreview && isAeConverged(result)) {
                        mConvergedFrame = result.getFrameNumber();
                        mStoppedPreview = true;

                        try {
                            mCaptureSession.stopRepeating();
                        } catch (Exception e) {
                            Log.e(TAG, "stopRepeating failed", e);
                        }

                        Log.d(TAG, "AE converged at frame #" + mConvergedFrame +
                                ". ZSL on frames #" + (mConvergedFrame + 1) +
                                " to #" + (mConvergedFrame + BURST_COUNT));
                    }

                    // 触发 ZSL 并计数
                    if (mStoppedPreview && mZslTriggeredCount < BURST_COUNT) {
                        long frameNum = result.getFrameNumber();
                        long zslStart = mConvergedFrame + 1;
                        long zslEnd = mConvergedFrame + BURST_COUNT;
                        if (frameNum >= zslStart && frameNum <= zslEnd) {
                            triggerZslCapture();
                            mZslTriggeredCount++;
                            Log.d(TAG, "Triggered ZSL #" + mZslTriggeredCount + " for preview frame #" + frameNum);
                        }
                    }
                }
            };

    private final CameraCaptureSession.CaptureCallback mZslCallback =
            new CameraCaptureSession.CaptureCallback() {
                private int mCapturedCount = 0; // ZSL 完成计数

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    logCaptureResult("ZSL Capture Result", result);
                    Log.d(TAG, "ZSL captured frame #" + result.getFrameNumber());

                    mCapturedCount++;
                    // 所有 ZSL 完成后 abort
                    if (mCapturedCount >= BURST_COUNT) {
                        Log.d(TAG, "All ZSL captures done. Aborting session.");
                        mBgHandler.post(NeoBurstZSLHeicActivity.this::abortCaptureSession);
                    }
                }
            };

    private boolean isAeConverged(CaptureResult result) {
        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        boolean aeOK = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED;

        return aeOK;
    }

    private boolean is2AConverged(CaptureResult result) {
        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);

        boolean aeOK = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED;
        boolean awbOK = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED;

        return aeOK && awbOK;
    }

    private boolean is3AConverged(CaptureResult result) {
        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        Integer af = result.get(CaptureResult.CONTROL_AF_STATE);

        boolean aeOK = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED;
        boolean awbOK = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED;
        boolean afOK = af == null ||
                af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED;

        return aeOK && awbOK && afOK;
    }

    private void triggerZslCapture() {
        try {
            // 使用 preview template + ZSL flag
            CaptureRequest.Builder capture = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            capture.addTarget(mImageReader.getSurface());
            capture.set(CaptureRequest.JPEG_QUALITY, (byte) 90);
            capture.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
            capture.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);

            mCaptureSession.capture(capture.build(), mZslCallback, mBgHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to trigger ZSL capture", e);
        }
    }

    // ===== Reflection-based abort =====
    private void abortCaptureSession() {
        if (mCaptureSession == null) return;

        String[] candidates = {"abort", "abortCaptures"};
        for (String method : candidates) {
            try {
                java.lang.reflect.Method m = mCaptureSession.getClass().getMethod(method);
                m.invoke(mCaptureSession);
                Log.d(TAG, "Successfully invoked " + method + "()");
                return;
            } catch (Exception e) {
                // Try next
            }
        }

        Log.w(TAG, "No abort method found, falling back to close()");
        mCaptureSession.close();
    }

    // ===== Utility Methods (Unchanged) =====

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

    private String formatUtcTime(long utcMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(utcMillis));
    }

    private Long sensorTimestampToUtcMs(Long sensorTimestampNs) {
        if (mBootTimeUtcMs <= 0 || sensorTimestampNs == null) {
            return null;
        }
        return mBootTimeUtcMs + (sensorTimestampNs / 1_000_000L);
    }

    private Size chooseJpegSize() throws CameraAccessException {
        StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map.getOutputSizes(ImageFormat.HEIC);
        if (sizes == null || sizes.length == 0) return new Size(1920, 1440);
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    private void saveImage(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            File dir = new File(getExternalMediaDirs()[0], "burst");
            dir.mkdirs();
            File file = new File(dir, "burst_" + System.currentTimeMillis() + ".heic");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                Log.d(TAG, "Saved: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
        } finally {
            image.close();
        }
    }

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

    private void startBackgroundThread() {
        mBgThread = new HandlerThread("CameraBg");
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());
    }

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