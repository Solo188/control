// app/src/main/java/your/package/ScreenCaptureRequestActivity.java

package your.package;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

import java.nio.ByteBuffer;

public class ScreenCaptureRequestActivity extends Activity {

    public static ScreenCaptureSender sender;
    public static int pendingCommandId = -1;
    public static MediaProjection mediaProjection;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (mediaProjection == null) {
                Log.e("ScreenCapture", "MediaProjection is null");
                finish();
                return;
            }

            startCapture();

        } catch (Exception e) {
            Log.e("ScreenCapture", "onCreate error", e);
            finish();
        }
    }

    private void startCapture() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);

            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int density = metrics.densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "screen_capture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            imageReader.setOnImageAvailableListener(reader -> {
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

                    Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);

                    sender.send(cropped, pendingCommandId);

                    bitmap.recycle();

                } catch (Exception e) {
                    Log.e("ScreenCapture", "capture error", e);
                } finally {

                    try {
                        if (image != null) image.close();
                    } catch (Exception e) {
                        Log.e("ScreenCapture", "image close error", e);
                    }

                    try {
                        if (imageReader != null) imageReader.close();
                    } catch (Exception e) {
                        Log.e("ScreenCapture", "reader close error", e);
                    }

                    try {
                        if (virtualDisplay != null) virtualDisplay.release();
                    } catch (Exception e) {
                        Log.e("ScreenCapture", "vd release error", e);
                    }

                    finish();
                }

            }, null);

        } catch (Exception e) {
            Log.e("ScreenCapture", "startCapture error", e);
            finish();
        }
    }
}
