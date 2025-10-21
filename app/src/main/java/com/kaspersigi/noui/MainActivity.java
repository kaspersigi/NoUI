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
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// adb shell pm grant com.kaspersigi.noui android.permission.CAMERA
// adb shell am start -n com.kaspersigi.noui/.MainActivity
// adb shell am force-stop com.kaspersigi.noui

public class MainActivity extends Activity {
    private static final String TAG = "NoUI";
    private static final int BURST_COUNT = 5;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private String mCameraId;
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private SurfaceTexture mDummyTexture;
    private Surface mPreviewSurface;
    private boolean m3AConverged = false;
    private boolean mBurstTriggered = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "NoUI camera started");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing CAMERA permission. Grant via: adb shell pm grant com.kaspersigi.noui android.permission.CAMERA");
            finish();
            return;
        }

        startBackgroundThread();
        openBackCamera();
    }

    private void openBackCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
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
            manager.openCamera(mCameraId, mStateCallback, mBgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            finish();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            createCaptureSession();
        }
        @Override public void onDisconnected(CameraDevice camera) { cleanup(); mMainHandler.post(MainActivity.this::finish); }
        @Override public void onError(CameraDevice camera, int error) { cleanup(); mMainHandler.post(MainActivity.this::finish); }
    };

    private void createCaptureSession() {
        mDummyTexture = new SurfaceTexture(0);
        mDummyTexture.setDefaultBufferSize(640, 480);
        mPreviewSurface = new Surface(mDummyTexture);

        try {
            Size jpegSize = chooseJpegSize();
            mImageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, BURST_COUNT + 5);
            mImageReader.setOnImageAvailableListener(reader -> {
                Image image;
                while ((image = reader.acquireLatestImage()) != null) {
                    saveImage(image);
                }
            }, mBgHandler);

            CaptureRequest.Builder previewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(mPreviewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface());
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    try {
                        session.setRepeatingRequest(previewBuilder.build(), mPreviewCaptureCallback, mBgHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start preview", e);
                        mMainHandler.post(MainActivity.this::finish);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Session config failed");
                    mMainHandler.post(MainActivity.this::finish);
                }
            }, mBgHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create session", e);
            mMainHandler.post(MainActivity.this::finish);
        }
    }

    private final CameraCaptureSession.CaptureCallback mPreviewCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    logCaptureResult("Preview Partial Result", partialResult);
                    check3AAndTriggerBurst(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult finalResult) {
                    logCaptureResult("Preview Final Result", finalResult);
                    // 注意：不再在这里 check3A，避免重复触发（partial 已足够）
                }
            };

    private void logCaptureResult(String prefix, CaptureResult result) {
        Long frameNumber = result.getFrameNumber();
        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);

        StringBuilder sb = new StringBuilder(prefix).append(" #");
        sb.append(frameNumber != null ? frameNumber : -1).append(": ");
        sb.append("AE=").append(aeStateToString(aeState)).append(", ");
        sb.append("AWB=").append(awbStateToString(awbState)).append(", ");
        sb.append("AF=").append(afStateToString(afState)).append(", ");
        sb.append("TIME=").append(timestamp);

        Log.d(TAG, sb.toString());
    }

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

    private void triggerBurst() {
        mBurstTriggered = true;
        try {
            // ✅ 关键：先停止预览流
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating(); // 停止 preview
            }

            CaptureRequest.Builder burstBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            burstBuilder.addTarget(mImageReader.getSurface());
            burstBuilder.addTarget(mPreviewSurface);
            burstBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
            burstBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            burstBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 90);

            List<CaptureRequest> requests = new ArrayList<>();
            for (int i = 0; i < BURST_COUNT; i++) {
                requests.add(burstBuilder.build());
            }

            mCaptureSession.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                private int captured = 0;
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    captured++;
                    Log.d(TAG, "Burst captured #" + captured + " (frame " + result.getFrameNumber() + ")");
                    if (captured >= BURST_COUNT) {
                        Log.d(TAG, "Burst completed. Exiting...");
                        mBgHandler.postDelayed(() -> {
                            cleanup();
                            mMainHandler.post(MainActivity.this::finish);
                        }, 300);
                    }
                }
            }, mBgHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Burst failed", e);
            mMainHandler.post(MainActivity.this::finish);
        }
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

    private Size chooseJpegSize() throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics cc = manager.getCameraCharacteristics(mCameraId);
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
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