package com.remotecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * MyAccessibilityService
 *
 * Предоставляет методы для выполнения жестов (клик, свайп) через GestureDescription.
 * Координаты принимаются в процентах (0.0 – 1.0) и пересчитываются в пиксели
 * на основе реального разрешения экрана устройства.
 */
public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "MyA11yService";

    // Singleton — используется из TelegramEngine
    private static MyAccessibilityService instance;

    // ──────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "AccessibilityService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // При необходимости можно обрабатывать события здесь
    }

    @Override
    public void onInterrupt() {
        // НЕ обнуляем instance здесь — это вызывается при временных прерываниях
        Log.w(TAG, "AccessibilityService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null; // Обнуляем только при полном уничтожении
        Log.d(TAG, "AccessibilityService destroyed");
    }

    // ──────────────────────────────────────────────────────────────
    //  Public static API
    // ──────────────────────────────────────────────────────────────

    /** Возвращает активный экземпляр сервиса или null если не запущен. */
    public static MyAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    // ──────────────────────────────────────────────────────────────
    //  Получение размеров экрана
    // ──────────────────────────────────────────────────────────────

    /**
     * Возвращает реальные пиксельные размеры экрана (включая системные полосы).
     * Используем getRealSize / getRealMetrics для максимальной совместимости.
     */
    private Point getScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+
            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
            size.x = metrics.getBounds().width();
            size.y = metrics.getBounds().height();
        } else {
            Display display = wm.getDefaultDisplay();
            display.getRealSize(size);
        }
        return size;
    }

    // ──────────────────────────────────────────────────────────────
    //  Жесты
    // ──────────────────────────────────────────────────────────────

    /**
     * Выполняет одиночный клик по указанным процентным координатам.
     *
     * @param xPct Горизонтальная позиция: 0.0 = левый край, 1.0 = правый край
     * @param yPct Вертикальная позиция:   0.0 = верхний край, 1.0 = нижний край
     * @return true если жест отправлен успешно
     */
    public boolean performClick(float xPct, float yPct) {
        if (instance == null) {
            Log.e(TAG, "performClick: service not connected");
            return false;
        }

        // Проверка диапазона
        xPct = Math.max(0f, Math.min(1f, xPct));
        yPct = Math.max(0f, Math.min(1f, yPct));

        Point screen = getScreenSize();
        float px = xPct * screen.x;
        float py = yPct * screen.y;

        Log.d(TAG, String.format("performClick: pct=(%.3f,%.3f) → px=(%.1f,%.1f) screen=%dx%d",
                xPct, yPct, px, py, screen.x, screen.y));

        Path path = new Path();
        path.moveTo(px, py);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50); // 50ms — короткое нажатие

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "performClick completed");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "performClick cancelled");
            }
        }, null);
    }

    /**
     * Выполняет свайп от точки (x1Pct, y1Pct) до (x2Pct, y2Pct).
     *
     * @param x1Pct Начало X (0.0–1.0)
     * @param y1Pct Начало Y (0.0–1.0)
     * @param x2Pct Конец X  (0.0–1.0)
     * @param y2Pct Конец Y  (0.0–1.0)
     * @return true если жест отправлен успешно
     */
    public boolean performSwipe(float x1Pct, float y1Pct, float x2Pct, float y2Pct) {
        return performSwipe(x1Pct, y1Pct, x2Pct, y2Pct, 300);
    }

    /**
     * Выполняет свайп с задаваемой длительностью (в миллисекундах).
     */
    public boolean performSwipe(float x1Pct, float y1Pct,
                                float x2Pct, float y2Pct,
                                long durationMs) {
        if (instance == null) {
            Log.e(TAG, "performSwipe: service not connected");
            return false;
        }

        // Зажимаем в диапазон [0,1]
        x1Pct = Math.max(0f, Math.min(1f, x1Pct));
        y1Pct = Math.max(0f, Math.min(1f, y1Pct));
        x2Pct = Math.max(0f, Math.min(1f, x2Pct));
        y2Pct = Math.max(0f, Math.min(1f, y2Pct));

        Point screen = getScreenSize();
        float px1 = x1Pct * screen.x;
        float py1 = y1Pct * screen.y;
        float px2 = x2Pct * screen.x;
        float py2 = y2Pct * screen.y;

        Log.d(TAG, String.format(
                "performSwipe: (%.3f,%.3f)→(%.3f,%.3f) px=(%.1f,%.1f)→(%.1f,%.1f) dur=%dms",
                x1Pct, y1Pct, x2Pct, y2Pct, px1, py1, px2, py2, durationMs));

        Path path = new Path();
        path.moveTo(px1, py1);
        path.lineTo(px2, py2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "performSwipe completed");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "performSwipe cancelled");
            }
        }, null);
    }

    /**
     * Нажатие системной кнопки HOME.
     */
    public boolean pressHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /**
     * Нажатие кнопки BACK.
     */
    public boolean pressBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * Открыть список последних приложений.
     */
    public boolean pressRecents() {
        return performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    /**
     * Нажатие и удержание (long press) по процентным координатам.
     */
    public boolean performLongPress(float xPct, float yPct) {
        return performSwipe(xPct, yPct, xPct, yPct, 800); // 800ms на одном месте = long press
    }

    /**
     * Открыть шторку уведомлений.
     */
    public boolean openNotificationShade() {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    /**
     * Получить текст из фокусированного поля ввода.
     */
    public String getFocusedNodeText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) return null;
        CharSequence text = focused.getText();
        return text != null ? text.toString() : "";
    }
}
