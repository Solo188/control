package com.remotecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class TelegramService extends Service {
    private static TelegramService instance;
    private HttpEngine engine;
    private Thread thread;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, "control")
                .setContentTitle("Remote Control")
                .setContentText("Connected via Bore")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        startForeground(1, notification);

        if (thread == null || !thread.isAlive()) {
            engine = new HttpEngine(this);
            thread = new Thread(engine);
            thread.start();
        }
        return START_STICKY;
    }

    public static TelegramService getInstance() { return instance; }
    public HttpEngine getEngine() { return engine; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("control", "Control", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
