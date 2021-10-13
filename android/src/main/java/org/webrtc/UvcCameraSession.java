package org.webrtc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

import java.util.List;

public final class UvcCameraSession implements CameraSession {
    private static final String TAG = "UvcCameraSession";
    private final Handler cameraThreadHandler;
    private final Events events;
    private final Context context;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final LocalBroadcastManager localBroadcastManager;

    private final USBMonitor usbMonitor;
    private UsbDevice currentUsbDevice;
    private UVCCamera uvcCamera;
    private Surface uvcPreviewSurface;

    public static void create(CreateSessionCallback callback, Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate) {
        long constructionTimeNs = System.nanoTime();
        Log.d(TAG, "Open camera " + cameraId);
        callback.onDone(new UvcCameraSession(events, applicationContext, surfaceTextureHelper, cameraId, new CaptureFormat(width, height, framerate, framerate), constructionTimeNs));
    }

    private UvcCameraSession(Events events, Context context, SurfaceTextureHelper surfaceTextureHelper, String cameraName, CaptureFormat captureFormat, long constructionTimeNs) {
        Log.d(TAG, "Create new usb camera session on camera " + cameraName);
        this.cameraThreadHandler = new Handler();
        this.events = events;
        this.context = context;
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context);
        this.surfaceTextureHelper = surfaceTextureHelper;
        USBMonitor.OnDeviceConnectListener deviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice usbDevice) {
                Log.i(TAG, "Usb device(" + usbDevice.getDeviceName() + "), onAttach");
            }

            @Override
            public void onConnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock, boolean b) {
                Log.i(TAG, "Usb device(" + usbDevice.getDeviceName() + "), onConnect");
                if (usbDevice.getDeviceName().equals(cameraName)) {
                    try {
                        final UVCCamera camera = new UVCCamera();
                        camera.open(usbControlBlock);
                        camera.setAutoFocus(true);
                        camera.setAutoWhiteBlance(true);
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
                        if (uvcPreviewSurface != null) {
                            uvcPreviewSurface.release();
                            uvcPreviewSurface = null;
                        }
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
                        startCapturing();
                        camera.setPreviewDisplay(uvcPreviewSurface);
                        camera.startPreview();
                        uvcCamera = camera;
                        Intent intent = new Intent("org.jitsi.meet.VIDEO_MUTED_CHANGED_X");
                        intent.putExtra("muted", false);
                        localBroadcastManager.sendBroadcast(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                        // ignore
                    }
                }
            }

            @Override
            public void onDisconnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock) {
                Log.i(TAG, "Usb device(" + usbDevice.getDeviceName() + "), onDisconnect");
                Intent intent = new Intent("org.jitsi.meet.VIDEO_MUTED_CHANGED_X");
                intent.putExtra("muted", true);
                localBroadcastManager.sendBroadcast(intent);
            }

            @Override
            public void onDettach(UsbDevice usbDevice) {
                Log.i(TAG, "Usb device(" + usbDevice.getDeviceName() + "), onDettach");
            }

            @Override
            public void onCancel(UsbDevice usbDevice) {
                Log.i(TAG, "Usb device(" + usbDevice.getDeviceName() + "), onCancel");
            }
        };
        this.usbMonitor = new USBMonitor(context, deviceConnectListener);
        List<UsbDevice> devices = usbMonitor.getDeviceList();
        for (UsbDevice device : devices) {
            if (device.getDeviceName().equals(cameraName)) {
                currentUsbDevice = device;
                break;
            }
        }
        if (currentUsbDevice == null) {
            return;
        }
        usbMonitor.register();
        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
        usbMonitor.requestPermission(currentUsbDevice);
        events.onCameraOpening();
    }

    private void startCapturing() {
        Log.d(TAG, "Start capturing uvc camera session on camera " + this.currentUsbDevice.getDeviceName());
        if (uvcPreviewSurface == null) {
            uvcPreviewSurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
            surfaceTextureHelper.startListening((frame) -> {
                this.checkIsOnCameraThread();
                VideoFrame modifiedFrame = new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix((TextureBufferImpl) frame.getBuffer(), false, 0), getFrameOrientation(), frame.getTimestampNs());
                events.onFrameCaptured(UvcCameraSession.this, modifiedFrame);
                modifiedFrame.release();
            });
        }
    }

    @Override
    public void stop() {
        if (this.currentUsbDevice == null) {
            Log.d(TAG, "Stop uvc camera session on camera");
            return;
        }
        Log.d(TAG, "Stop uvc camera session on camera " + this.currentUsbDevice.getDeviceName());
        this.checkIsOnCameraThread();
        if (uvcCamera != null) {
            try {
                uvcCamera.setStatusCallback(null);
                uvcCamera.setButtonCallback(null);
                uvcCamera.stopPreview();
                uvcCamera.close();
                uvcCamera.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
            uvcCamera = null;
        }
        if (uvcPreviewSurface != null) {
            uvcPreviewSurface.release();
            uvcPreviewSurface = null;
        }
        events.onCameraClosed(this);
        surfaceTextureHelper.stopListening();
        usbMonitor.unregister();
        usbMonitor.destroy();
    }

    @SuppressLint("SwitchIntDef")
    private int getFrameOrientation() {
        WindowManager wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
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
}
