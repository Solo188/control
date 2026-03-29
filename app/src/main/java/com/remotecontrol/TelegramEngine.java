package com.remotecontrol;

import android.content.Context;
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

/**
 * TelegramEngine
 *
 * Реализует бесконечный Long Polling цикл (getUpdates).
 * Парсит web_app_data и обычные команды (/start, /screenshot, /home, /back, /tap, /swipe).
 * Отправляет скриншоты через sendPhoto.
 */
public class TelegramEngine implements Runnable {

    private static final String TAG = "TelegramEngine";

    // ──────── Настройки — задаются через Config ────────
    private final String botToken;
    private final String miniAppUrl;       // URL вашего GitHub Pages Mini App
    private final Context context;

    private static final String API_BASE = "https://api.telegram.org/bot";

    private final OkHttpClient httpClient;
    private volatile boolean running = true;
    private long lastUpdateId = 0;

    // ──────────────────────────────────────────────────
    //  Constructor
    // ──────────────────────────────────────────────────

    public TelegramEngine(Context context, String botToken, String miniAppUrl) {
        this.context = context.getApplicationContext();
        this.botToken = botToken;
        this.miniAppUrl = miniAppUrl;

        // Long Polling: таймаут чтения должен быть > polling timeout
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(70, TimeUnit.SECONDS) // polling timeout = 60с
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void stop() {
        running = false;
    }

    // ──────────────────────────────────────────────────
    //  Main Loop
    // ──────────────────────────────────────────────────

    @Override
    public void run() {
        Log.i(TAG, "Long Polling started, miniAppUrl=" + miniAppUrl);
        while (running) {
            try {
                pollUpdates();
            } catch (Exception e) {
                Log.e(TAG, "Polling error: " + e.getMessage());
                sleep(5000); // Пауза перед повтором при ошибке
            }
        }
        Log.i(TAG, "Long Polling stopped");
    }

    // ──────────────────────────────────────────────────
    //  getUpdates
    // ──────────────────────────────────────────────────

    private void pollUpdates() throws IOException, JSONException {
        String url = API_BASE + botToken + "/getUpdates"
                + "?timeout=60"
                + "&allowed_updates=[\"message\",\"callback_query\"]"
                + (lastUpdateId > 0 ? "&offset=" + (lastUpdateId + 1) : "");

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "getUpdates HTTP " + response.code());
                sleep(3000);
                return;
            }

            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);

            if (!json.optBoolean("ok", false)) {
                Log.w(TAG, "getUpdates not ok: " + body);
                sleep(3000);
                return;
            }

            JSONArray results = json.getJSONArray("result");
            for (int i = 0; i < results.length(); i++) {
                JSONObject update = results.getJSONObject(i);
                lastUpdateId = update.getLong("update_id");
                handleUpdate(update);
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Update Dispatcher
    // ──────────────────────────────────────────────────

    private void handleUpdate(JSONObject update) {
        try {
            if (update.has("message")) {
                handleMessage(update.getJSONObject("message"));
            } else if (update.has("callback_query")) {
                handleCallbackQuery(update.getJSONObject("callback_query"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "handleUpdate error: " + e.getMessage());
        }
    }

    private void handleMessage(JSONObject message) throws JSONException {
        long chatId = message.getJSONObject("chat").getLong("id");

        // ─── Web App Data (из Mini App) ───
        if (message.has("web_app_data")) {
            String data = message.getJSONObject("web_app_data").getString("data");
            Log.d(TAG, "web_app_data: " + data);
            handleWebAppData(chatId, data);
            return;
        }

        // ─── Обычные текстовые команды ───
        String text = message.optString("text", "").trim();
        if (text.isEmpty()) return;

        Log.d(TAG, "message from " + chatId + ": " + text);

        if (text.equals("/start") || text.equals("/help")) {
            sendStartMenu(chatId);
        } else if (text.equals("/screenshot") || text.equals("/screen")) {
            handleScreenshot(chatId);
        } else if (text.equals("/home")) {
            execHome(chatId);
        } else if (text.equals("/back")) {
            execBack(chatId);
        } else if (text.equals("/recents")) {
            execRecents(chatId);
        } else if (text.startsWith("/tap ")) {
            // /tap 0.5 0.5
            parseTapCommand(chatId, text.substring(5).trim());
        } else if (text.startsWith("/swipe ")) {
            // /swipe 0.5 0.8 0.5 0.2
            parseSwipeCommand(chatId, text.substring(7).trim());
        } else {
            sendMessage(chatId, "❓ Неизвестная команда. Используй /start");
        }
    }

    private void handleCallbackQuery(JSONObject cbq) throws JSONException {
        long chatId = cbq.getJSONObject("message").getJSONObject("chat").getLong("id");
        String callbackId = cbq.getString("id");
        String data = cbq.optString("data", "");

        answerCallbackQuery(callbackId);

        switch (data) {
            case "screenshot": handleScreenshot(chatId); break;
            case "home":       execHome(chatId);         break;
            case "back":       execBack(chatId);         break;
            case "recents":    execRecents(chatId);      break;
            default:
                sendMessage(chatId, "Действие: " + data);
        }
    }

    // ──────────────────────────────────────────────────
    //  Web App Data Parser
    // ──────────────────────────────────────────────────

    /**
     * Разбирает JSON из Mini App.
     *
     * Поддерживаемые форматы:
     *   {"a":"tap",   "x":0.5, "y":0.5}
     *   {"a":"swipe", "x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2}
     *   {"a":"swipe", "x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2,"dur":400}
     *   {"a":"screenshot"}
     *   {"a":"home"}
     *   {"a":"back"}
     *   {"a":"recents"}
     *   {"a":"longpress","x":0.5,"y":0.5}
     *   {"a":"notifications"}
     */
    private void handleWebAppData(long chatId, String rawJson) {
        try {
            JSONObject cmd = new JSONObject(rawJson);
            String action = cmd.optString("a", cmd.optString("action", "")).toLowerCase();

            MyAccessibilityService a11y = MyAccessibilityService.getInstance();
            boolean a11yOk = a11y != null;

            switch (action) {

                case "tap":
                case "click": {
                    float x = (float) cmd.getDouble("x");
                    float y = (float) cmd.getDouble("y");
                    if (a11yOk) {
                        boolean ok = a11y.performClick(x, y);
                        sendMessage(chatId, ok ? "✅ Клик (%.2f, %.2f)".formatted(x, y)
                                              : "❌ Жест отклонён системой");
                    } else {
                        sendMessage(chatId, "⚠️ AccessibilityService не активен");
                    }
                    break;
                }

                case "swipe": {
                    float x1 = (float) cmd.getDouble("x1");
                    float y1 = (float) cmd.getDouble("y1");
                    float x2 = (float) cmd.getDouble("x2");
                    float y2 = (float) cmd.getDouble("y2");
                    long dur = cmd.optLong("dur", 300);
                    if (a11yOk) {
                        boolean ok = a11y.performSwipe(x1, y1, x2, y2, dur);
                        sendMessage(chatId, ok ? "✅ Свайп выполнен" : "❌ Жест отклонён");
                    } else {
                        sendMessage(chatId, "⚠️ AccessibilityService не активен");
                    }
                    break;
                }

                case "longpress": {
                    float x = (float) cmd.getDouble("x");
                    float y = (float) cmd.getDouble("y");
                    if (a11yOk) {
                        a11y.performLongPress(x, y);
                        sendMessage(chatId, "✅ Long press (%.2f, %.2f)".formatted(x, y));
                    } else {
                        sendMessage(chatId, "⚠️ AccessibilityService не активен");
                    }
                    break;
                }

                case "screenshot":
                case "screen":
                    handleScreenshot(chatId);
                    break;

                case "home":
                    execHome(chatId);
                    break;

                case "back":
                    execBack(chatId);
                    break;

                case "recents":
                    execRecents(chatId);
                    break;

                case "notifications":
                    if (a11yOk) a11y.openNotificationShade();
                    break;

                default:
                    sendMessage(chatId, "❓ Неизвестное действие: " + action);
            }

        } catch (JSONException e) {
            Log.e(TAG, "handleWebAppData JSON error: " + e.getMessage());
            sendMessage(chatId, "❌ Ошибка разбора команды: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────
    //  Command Handlers
    // ──────────────────────────────────────────────────

    private void handleScreenshot(long chatId) {
        sendMessage(chatId, "📸 Делаю скриншот...");
        ScreenCapture.requestScreenshot(context, chatId, this);
    }

    private void execHome(long chatId) {
        MyAccessibilityService a11y = MyAccessibilityService.getInstance();
        if (a11y != null) {
            a11y.pressHome();
            sendMessage(chatId, "🏠 HOME");
        } else {
            sendMessage(chatId, "⚠️ AccessibilityService не активен");
        }
    }

    private void execBack(long chatId) {
        MyAccessibilityService a11y = MyAccessibilityService.getInstance();
        if (a11y != null) {
            a11y.pressBack();
            sendMessage(chatId, "◀️ BACK");
        } else {
            sendMessage(chatId, "⚠️ AccessibilityService не активен");
        }
    }

    private void execRecents(long chatId) {
        MyAccessibilityService a11y = MyAccessibilityService.getInstance();
        if (a11y != null) {
            a11y.pressRecents();
            sendMessage(chatId, "📋 Recents");
        } else {
            sendMessage(chatId, "⚠️ AccessibilityService не активен");
        }
    }

    private void parseTapCommand(long chatId, String args) {
        try {
            String[] parts = args.split("\\s+");
            float x = Float.parseFloat(parts[0]);
            float y = Float.parseFloat(parts[1]);
            MyAccessibilityService a11y = MyAccessibilityService.getInstance();
            if (a11y != null) {
                a11y.performClick(x, y);
                sendMessage(chatId, "✅ Tap %.2f %.2f".formatted(x, y));
            } else {
                sendMessage(chatId, "⚠️ AccessibilityService не активен");
            }
        } catch (Exception e) {
            sendMessage(chatId, "❌ Формат: /tap <x> <y>  (0.0–1.0)");
        }
    }

    private void parseSwipeCommand(long chatId, String args) {
        try {
            String[] parts = args.split("\\s+");
            float x1 = Float.parseFloat(parts[0]);
            float y1 = Float.parseFloat(parts[1]);
            float x2 = Float.parseFloat(parts[2]);
            float y2 = Float.parseFloat(parts[3]);
            MyAccessibilityService a11y = MyAccessibilityService.getInstance();
            if (a11y != null) {
                a11y.performSwipe(x1, y1, x2, y2);
                sendMessage(chatId, "✅ Swipe выполнен");
            } else {
                sendMessage(chatId, "⚠️ AccessibilityService не активен");
            }
        } catch (Exception e) {
            sendMessage(chatId, "❌ Формат: /swipe <x1> <y1> <x2> <y2>  (0.0–1.0)");
        }
    }

    // ──────────────────────────────────────────────────
    //  Telegram API — отправка сообщений
    // ──────────────────────────────────────────────────

    /** Отправляет стартовое меню с Inline-кнопкой открытия Mini App */
    public void sendStartMenu(long chatId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            JSONArray row1 = new JSONArray();

            // Кнопка открытия Mini App
            JSONObject webAppBtn = new JSONObject();
            webAppBtn.put("text", "🖥 Открыть панель управления");
            JSONObject webApp = new JSONObject();
            webApp.put("url", miniAppUrl);
            webAppBtn.put("web_app", webApp);
            row1.put(webAppBtn);
            rows.put(row1);

            // Вторая строка — быстрые кнопки
            JSONArray row2 = new JSONArray();
            row2.put(makeCallbackBtn("📸 Скриншот", "screenshot"));
            row2.put(makeCallbackBtn("🏠 Home", "home"));
            row2.put(makeCallbackBtn("◀️ Back", "back"));
            rows.put(row2);

            keyboard.put("inline_keyboard", rows);

            JSONObject params = new JSONObject();
            params.put("chat_id", chatId);
            params.put("text", "🤖 *RemoteControl*\n\nВыбери действие или открой панель управления:"
                    );
            params.put("parse_mode", "Markdown");
            params.put("reply_markup", keyboard);

            post("sendMessage", params.toString());

        } catch (JSONException e) {
            Log.e(TAG, "sendStartMenu error: " + e.getMessage());
        }
    }

    private JSONObject makeCallbackBtn(String text, String data) throws JSONException {
        JSONObject btn = new JSONObject();
        btn.put("text", text);
        btn.put("callback_data", data);
        return btn;
    }

    /** Отправляет текстовое сообщение */
    public void sendMessage(long chatId, String text) {
        try {
            JSONObject params = new JSONObject();
            params.put("chat_id", chatId);
            params.put("text", text);
            post("sendMessage", params.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendMessage error: " + e.getMessage());
        }
    }

    /** Отправляет фото (скриншот) */
    public void sendPhoto(long chatId, File imageFile, String caption) {
        String url = API_BASE + botToken + "/sendPhoto";
        try {
            RequestBody fileBody = RequestBody.create(imageFile,
                    MediaType.parse("image/jpeg"));

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", String.valueOf(chatId))
                    .addFormDataPart("photo", imageFile.getName(), fileBody)
                    .addFormDataPart("caption", caption != null ? caption : "")
                    .build();

            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "sendPhoto failed: " + response.code());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "sendPhoto IO error: " + e.getMessage());
        }
    }

    private void answerCallbackQuery(String callbackId) {
        try {
            JSONObject params = new JSONObject();
            params.put("callback_query_id", callbackId);
            post("answerCallbackQuery", params.toString());
        } catch (JSONException e) {
            Log.e(TAG, "answerCallbackQuery error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────
    //  HTTP Helper
    // ──────────────────────────────────────────────────

    private void post(String method, String jsonBody) {
        String url = API_BASE + botToken + "/" + method;
        RequestBody body = RequestBody.create(jsonBody,
                MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, method + " HTTP " + response.code());
            }
        } catch (IOException e) {
            Log.e(TAG, method + " error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────
    //  Utils
    // ──────────────────────────────────────────────────

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
