package com.remotecontrol;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public class HttpEngine implements Runnable {
    private static final String TAG = "HttpEngine";
    private final Context context;
    private final OkHttpClient httpClient;
    private volatile boolean running = true;

    public HttpEngine(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void run() {
        Log.i(TAG, "HttpEngine started. Polling: " + Config.SERVER_URL);
        while (running) {
            try {
                // Запрашиваем команду у вашего сервера в Termux
                Request request = new Request.Builder()
                        .url(Config.SERVER_URL + "/get_command")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String data = response.body().string();
                        if (!data.isEmpty() && !data.equals("wait")) {
                            handleCommand(new JSONObject(data));
                        }
                    }
                }
                Thread.sleep(1000); // Пауза между опросами
            } catch (Exception e) {
                Log.e(TAG, "Poll error: " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleCommand(JSONObject json) {
        try {
            String action = json.getString("a");
            MyAccessibilityService a11y = MyAccessibilityService.getInstance();
            Log.i(TAG, "Executing: " + action);

            switch (action) {
                case "screenshot":
                    Intent intent = new Intent(context, ScreenCaptureRequestActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    break;
                case "home": if (a11y != null) a11y.pressHome(); break;
                case "back": if (a11y != null) a11y.pressBack(); break;
                case "tap":
                    if (a11y != null) a11y.performClick((float)json.getDouble("x"), (float)json.getDouble("y"));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Command parsing error", e);
        }
    }

    public void stop() { running = false; }
}
