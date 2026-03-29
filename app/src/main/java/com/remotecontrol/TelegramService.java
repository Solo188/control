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
 * Название сохранено для совместимости с AndroidManifest.xml.
 * Внутри запускает HttpPollingEngine — никакого Telegram.
 */
public class TelegramService extends Service {

    private static final String TAG      = "TelegramService";
    private static final String CHAN_ID  = "remote_control";
    private static final int    NOTIF_ID = 1;

    private static TelegramService instance;

    private HttpPollingEngine engine;
    private Thread engineThread;

    // ──────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────

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
            engine = new HttpPollingEngine(this);
            engineThread = new Thread(engine, "HttpPollingThread");
            engineThread.setDaemon(true);
            engineThread.start();
            Log.i(TAG, "HttpPollingEngine started → " + Config.BASE_URL);
        }

        return START_STICKY; // Перезапускать при убийстве системой
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

    // ──────────────────────────────────────────────────
    //  Public accessors
    // ──────────────────────────────────────────────────

    public static TelegramService getInstance() { return instance; }

    /** Возвращает движок для загрузки скриншотов из ScreenCaptureService */
    public HttpPollingEngine getEngine() { return engine; }

    // ──────────────────────────────────────────────────
    //  Notification
    // ──────────────────────────────────────────────────

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHAN_ID)
                .setContentTitle("RemoteControl")
                .setContentText("Подключено: " + Config.BASE_URL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHAN_ID, "Remote Control", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Фоновое управление через HTTP");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
