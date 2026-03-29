package com.remotecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityService extends AccessibilityService {
    
    private static final String TAG = "MyAccessibilityService";
    private static MyAccessibilityService instance;

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    // Тот самый недостающий метод, который ждет MainActivity
    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility Service Connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Нам не нужно обрабатывать события системы, только отправлять жесты
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public void performClick(float xPercent, float yPercent) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int x = (int) (xPercent * metrics.widthPixels);
        int y = (int) (yPercent * metrics.heightPixels);
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        
        boolean result = dispatchGesture(gesture, null, null);
        Log.d(TAG, "Click performed at " + x + "," + y + " Success: " + result);
    }

    public void performSwipe(float x1p, float y1p, float x2p, float y2p, long duration) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int x1 = (int) (x1p * metrics.widthPixels);
        int y1 = (int) (y1p * metrics.heightPixels);
        int x2 = (int) (x2p * metrics.widthPixels);
        int y2 = (int) (y2p * metrics.heightPixels);
        
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        
        dispatchGesture(gesture, null, null);
    }

    public void performLongPress(float xPercent, float yPercent) {
        performSwipe(xPercent, yPercent, xPercent, yPercent, 1000);
    }

    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void pressRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }
}
