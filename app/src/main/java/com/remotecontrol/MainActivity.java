package com.remotecontrol;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

/**
 * MainActivity — первичный экран настройки.
 * Показывает статус разрешений и позволяет их включить.
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);

        Button btnStartService = findViewById(R.id.btnStartService);
        btnStartService.setOnClickListener(v -> startTelegramService());

        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        Button btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimization());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void startTelegramService() {
        Intent intent = new Intent(this, TelegramService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "TelegramService запущен", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void requestIgnoreBatteryOptimization() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void updateStatus() {
        boolean a11y = MyAccessibilityService.isRunning();
        boolean notif = NotificationManagerCompat.from(this).areNotificationsEnabled();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm.isIgnoringBatteryOptimizations(getPackageName());

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 Bot Token: ").append(
                Config.BOT_TOKEN.isEmpty() || Config.BOT_TOKEN.equals("YOUR_BOT_TOKEN")
                        ? "❌ Не задан" : "✅ Задан"
        ).append("\n");
        sb.append("♿ AccessibilityService: ").append(a11y ? "✅ Активен" : "❌ Выключен").append("\n");
        sb.append("🔔 Уведомления: ").append(notif ? "✅" : "❌").append("\n");
        sb.append("🔋 Игнорирование батареи: ").append(battery ? "✅" : "⚠️").append("\n");
        sb.append("📡 MiniApp URL: ").append(Config.MINI_APP_URL);

        tvStatus.setText(sb.toString());
    }
}
