package com.remotecontrol;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ScreenCaptureSender {

    private final String baseUrl;
    private final OkHttpClient client = new OkHttpClient();
    private final HttpPollingEngine engine;

    public ScreenCaptureSender(String baseUrl, HttpPollingEngine engine) {
        this.baseUrl = baseUrl;
        this.engine = engine;
    }

    public void send(Bitmap bitmap, int commandId) {
        new Thread(() -> {
            try {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
                byte[] bytes = stream.toByteArray();

                // upload
                Request upload = new Request.Builder()
                        .url(baseUrl + "/upload")
                        .post(RequestBody.create(bytes, MediaType.parse("image/jpeg")))
                        .build();

                try (Response r = client.newCall(upload).execute()) {
                    Log.d("Sender", "Uploaded");
                }

                // ACK
                JSONObject json = new JSONObject();
                json.put("id", commandId);
                json.put("status", "done");

                Request ack = new Request.Builder()
                        .url(baseUrl + "/ack")
                        .post(RequestBody.create(
                                json.toString(),
                                MediaType.parse("application/json")))
                        .build();

                client.newCall(ack).execute();

            } catch (Exception e) {
                Log.e("Sender", "Error", e);
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                engine.onScreenSent();
            }
        }).start();
    }
}
