package com.remotecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * ScreenCaptureService
 *
 * Foreground Service с типом mediaProjection (обязательно для Android 10+).
 * Запускается из ScreenCaptureRequestActivity после получения разрешения.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureSvc";
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIF_ID = 2;

    public static final String EXTRA_RESULT_CODE   = "result_code";
    public static final String EXTRA_RESULT_DATA   = "result_data";
    public static final String EXTRA_CHAT_ID       = "chat_id";
    public static final String ACTION_STOP         = "com.remotecontrol.STOP_CAPTURE";

    private static ScreenCaptureService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            ScreenCapture.release();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Запускаем как Foreground с типом mediaProjection (обязательно!)
        startForeground(NOTIF_ID, buildNotification());

        int resultCode  = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data     = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        long chatId     = intent.getLongExtra(EXTRA_CHAT_ID, 0);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        MediaProjection projection = mpm.getMediaProjection(resultCode, data);
        ScreenCapture.initProjection(projection, this);

        // Делаем скриншот если запрошен
        TelegramService svc = TelegramService.getInstance();
        if (svc != null && chatId != 0) {
            ScreenCapture.captureWithExistingProjection(this, chatId, svc.getEngine());
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "ScreenCaptureService destroyed");
    }

    public static ScreenCaptureService getInstance() { return instance; }

    // ──────────────────────────────────────────────────
    //  Notification
    // ──────────────────────────────────────────────────

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Служба захвата экрана");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
