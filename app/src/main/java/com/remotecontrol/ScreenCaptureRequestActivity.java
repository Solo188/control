// app/src/main/java/your/package/ScreenCaptureRequestActivity.java
package com.remotecontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

public class ScreenCaptureRequestActivity extends Activity {

    private static int pendingCommandId;
    private static HttpPollingEngine engine;

    public static void request(Context ctx, int commandId) {
        pendingCommandId = commandId;
        Intent i = new Intent(ctx, ScreenCaptureRequestActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public static void setEngine(HttpPollingEngine e) {
        engine = e;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ⚠️ ТУТ должен быть реальный захват экрана
        // пока заглушка:

        Bitmap fakeBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);

        ScreenCaptureSender sender = new ScreenCaptureSender(
                "http://YOUR_SERVER_URL",
                engine
        );

        sender.send(fakeBitmap, pendingCommandId);

        finish();
    }
}
