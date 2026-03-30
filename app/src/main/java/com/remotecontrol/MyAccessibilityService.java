package com.remotecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "MyA11yService";
    private static MyAccessibilityService instance;

    public static MyAccessibilityService getInstance() { return instance; }
    public static boolean isRunning() { return instance != null; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "✅ Специальные возможности подключены");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        Log.w(TAG, "⚠️ Сервис прерван");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    // ── Клик ──────────────────────────────────────────

    public void performClick(float xPercent, float yPercent) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float x = xPercent * m.widthPixels;
        float y = yPercent * m.heightPixels;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();

        dispatchGesture(gesture, null, null);
        Log.d(TAG, "Клик: " + x + "," + y);
    }

    // ── Свайп ─────────────────────────────────────────

    public void performSwipe(float x1p, float y1p, float x2p, float y2p, long duration) {
        DisplayMetrics m = getResources().getDisplayMetrics();

        Path path = new Path();
        path.moveTo(x1p * m.widthPixels,  y1p * m.heightPixels);
        path.lineTo(x2p * m.widthPixels,  y2p * m.heightPixels);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();

        dispatchGesture(gesture, null, null);
        Log.d(TAG, "Свайп выполнен");
    }

    // ── Long Press (удержание на месте 800 мс) ────────

    public void performLongPress(float xPercent, float yPercent) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float x = xPercent * m.widthPixels;
        float y = yPercent * m.heightPixels;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 800))
                .build();

        dispatchGesture(gesture, null, null);
        Log.d(TAG, "LongPress: " + x + "," + y);
    }

    // ── Системные действия ────────────────────────────

    public boolean pressHome()    { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean pressBack()    { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean pressRecents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }

    public boolean openNotificationShade() {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }
}
