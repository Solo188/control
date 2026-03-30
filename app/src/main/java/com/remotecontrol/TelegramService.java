package com.remotecontrol;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TelegramService extends Service {

    private HttpPollingEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();

        engine = new HttpPollingEngine(
                "http://YOUR_SERVER_URL",

                command -> {
                    if ("tap".equals(command.action)) {
                        // обработка тапа
                    }
                },

                commandId -> {
                    ScreenCaptureRequestActivity.request(this, commandId);
                },

                commandId -> {
                    // можно оставить пустым
                }
        );

        ScreenCaptureRequestActivity.setEngine(engine);

        engine.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
