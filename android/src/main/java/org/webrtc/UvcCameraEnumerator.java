package org.webrtc;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import com.serenegiant.usb.USBMonitor;

import java.util.Collections;
import java.util.List;

public final class UvcCameraEnumerator implements CameraEnumerator {
    private static final String TAG = "UvcCameraEnumerator";

    private final Context context;
    private final USBMonitor usbMonitor;

    public UvcCameraEnumerator(Context context) {
        this.context = context;
        this.usbMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice usbDevice) {
            }

            @Override
            public void onDettach(UsbDevice usbDevice) {
            }

            @Override
            public void onConnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock, boolean b) {
            }

            @Override
            public void onDisconnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock) {
            }

            @Override
            public void onCancel(UsbDevice usbDevice) {
            }
        });
    }

    @Override
    public String[] getDeviceNames() {
        List<UsbDevice> usbDevices = usbMonitor.getDeviceList();
        String[] deviceNames = new String[usbDevices.size()];
        int i = 0;
        for (UsbDevice device : usbDevices) {
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
