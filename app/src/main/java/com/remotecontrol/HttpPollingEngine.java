package com.remotecontrol;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpPollingEngine implements Runnable {
    private static final String TAG = "HttpPollingEngine";
    private final Context context;
    private final OkHttpClient httpClient;
    private volatile boolean running = true;

    public HttpPollingEngine(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        while (running) {
            try {
                Request request = new Request.Builder().url(Config.ENDPOINT_GET_COMMAND).get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body().string();
                        if (!body.equals("wait")) {
                            Log.d(TAG, "Выполнение: " + body);
                            handleCommand(new JSONObject(body));
                            sendAck(); // Сообщаем серверу, что команда принята
                        }
                    }
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleCommand(JSONObject json) {
        try {
            MyAccessibilityService service = MyAccessibilityService.getInstance();
            if (service == null) return;

            String action = json.getString("a");
            if (action.equals("tap")) {
                service.performClick((float)json.getDouble("x"), (float)json.getDouble("y"));
            } else if (action.equals("home")) {
                service.pressHome();
            } else if (action.equals("back")) {
                service.pressBack();
            }

            // ВАЖНО: Запрашиваем скриншот СРАЗУ после действия
            Intent intent = new Intent(context, ScreenCaptureRequestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

        } catch (Exception e) {
            Log.e(TAG, "handleCommand Error: " + e.getMessage());
        }
    }

    private void sendAck() {
        // Эндпоинт /ack должен быть добавлен в Config.java
        Request req = new Request.Builder().url(Config.BASE_URL + "/ack").get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            Log.d(TAG, "ACK sent: " + res.code());
        } catch (IOException ignored) {}
    }

    public void uploadScreenshot(File file) {
        if (file == null || !file.exists()) return;
        RequestBody body = RequestBody.create(file, MediaType.parse("image/jpeg"));
        Request req = new Request.Builder().url(Config.ENDPOINT_UPLOAD).post(body).build();
        try (Response resp = httpClient.newCall(req).execute()) {
            Log.i(TAG, "Скриншот загружен: " + resp.code());
        } catch (IOException e) {
            Log.e(TAG, "Ошибка загрузки: " + e.getMessage());
        } finally {
            file.delete();
        }
    }
}
