package com.remotecontrol;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureSvc";
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIF_ID = 2;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_CHAT_ID = "chat_id";

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        startForeground(NOTIF_ID, buildNotification());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        long chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0);

        if (resultCode == Activity.RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            
            // Инициализируем ScreenCapture
            ScreenCapture.initProjection(mediaProjection, this);

            // Вызываем захват
            TelegramService svc = TelegramService.getInstance();
            if (svc != null && chatId != 0) {
                ScreenCapture.captureWithExistingProjection(this, chatId, svc.getEngine());
            }
        } else {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Очищаем ресурсы в статическом классе
        ScreenCapture.release();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Захват экрана...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
