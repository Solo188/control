package com.remotecontrol;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * HttpPollingEngine
 *
 * 1. GET /get_command  каждую секунду — ждёт команду
 * 2. Выполняет команду через AccessibilityService
 * 3. POST /ack        — сообщает серверу что команда выполнена (сервер сбрасывает очередь)
 * 4. uploadScreenshot — POST /upload с JPEG после скриншота
 */
public class HttpPollingEngine implements Runnable {

    private static final String TAG = "HttpPollingEngine";

    private final Context context;
    private volatile boolean running = true;

    private final OkHttpClient httpClient;

    public HttpPollingEngine(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    // ──────────────────────────────────────────────────
    //  Main loop
    // ──────────────────────────────────────────────────

    @Override
    public void run() {
        Log.i(TAG, "Polling started: " + Config.BASE_URL);
        while (running) {
            try {
                poll();
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "Poll error: " + e.getMessage());
                sleep(5000);
            }
        }
        Log.i(TAG, "Polling stopped");
    }

    public void stop() { running = false; }

    // ──────────────────────────────────────────────────
    //  GET /get_command
    // ──────────────────────────────────────────────────

    private void poll() throws IOException {
        Request req = new Request.Builder()
                .url(Config.ENDPOINT_GET_COMMAND)
                .get().build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return;

            String body = resp.body().string().trim();
            if (body.isEmpty() || body.equals("wait") || body.equals("{}")) return;

            JSONObject cmd = new JSONObject(body);
            handleCommand(cmd);
            ack(); // Сообщаем серверу что команда выполнена
        } catch (Exception e) {
            Log.w(TAG, "poll exception: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────
    //  Command dispatcher
    // ──────────────────────────────────────────────────

    private void handleCommand(JSONObject json) {
        try {
            String action = json.optString("a", "").toLowerCase();
            Log.d(TAG, "CMD: " + action + " | " + json);

            MyAccessibilityService a11y = MyAccessibilityService.getInstance();

            switch (action) {
                case "tap":
                case "click": {
                    if (a11y == null) break;
                    float x = (float) json.getDouble("x");
                    float y = (float) json.getDouble("y");
                    a11y.performClick(x, y);
                    break;
                }
                case "swipe": {
                    if (a11y == null) break;
                    float x1  = (float) json.getDouble("x1");
                    float y1  = (float) json.getDouble("y1");
                    float x2  = (float) json.getDouble("x2");
                    float y2  = (float) json.getDouble("y2");
                    long  dur = json.optLong("dur", 300);
                    a11y.performSwipe(x1, y1, x2, y2, dur);
                    break;
                }
                case "longpress": {
                    if (a11y == null) break;
                    float x = (float) json.getDouble("x");
                    float y = (float) json.getDouble("y");
                    a11y.performLongPress(x, y);
                    break;
                }
                case "home":
                    if (a11y != null) a11y.pressHome();
                    break;
                case "back":
                    if (a11y != null) a11y.pressBack();
                    break;
                case "recents":
                    if (a11y != null) a11y.pressRecents();
                    break;
                case "notifications":
                    if (a11y != null) a11y.openNotificationShade();
                    break;
                case "screenshot":
                case "screen": {
                    Intent intent = new Intent(context, ScreenCaptureRequestActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    break;
                }
                default:
                    Log.w(TAG, "Unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleCommand error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────
    //  POST /ack — сброс команды на сервере
    // ──────────────────────────────────────────────────

    private void ack() {
        try {
            RequestBody body = RequestBody.create(new byte[0]);
            Request req = new Request.Builder()
                    .url(Config.BASE_URL + "/ack")
                    .post(body).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                Log.d(TAG, "ACK: " + resp.code());
            }
        } catch (IOException e) {
            Log.w(TAG, "ACK error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────
    //  POST /upload — отправка скриншота
    // ──────────────────────────────────────────────────

    /**
     * Загружает JPEG на сервер.
     * Вызывается из фонового потока в ScreenCapture.
     */
    public void uploadScreenshot(File file) {
        if (file == null || !file.exists()) return;
        Log.d(TAG, "Uploading: " + file.length() / 1024 + " KB");

        RequestBody fileBody = RequestBody.create(
                file, MediaType.parse("image/jpeg"));

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();

        Request req = new Request.Builder()
                .url(Config.ENDPOINT_UPLOAD)
                .post(body).build();

        try (Response resp = httpClient.newCall(req).execute()) {
            Log.i(TAG, "Upload: HTTP " + resp.code());
        } catch (IOException e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
        } finally {
            file.delete();
        }
    }

    // ──────────────────────────────────────────────────

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
