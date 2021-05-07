package org.webrtc;

import android.content.Context;

public final class UvcCameraCapturer extends CameraCapturer {
    private static final String TAG = "UvcCameraCapturer";

    public UvcCameraCapturer(Context context, String cameraName, CameraEventsHandler eventsHandler) {
        super(cameraName, eventsHandler, new UvcCameraEnumerator(context));
    }

    @Override
    protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context context, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate) {
        UvcCameraSession.create(createSessionCallback, events, context, surfaceTextureHelper, cameraName, width, height, framerate);
    }
}