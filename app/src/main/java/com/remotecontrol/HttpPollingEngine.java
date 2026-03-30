package com.remotecontrol;

import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Polling-движок: каждые 1–2 сек спрашивает сервер о новых командах.
 *
 * ИСПРАВЛЕНО:
 * 1. URL исправлен: /get_command (было /command — 404)
 * 2. Добавлена null-проверка response.body() перед .string()
 * 3. Добавлена проверка HTTP 204 (нет команд) — не парсим пустое тело
 * 4. Добавлены таймауты OkHttpClient
 * 5. Добавлен reset isBusy при ошибке с задержкой (не зависает навсегда)
 * 6. Поле action теперь "action" (консистентно с сервером), fallback на "a"
 */
public class HttpPollingEngine {

    public interface CommandExecutor {
        void execute(Command command);
    }

    public interface ScreenRequester {
        void request(int commandId);
    }

    public interface AckSender {
        void sendAck(int commandId);
    }

    public static class Command {
        public int    id;
        public String action;
        public double x;
        public double y;
        public double x2;      // для swipe
        public double y2;      // для swipe
        public long   duration; // для swipe/long_press (мс)
    }

    private static final String TAG            = "Polling";
    private static final long   POLL_INTERVAL  = 1_500L;  // мс между запросами
    private static final long   BUSY_TIMEOUT   = 15_000L; // мс — сброс isBusy при зависании

    private final String          baseUrl;
    private final CommandExecutor executor;
    private final ScreenRequester requester;
    private final AckSender       ackSender;

    // Один shared клиент с таймаутами — НЕ создаём новый на каждый запрос
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    private volatile boolean running     = true;
    private volatile boolean isBusy      = false;
    private volatile long    busySince   = 0;

    private int lastCommandId = -1;

    public HttpPollingEngine(String baseUrl,
                             CommandExecutor executor,
                             ScreenRequester requester,
                             AckSender ackSender) {
        this.baseUrl   = baseUrl;
        this.executor  = executor;
        this.requester = requester;
        this.ackSender = ackSender;
    }

    public void start() {
        new Thread(() -> {
            while (running) {
                // Защита от вечного зависания: если isBusy > BUSY_TIMEOUT — сбрасываем
                if (isBusy && (System.currentTimeMillis() - busySince) > BUSY_TIMEOUT) {
                    Log.w(TAG, "isBusy завис дольше " + BUSY_TIMEOUT + "мс — сброс");
                    isBusy = false;
                }

                poll();

                try {
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Log.i(TAG, "Polling остановлен");
        }, "HttpPollingThread").start();
    }

    public void stop() {
        running = false;
    }

    private void poll() {
        if (isBusy) return;

        try {
            // 🔴 FIX #1: правильный endpoint /get_command (было /command)
            Request request = new Request.Builder()
                    .url(baseUrl + "/get_command")
                    .build();

            try (Response response = client.newCall(request).execute()) {

                // 🔴 FIX #2: 204 = нет команд, выходим без парсинга
                if (response.code() == 204) return;

                if (!response.isSuccessful()) {
                    Log.w(TAG, "HTTP " + response.code());
                    return;
                }

                // 🔴 FIX #3: null-проверка body перед .string()
                ResponseBody responseBody = response.body();
                if (responseBody == null) return;

                String body = responseBody.string();
                if (body.isEmpty()) return;

                JSONObject json = new JSONObject(body);

                Command command = new Command();
                command.id = json.getInt("id");

                // Читаем "action" (новый формат) или "a" (legacy fallback)
                command.action   = json.has("action") ? json.getString("action")
                                                       : json.optString("a", "");
                command.x        = json.optDouble("x", 0);
                command.y        = json.optDouble("y", 0);
                command.x2       = json.optDouble("x2", 0);
                command.y2       = json.optDouble("y2", 0);
                command.duration = json.optLong("duration", 0);

                // Дедупликация: не выполняем одну команду дважды
                if (command.id == lastCommandId) return;

                lastCommandId = command.id;
                isBusy        = true;
                busySince     = System.currentTimeMillis();

                executor.execute(command);
                requester.request(command.id);
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка polling", e);
            // Не сбрасываем isBusy здесь — таймер выше это сделает
        }
    }

    /** Вызывается из ScreenCaptureSender когда скриншот отправлен (или при ошибке) */
    public void onScreenSent() {
        isBusy = false;
        Log.d(TAG, "isBusy = false, polling продолжается");
    }
}
