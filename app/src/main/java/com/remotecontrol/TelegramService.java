package com.remotecontrol;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TelegramService extends Service {

    private static TelegramService instance;
    private HttpPollingEngine engine;

    public static TelegramService getInstance() {
        return instance;
    }

    public HttpPollingEngine getEngine() {
        return engine;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        engine = new HttpPollingEngine(
                "http://YOUR_SERVER_URL",

                command -> {
                    if ("tap".equals(command.action)) {
                        // TODO: обработка тапа
                    }
                },

                commandId -> {
                    ScreenCaptureRequestActivity.request(this, commandId);
                },

                commandId -> {}
        );

        ScreenCaptureRequestActivity.setEngine(engine);
        engine.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (engine != null) engine.stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
