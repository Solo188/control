package com.remotecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * TelegramService
 *
 * Foreground Service, который запускает TelegramEngine в отдельном потоке.
 * Тип сервиса: specialUse (т.к. это не медиа/локация/камера).
 */
public class TelegramService extends Service {

    private static final String TAG = "TelegramService";
    private static final String CHANNEL_ID = "telegram_service";
    private static final int NOTIF_ID = 1;

    private static TelegramService instance;

    private TelegramEngine engine;
    private Thread engineThread;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        if (engineThread == null || !engineThread.isAlive()) {
            engine = new TelegramEngine(
                    this,
                    Config.BOT_TOKEN,
                    Config.MINI_APP_URL
            );
            engineThread = new Thread(engine, "TelegramEngineThread");
            engineThread.setDaemon(true);
            engineThread.start();
            Log.i(TAG, "TelegramEngine started");
        }

        return START_STICKY; // Система перезапустит сервис при убийстве
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stop();
        if (engineThread != null) engineThread.interrupt();
        instance = null;
        Log.i(TAG, "TelegramService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static TelegramService getInstance() { return instance; }
    public TelegramEngine getEngine() { return engine; }

    // ──────────────────────────────────────────────────
    //  Notification
    // ──────────────────────────────────────────────────

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RemoteControl")
                .setContentText("Управление через Telegram активно")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Telegram Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Фоновое управление через Telegram Bot");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
