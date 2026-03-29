package com.remotecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
    public void onInterrupt() { Log.w(TAG, "⚠️ Сервис прерван"); }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public TelegramEngine getEngine() {
        TelegramService svc = TelegramService.getInstance();
        return (svc != null) ? svc.getEngine() : null;
    }

    public void performClick(float xPercent, float yPercent) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int x = (int) (xPercent * metrics.widthPixels);
        int y = (int) (yPercent * metrics.heightPixels);
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        
        dispatchGesture(gesture, null, null);
        Log.d(TAG, "🖱 Клик: " + x + "," + y);
    }

    public boolean pressHome() { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean pressBack() { return performGlobalAction(GLOBAL_ACTION_BACK); }
}
