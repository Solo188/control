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
 * Бесконечный цикл GET /get_command каждые 1–2 секунды.
 * Поддерживаемые действия: tap, swipe, screenshot, home, back.
 * Скриншот отправляется через POST /upload.
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
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    // ──────────────────────────────────────────────────
    //  Main Loop
    // ──────────────────────────────────────────────────

    @Override
    public void run() {
        Log.i(TAG, "Started polling: " + Config.ENDPOINT_GET_COMMAND);
        while (running) {
            try {
                poll();
                Thread.sleep(1000); // 1 секунда между запросами
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Poll error: " + e.getMessage());
                sleep(5000); // Пауза при сетевой ошибке
            }
        }
        Log.i(TAG, "Polling stopped");
    }

    public void stop() {
        running = false;
    }

    // ──────────────────────────────────────────────────
    //  HTTP GET /get_command
    // ──────────────────────────────────────────────────

    private void poll() throws IOException {
        Request request = new Request.Builder()
                .url(Config.ENDPOINT_GET_COMMAND)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            if (response.body() == null) return;

            String body = response.body().string().trim();

            // Сервер возвращает "wait" или пустую строку если команд нет
            if (body.isEmpty() || body.equals("wait") || body.equals("{}")) return;

            JSONObject json = new JSONObject(body);
            handleCommand(json);
        }
    }

    // ──────────────────────────────────────────────────
    //  Command Dispatcher
    // ──────────────────────────────────────────────────

    private void handleCommand(JSONObject json) {
        try {
            String action = json.optString("a", json.optString("action", "")).toLowerCase();
            Log.d(TAG, "Command: " + action + " | json: " + json);

            MyAccessibilityService a11y = MyAccessibilityService.getInstance();

            switch (action) {

                case "tap":
                case "click": {
                    if (a11y == null) { Log.w(TAG, "a11y not running"); break; }
                    float x = (float) json.getDouble("x");
                    float y = (float) json.getDouble("y");
                    a11y.performClick(x, y);
                    break;
                }

                case "swipe": {
                    if (a11y == null) { Log.w(TAG, "a11y not running"); break; }
                    float x1 = (float) json.getDouble("x1");
                    float y1 = (float) json.getDouble("y1");
                    float x2 = (float) json.getDouble("x2");
                    float y2 = (float) json.getDouble("y2");
                    long dur = json.optLong("dur", 300);
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
                    // Запускаем прозрачную Activity для запроса MediaProjection
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
    //  POST /upload  (отправка скриншота)
    // ──────────────────────────────────────────────────

    /**
     * Загружает JPEG файл на сервер.
     * Вызывается из ScreenCapture после захвата.
     * Выполняется в вызывающем потоке — вызывать из фонового треда!
     */
    public void uploadScreenshot(File file) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "uploadScreenshot: file is null or missing");
            return;
        }
        Log.d(TAG, "Uploading screenshot: " + file.length() / 1024 + " KB");

        RequestBody fileBody = RequestBody.create(file,
                MediaType.parse("image/jpeg"));

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(Config.ENDPOINT_UPLOAD)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                Log.i(TAG, "Screenshot uploaded OK");
            } else {
                Log.e(TAG, "Upload failed: HTTP " + response.code());
            }
        } catch (IOException e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
        } finally {
            // Удаляем временный файл после загрузки
            file.delete();
        }
    }

    // ──────────────────────────────────────────────────
    //  Utils
    // ──────────────────────────────────────────────────

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
