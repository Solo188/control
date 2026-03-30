package com.remotecontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Activity для одноразового захвата экрана через MediaProjection.
 *
 * ИСПРАВЛЕНО:
 * 1. Использует Config.BASE_URL вместо "http://YOUR_SERVER_URL"
 * 2. ImageReader listener передаётся HandlerThread (НЕ main thread)
 * 3. Ресурсы корректно освобождаются даже при null image
 * 4. Добавлена защита от повторного вызова listener (флаг captured)
 * 5. Лог для диагностики
 */
public class ScreenCaptureRequestActivity extends Activity {

    private static final String TAG          = "ScreenCapture";
    private static final int    REQUEST_CODE = 1001;

    // static — передаём данные через Intent или static поля
    // (Activity живёт кратко, race condition минимален)
    private static volatile int                pendingCommandId;
    private static volatile HttpPollingEngine  engine;

    private MediaProjectionManager projectionManager;

    // ──────────────────────────────────────────────────────────────

    public static void request(Context ctx, int commandId) {
        pendingCommandId = commandId;
        Intent intent = new Intent(ctx, ScreenCaptureRequestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    public static void setEngine(HttpPollingEngine e) {
        engine = e;
    }

    // ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CODE
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection permission denied or cancelled");
            // 🔴 FIX: уведомляем engine чтобы isBusy не завис
            if (engine != null) engine.onScreenSent();
            finish();
            return;
        }

        MediaProjection projection = projectionManager.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.e(TAG, "getMediaProjection вернул null");
            if (engine != null) engine.onScreenSent();
            finish();
            return;
        }

        startCapture(projection);
    }

    // ──────────────────────────────────────────────────────────────

    private void startCapture(MediaProjection projection) {
        DisplayMetrics metrics = new DisplayMetrics();
        //noinspection deprecation
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int width   = metrics.widthPixels;
        int height  = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2
        );

        VirtualDisplay display = projection.createVirtualDisplay(
                "screen_capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null, null
        );

        // 🔴 FIX: listener запускается в отдельном HandlerThread, НЕ на main thread
        HandlerThread handlerThread = new HandlerThread("ImageReaderThread");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        // Флаг: обрабатываем только первый кадр
        final boolean[] captured = {false};

        reader.setOnImageAvailableListener(r -> {
            if (captured[0]) return;
            captured[0] = true;

            Image image = null;
            Bitmap bitmap = null;

            try {
                image = r.acquireLatestImage();
                if (image == null) {
                    Log.w(TAG, "acquireLatestImage вернул null");
                    return;
                }

                Image.Plane[] planes    = image.getPlanes();
                ByteBuffer    buffer    = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride   = planes[0].getRowStride();
                int rowPadding  = rowStride - pixelStride * width;

                bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                );
                bitmap.copyPixelsFromBuffer(buffer);

                // Отправляем — ScreenCaptureSender сам запустит поток
                sendBitmap(bitmap, pendingCommandId);
                bitmap = null; // владение передано sender, не recycleим здесь

            } catch (Exception e) {
                Log.e(TAG, "Ошибка при захвате экрана", e);
                if (engine != null) engine.onScreenSent(); // сбрасываем isBusy
            } finally {
                if (image != null)  image.close();
                if (bitmap != null) bitmap.recycle(); // recycle только если не передали
                reader.close();
                display.release();
                projection.stop();
                handlerThread.quitSafely();
                finish();
            }

        }, handler);
    }

    // ──────────────────────────────────────────────────────────────

    private void sendBitmap(Bitmap bitmap, int commandId) {
        // 🔴 FIX: используем Config.BASE_URL, а не "http://YOUR_SERVER_URL"
        HttpPollingEngine eng = engine;
        if (eng == null) {
            Log.e(TAG, "engine == null, скриншот не отправлен");
            if (bitmap != null) bitmap.recycle();
            return;
        }

        ScreenCaptureSender sender = new ScreenCaptureSender(Config.BASE_URL, eng);
        sender.send(bitmap, commandId);
    }
}
