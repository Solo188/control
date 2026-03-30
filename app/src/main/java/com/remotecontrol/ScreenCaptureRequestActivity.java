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
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

public class ScreenCaptureRequestActivity extends Activity {

    private static final int REQUEST_CODE = 1001;

    private static int pendingCommandId;
    private static HttpPollingEngine engine;

    private MediaProjectionManager projectionManager;

    public static void request(Context ctx, int commandId) {
        pendingCommandId = commandId;

        Intent intent = new Intent(ctx, ScreenCaptureRequestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    public static void setEngine(HttpPollingEngine e) {
        engine = e;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CODE
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK) {
            finish();
            return;
        }

        MediaProjection projection = projectionManager.getMediaProjection(resultCode, data);

        startCapture(projection);
    }

    private void startCapture(MediaProjection projection) {

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        VirtualDisplay display = projection.createVirtualDisplay(
                "screen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                null
        );

        reader.setOnImageAvailableListener(r -> {
            Image image = null;

            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();

                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                Bitmap bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                );

                bitmap.copyPixelsFromBuffer(buffer);

                send(bitmap);

            } catch (Exception ignored) {

            } finally {
                if (image != null) image.close();

                reader.close();
                display.release();
                projection.stop();

                finish();
            }

        }, null);
    }

    private void send(Bitmap bitmap) {
        ScreenCaptureSender sender = new ScreenCaptureSender(
                "http://YOUR_SERVER_URL",
                engine
        );

        sender.send(bitmap, pendingCommandId);
    }
}
