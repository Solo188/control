package com.remotecontrol;

import android.content.Context;
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

    public static void initProjection(MediaProjection projection) {
        activeProjection = projection;
    }

    public static void captureAndUpload(Context context, HttpPollingEngine engine) {
        if (activeProjection == null) return;

        releaseDisplayResources(); // Чистим старое

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        virtualDisplay = activeProjection.createVirtualDisplay(TAG, w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                reader.setOnImageAvailableListener(null, null); // Стоп после первого кадра
                Bitmap bmp = imageToBitmap(image, w, h);
                image.close();
                
                File file = bitmapToJpeg(context, bmp, 60); // 60% качество для скорости
                if (file != null) {
                    new Thread(() -> engine.uploadScreenshot(file)).start();
                }
                bmp.recycle();
                releaseDisplayResources();
            }
        }, null);
    }

    private static Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        Bitmap bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bmp, 0, 0, w, h);
    }

    private static File bitmapToJpeg(Context ctx, Bitmap bmp, int quality) {
        File f = new File(ctx.getCacheDir(), "sc.jpg");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            return f;
        } catch (IOException e) { return null; }
    }

    public static void releaseDisplayResources() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        virtualDisplay = null;
        imageReader = null;
    }
}
