package org.webrtc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

import java.util.List;

public final class UvcCameraSession implements CameraSession {
    private static final String TAG = "UvcCameraSession";
    private final Handler cameraThreadHandler;
    private final Events events;
    private final Context applicationContext;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final String cameraId;
    private final CaptureFormat captureFormat;
    private SessionState sessionState;
    private boolean firstFrameReported;

    private static UvcCameraSession lastCameraSession;
    private final Object lock = new Object();
    private final USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private Surface previewSurface;
    private boolean isStopped = false;

    public static void create(CreateSessionCallback callback, Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate) {
        long constructionTimeNs = System.nanoTime();
        Logging.d(TAG, "Open camera " + cameraId);
        events.onCameraOpening();

        CaptureFormat captureFormat = new CaptureFormat(width, height, framerate, framerate);
        UvcCameraSession current = new UvcCameraSession(events, applicationContext, surfaceTextureHelper, cameraId, captureFormat, constructionTimeNs);
        UvcCameraSession cache = lastCameraSession;
        lastCameraSession = current;
        if (cache != null) {
            cache.cameraThreadHandler.post(() -> {
                cache.stop(false);
                current.cameraThreadHandler.post(() -> {
                    current.startCapturing();
                    callback.onDone(current);
                });
            });
        } else {
            current.startCapturing();
            callback.onDone(current);
        }
    }

    private void releaseCamera() {
        if (uvcCamera != null) {
            synchronized (lock) {
                if (uvcCamera != null) {
                    try {
                        uvcCamera.setStatusCallback(null);
                        uvcCamera.setButtonCallback(null);
                        uvcCamera.close();
                        uvcCamera.destroy();
                        uvcCamera = null;
                    } catch (final Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private UvcCameraSession(Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraId, CaptureFormat captureFormat, long constructionTimeNs) {
        Logging.d(TAG, "Create new usb camera session on camera " + cameraId);
        this.cameraThreadHandler = new Handler();
        this.events = events;
        this.applicationContext = applicationContext;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.captureFormat = captureFormat;
        USBMonitor.OnDeviceConnectListener deviceConnectListener = new USBMonitor.OnDeviceConnectListener() {

            @Override
            public void onAttach(UsbDevice usbDevice) {
                Log.i(TAG, "Usb device( " + usbDevice.getDeviceId() + "), onAttach");
                if (usbMonitor.hasPermission(usbDevice)) {
                    usbMonitor.openDevice(usbDevice);
                } else {
                    usbMonitor.requestPermission(usbDevice);
                }
            }

            @Override
            public void onConnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock, boolean b) {
                Log.i(TAG, "Usb device( " + usbDevice.getDeviceId() + "), onConnect");
                releaseCamera();
                final UVCCamera camera = new UVCCamera();
                camera.open(usbControlBlock);
                camera.setStatusCallback((statusClass, event, selector, statusAttribute, data) -> {
                    Log.i(TAG, "Uvc camera, onStatus(statusClass = " + statusClass +
                            ", event = " + event +
                            ", selector = " + selector +
                            ", statusAttribute = " + statusAttribute +
                            ", data = " + data + ")");
                });
                camera.setButtonCallback((button, state) -> {
                    Log.i(TAG, "Uvc camera, onButton(button = " + button + ", state = " + state + ")");
                });
                try {
                    camera.setPreviewSize(captureFormat.width, captureFormat.height, captureFormat.framerate.max);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                    try {
                        camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                    } catch (final IllegalArgumentException err) {
                        err.printStackTrace();
                        camera.destroy();
                        return;
                    }
                }

                camera.setPreviewDisplay(previewSurface);
                camera.startPreview();
                synchronized (lock) {
                    uvcCamera = camera;
                }
            }

            @Override
            public void onDisconnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock) {
                Log.i(TAG, "Usb device( " + usbDevice.getDeviceId() + "), onDisconnect");
                releaseCamera();
            }

            @Override
            public void onDettach(UsbDevice usbDevice) {
                Log.i(TAG, "Usb device( " + usbDevice.getDeviceId() + "), onDettach");
            }

            @Override
            public void onCancel(UsbDevice usbDevice) {
                Log.i(TAG, "Usb device( " + usbDevice.getDeviceId() + "), onCancel");
            }
        };
        this.usbMonitor = new USBMonitor(applicationContext, deviceConnectListener);
        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
    }

    @Override
    public void stop() {
        stop(true);
    }

    public void stop(boolean checkable) {
        Logging.d(TAG, "Stop usb camera session on camera " + this.cameraId);
        if (checkable) {
            checkIsOnCameraThread();
        }
        if (isStopped) {
            return;
        }
        if (sessionState != SessionState.STOPPED) {
            stopInternal();
        }
        usbMonitor.unregister();
        usbMonitor.destroy();
        isStopped = true;
    }

    private void startCapturing() {
        Logging.d(TAG, "Start capturing");
        checkIsOnCameraThread();
        sessionState = SessionState.RUNNING;
        SurfaceTexture surfaceTexture = surfaceTextureHelper.getSurfaceTexture();
        if (surfaceTexture != null) {
            previewSurface = new Surface(surfaceTexture);
        }
        listenForTextureFrames();
        List<UsbDevice> devices = usbMonitor.getDeviceList();
        if (devices.size() > 0) {
            UsbDevice usbDevice = devices.get(0);
            if (!usbMonitor.hasPermission(usbDevice)) {
                usbMonitor.requestPermission(usbDevice);
            }
        }
    }

    private void stopInternal() {
        Logging.d(TAG, "Stop internal");
        checkIsOnCameraThread();
        if (sessionState == SessionState.STOPPED) {
            Logging.d(TAG, "Camera is already stopped");
        } else {
            sessionState = SessionState.STOPPED;
            surfaceTextureHelper.stopListening();
            releaseCamera();
            events.onCameraClosed(this);
            Logging.d(TAG, "Stop done");
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void listenForTextureFrames() {
        this.surfaceTextureHelper.startListening((frame) -> {
            this.checkIsOnCameraThread();
            if (this.sessionState != SessionState.RUNNING) {
                Logging.d(TAG, "Texture frame captured but camera is no longer running.");
            } else {
                if (!this.firstFrameReported) {
                    this.firstFrameReported = true;
                }

                VideoFrame modifiedFrame = new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix((TextureBufferImpl) frame.getBuffer(), false, 0), this.getFrameOrientation(), frame.getTimestampNs());
                this.events.onFrameCaptured(this, modifiedFrame);
                modifiedFrame.release();
            }
        });
    }

    @SuppressLint("SwitchIntDef")
    private int getFrameOrientation() {
        WindowManager wm = (WindowManager) this.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        int orientation = 0;
        switch (wm.getDefaultDisplay().getRotation()) {
            case 1:
                orientation = 90;
                break;
            case 2:
                orientation = 180;
                break;
            case 3:
                orientation = 270;
                break;
            case 0:
            default:
                break;
        }

        return orientation;
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }

    private enum SessionState {
        RUNNING,
        STOPPED;

        SessionState() {
        }
    }
}
