package com.remotecontrol;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

public class ScreenCaptureRequestActivity extends Activity {

    private static final String TAG = "SCRequestActivity";
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private long chatId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chatId = getIntent().getLongExtra("chat_id", 0);
        Log.d(TAG, "🔍 Создание Activity запроса для ChatID: " + chatId);

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } else {
            Log.e(TAG, "❌ MediaProjectionManager недоступен");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.i(TAG, "✅ Разрешение на запись экрана получено!");
                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                serviceIntent.putExtra(ScreenCaptureService.EXTRA_CHAT_ID, chatId);
                startForegroundService(serviceIntent);
            } else {
                Log.w(TAG, "🚫 Пользователь отклонил запрос на запись экрана");
            }
        }
        finish();
    }
}
