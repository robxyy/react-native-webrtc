package org.webrtc;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class UvcCameraEnumerator implements CameraEnumerator {
    private static final String TAG = "UvcCameraEnumerator";

    private final Context context;
    private final UsbManager usbManager;

    public UvcCameraEnumerator(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public String[] getDeviceNames() {
        Map<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        String[] deviceNames = new String[usbDevices.size()];
        int i = 0;
        for (UsbDevice device : usbDevices.values()) {
            deviceNames[i] = device.getDeviceName();
            i++;
        }
        return deviceNames;
    }

    @Override
    public boolean isFrontFacing(String s) {
        return false;
    }

    @Override
    public boolean isBackFacing(String s) {
        return false;
    }

    @Override
    public List<CameraEnumerationAndroid.CaptureFormat> getSupportedFormats(String s) {
        return Collections.emptyList();
    }

    @Override
    public CameraVideoCapturer createCapturer(String s, CameraVideoCapturer.CameraEventsHandler cameraEventsHandler) {
        return new UvcCameraCapturer(context, s, cameraEventsHandler);
    }
}
