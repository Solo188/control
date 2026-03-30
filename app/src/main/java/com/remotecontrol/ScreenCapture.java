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
 * ScreenCapture — захват экрана и отправка на сервер.
 *
 * Поток работы:
 *   ScreenCaptureRequestActivity (получает разрешение)
 *     → ScreenCaptureService (инициализирует MediaProjection)
 *       → ScreenCapture.captureAndUpload()
 *         → HttpPollingEngine.uploadScreenshot()
 */
public class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    private static MediaProjection activeProjection;
    private static VirtualDisplay   virtualDisplay;
    private static ImageReader      imageReader;

    // ──────────────────────────────────────────────────

    public static void initProjection(MediaProjection projection, Context context) {
        activeProjection = projection;
        if (projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { release(); }
            }, null);
        }
        Log.i(TAG, "Projection initialized");
    }

    public static void release() {
        releaseDisplayResources();
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
            activeProjection = null;
        }
    }

    // ──────────────────────────────────────────────────
    //  Capture + upload
    // ──────────────────────────────────────────────────

    public static void captureAndUpload(Context context, HttpPollingEngine engine) {
        if (activeProjection == null) {
            Log.e(TAG, "captureAndUpload: no projection");
            return;
        }

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics wm2 = wm.getCurrentWindowMetrics();
            dm.widthPixels  = wm2.getBounds().width();
            dm.heightPixels = wm2.getBounds().height();
            dm.densityDpi   = context.getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(dm);
        }

        releaseDisplayResources();

        imageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels,
                PixelFormat.RGBA_8888, 2);

        virtualDisplay = activeProjection.createVirtualDisplay(
                "RC", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            reader.setOnImageAvailableListener(null, null);
            Image image = null;
            Bitmap bmp  = null;
            try {
                Thread.sleep(250); // стабилизация кадра
                image = reader.acquireLatestImage();
                if (image == null) return;
                bmp = imageToBitmap(image, dm.widthPixels, dm.heightPixels);
                File file = bitmapToJpeg(context, bmp, 75);
                if (file != null && engine != null) engine.uploadScreenshot(file);
            } catch (Exception e) {
                Log.e(TAG, "Capture error: " + e.getMessage());
            } finally {
                if (image != null) image.close();
                if (bmp   != null) bmp.recycle();
                releaseDisplayResources();
            }
        }, null);
    }

    // ──────────────────────────────────────────────────

    private static Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes  = image.getPlanes();
        ByteBuffer    buffer  = planes[0].getBuffer();
        int pixelStride       = planes[0].getPixelStride();
        int rowStride         = planes[0].getRowStride();
        int rowPadding        = rowStride - pixelStride * w;

        Bitmap bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);

        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
            bmp.recycle();
            return cropped;
        }
        return bmp;
    }

    private static File bitmapToJpeg(Context ctx, Bitmap bmp, int quality) {
        File f = new File(ctx.getCacheDir(), "sc_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            return f;
        } catch (IOException e) {
            Log.e(TAG, "JPEG error: " + e.getMessage());
            return null;
        }
    }

    private static void releaseDisplayResources() {
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (imageReader    != null) { try { imageReader.close();      } catch (Exception ignored) {} imageReader    = null; }
    }
}
