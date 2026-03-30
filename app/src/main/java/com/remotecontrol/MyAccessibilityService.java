package com.remotecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "RemoteA11y";
    private static MyAccessibilityService instance;

    public static MyAccessibilityService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "✅ Service Connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    // --- Выполнение действий ---
    public void executeAction(String action) {
        Log.d(TAG, "Executing: " + action);
        switch (action) {
            case "home": performGlobalAction(GLOBAL_ACTION_HOME); break;
            case "back": performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "recents": performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case "notifications": performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); break;
            case "screenshot": takeAndUpload(); break;
        }
    }

    // --- Скриншот через Accessibility API (Android 11+) ---
    public void takeAndUpload() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.e(TAG, "Screenshot API требует Android 11+");
            return;
        }

        takeScreenshot(0, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshotResult) {
                HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                // Конвертируем Hardware Bitmap в программный для сжатия
                Bitmap softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                
                new Thread(() -> {
                    File file = saveBitmap(softwareBitmap);
                    if (file != null) {
                        HttpPollingEngine.getInstance().uploadScreenshot(file);
                    }
                    softwareBitmap.recycle();
                    buffer.close();
                }).start();
            }

            @Override
            public void onFailure(int errorCode) {
                Log.e(TAG, "Screenshot failed: " + errorCode);
            }
        });
    }

    private File saveBitmap(Bitmap bmp) {
        File f = new File(getCacheDir(), "screen.jpg");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            return f;
        } catch (Exception e) {
            Log.e(TAG, "Save error", e);
            return null;
        }
    }
}
