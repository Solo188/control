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
    private final HttpPollingEngine pollingEngine;

    private final OkHttpClient client = new OkHttpClient();

    public ScreenCaptureSender(String baseUrl, HttpPollingEngine pollingEngine) {
        this.baseUrl = baseUrl;
        this.pollingEngine = pollingEngine;
    }

    public void send(Bitmap bitmap, int commandId) {

        new Thread(() -> {

            try {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();

                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
                byte[] bytes = stream.toByteArray();
                stream.close();

                // ───── upload ─────
                try {
                    Request uploadRequest = new Request.Builder()
                            .url(baseUrl + "/upload")
                            .post(RequestBody.create(bytes, MediaType.parse("image/jpeg")))
                            .build();

                    try (Response response = client.newCall(uploadRequest).execute()) {
                        if (!response.isSuccessful()) {
                            Log.e("ScreenSender", "upload failed");
                        }
                    }

                } catch (Exception e) {
                    Log.e("ScreenSender", "upload error", e);
                }

                // ───── ACK ВСЕГДА ─────
                try {
                    JSONObject ackJson = new JSONObject();
                    ackJson.put("id", commandId);

                    Request ackRequest = new Request.Builder()
                            .url(baseUrl + "/ack")
                            .post(RequestBody.create(
                                    ackJson.toString(),
                                    MediaType.parse("application/json")
                            ))
                            .build();

                    try (Response response = client.newCall(ackRequest).execute()) {
                        Log.d("ScreenSender", "ACK sent: " + commandId);
                    }

                } catch (Exception e) {
                    Log.e("ScreenSender", "ack error", e);
                }

            } catch (Exception e) {
                Log.e("ScreenSender", "fatal error", e);
            } finally {

                try {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                } catch (Exception e) {
                    Log.e("ScreenSender", "recycle error", e);
                }

                pollingEngine.onScreenSent();
            }

        }).start();
    }
}
