package com.remotecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Главный сервис: запускает polling, управляет жизненным циклом.
 *
 * ИСПРАВЛЕНО:
 * 1. Добавлен startForeground() — обязателен на Android 12+ (foregroundServiceType=specialUse)
 * 2. Используется Config.BASE_URL вместо "http://YOUR_SERVER_URL"
 * 3. Добавлен обработчик команд tap/swipe/home/back
 * 4. onDestroy() корректно зачищает instance
 */
public class TelegramService extends Service {

    private static final String TAG        = "TelegramService";
    private static final int    NOTIF_ID   = 1;
    private static final String CHANNEL_ID = "remote_control";

    private static TelegramService instance;
    private HttpPollingEngine engine;

    public static TelegramService getInstance() { return instance; }
    public HttpPollingEngine getEngine()         { return engine; }

    // ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 🔴 FIX #1: startForeground() ОБЯЗАТЕЛЕН до любой работы
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        // 🔴 FIX #2: используем Config.BASE_URL, НЕ "http://YOUR_SERVER_URL"
        engine = new HttpPollingEngine(
                Config.BASE_URL,

                // CommandExecutor — обрабатываем команды
                command -> {
                    MyAccessibilityService svc = MyAccessibilityService.getInstance();
                    if (svc == null) {
                        Log.w(TAG, "AccessibilityService не запущен — команда пропущена");
                        return;
                    }
                    switch (command.action) {
                        case "tap":
                            svc.performClick((float) command.x, (float) command.y);
                            break;
                        case "swipe":
                            // x,y — старт; x2,y2 — конец; duration — длительность мс
                            svc.performSwipe((float) command.x, (float) command.y,
                                             (float) command.x2, (float) command.y2,
                                             command.duration > 0 ? command.duration : 300);
                            break;
                        case "long_press":
                            svc.performLongPress((float) command.x, (float) command.y);
                            break;
                        case "home":
                            svc.pressHome();
                            break;
                        case "back":
                            svc.pressBack();
                            break;
                        case "recents":
                            svc.pressRecents();
                            break;
                        default:
                            Log.w(TAG, "Неизвестная команда: " + command.action);
                    }
                },

                // ScreenRequester — запрашиваем скриншот
                commandId -> ScreenCaptureRequestActivity.request(this, commandId),

                // AckSender — пустая реализация, ACK отправляет ScreenCaptureSender
                commandId -> {}
        );

        ScreenCaptureRequestActivity.setEngine(engine);
        engine.start();

        Log.i(TAG, "✅ TelegramService запущен, polling начат");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: система перезапустит сервис если он будет убит
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        instance = null;
        Log.i(TAG, "TelegramService остановлен");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ──────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Remote Control Service",
                NotificationManager.IMPORTANCE_LOW  // тихий канал, без звука
        );
        channel.setDescription("Фоновая работа Remote Control");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote Control")
                .setContentText("Ожидание команд...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)   // нельзя смахнуть
                .build();
    }
}
