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

/**
 * ScreenCaptureService
 *
 * Foreground Service с типом mediaProjection.
 * Получает разрешение от ScreenCaptureRequestActivity,
 * инициализирует MediaProjection и запускает захват экрана.
 * Результат уходит через HttpPollingEngine.uploadScreenshot().
 */
public class ScreenCaptureService extends Service {

    private static final String TAG      = "ScreenCaptureSvc";
    private static final String CHAN_ID  = "screen_capture";
    private static final int    NOTIF_ID = 2;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String ACTION_STOP       = "com.remotecontrol.STOP_CAPTURE";

    private MediaProjection mediaProjection;

    // ──────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
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

        // Обязательно: запустить foreground ДО любых других операций
        startForeground(NOTIF_ID, buildNotification());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data    = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid MediaProjection result");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        mediaProjection = mpm.getMediaProjection(resultCode, data);
        ScreenCapture.initProjection(mediaProjection, this);

        // Получаем HttpPollingEngine из TelegramService
        TelegramService svc = TelegramService.getInstance();
        HttpPollingEngine engine = (svc != null) ? svc.getEngine() : null;

        if (engine == null) {
            Log.e(TAG, "HttpPollingEngine not available — TelegramService not running?");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Запускаем захват в фоновом потоке
        final HttpPollingEngine finalEngine = engine;
        new Thread(() -> {
            ScreenCapture.captureAndUpload(ScreenCaptureService.this, finalEngine);
            // После захвата сервис больше не нужен
            stopSelf();
        }, "CaptureThread").start();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        ScreenCapture.release();
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        super.onDestroy();
        Log.d(TAG, "ScreenCaptureService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ──────────────────────────────────────────────────
    //  Notification
    // ──────────────────────────────────────────────────

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHAN_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Захват экрана...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHAN_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Служба захвата экрана");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
