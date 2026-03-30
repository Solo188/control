package com.remotecontrol;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Отправляет скриншот на сервер и подтверждает выполнение команды (ACK).
 *
 * ИСПРАВЛЕНО:
 * 1. Shared OkHttpClient (singleton) — нет утечки пулов соединений
 * 2. engine.onScreenSent() гарантированно вызывается в finally (уже было, сохранено)
 * 3. Добавлена проверка response.isSuccessful() для upload
 * 4. Закрытие Response в try-with-resources для ACK
 */
public class ScreenCaptureSender {

    private static final String TAG = "Sender";

    // 🟢 Один shared клиент на всё приложение
    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)  // upload может занять время
            .build();

    private final String            baseUrl;
    private final HttpPollingEngine engine;

    public ScreenCaptureSender(String baseUrl, HttpPollingEngine engine) {
        this.baseUrl = baseUrl;
        this.engine  = engine;
    }

    public void send(final Bitmap bitmap, final int commandId) {
        new Thread(() -> {
            try {
                // ── Компрессия ──────────────────────────────────────
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
                byte[] bytes = stream.toByteArray();

                Log.d(TAG, "Отправка скриншота: " + bytes.length / 1024 + " KB");

                // ── Upload ──────────────────────────────────────────
                Request uploadReq = new Request.Builder()
                        .url(baseUrl + "/upload")
                        .post(RequestBody.create(bytes,
                                MediaType.parse("image/jpeg")))
                        .build();

                try (Response uploadResp = SHARED_CLIENT.newCall(uploadReq).execute()) {
                    if (!uploadResp.isSuccessful()) {
                        Log.w(TAG, "Upload failed: HTTP " + uploadResp.code());
                        // Продолжаем — ACK всё равно отправляем
                    } else {
                        Log.d(TAG, "Upload OK");
                    }
                }

                // ── ACK ─────────────────────────────────────────────
                JSONObject json = new JSONObject();
                json.put("id",     commandId);
                json.put("status", "done");

                Request ackReq = new Request.Builder()
                        .url(baseUrl + "/ack")
                        .post(RequestBody.create(
                                json.toString(),
                                MediaType.parse("application/json")))
                        .build();

                try (Response ackResp = SHARED_CLIENT.newCall(ackReq).execute()) {
                    Log.d(TAG, "ACK #" + commandId + " → HTTP " + ackResp.code());
                }

            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки скриншота", e);

            } finally {
                // Recycle bitmap — всегда
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                // Разблокируем polling — всегда, даже при ошибке
                if (engine != null) {
                    engine.onScreenSent();
                }
            }

        }, "ScreenSenderThread").start();
    }
}
