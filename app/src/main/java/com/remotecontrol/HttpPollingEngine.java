package com.remotecontrol;

import android.content.Context;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class HttpPollingEngine implements Runnable {
    private static final String TAG = "PollingEngine";
    private static HttpPollingEngine instance;
    private final OkHttpClient client;
    private final Context context;
    private volatile boolean running = true;

    public HttpPollingEngine(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();
        instance = this;
    }

    public static HttpPollingEngine getInstance() { return instance; }

    @Override
    public void run() {
        while (running) {
            try {
                Request request = new Request.Builder().url(Config.ENDPOINT_GET_COMMAND).get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.code() != 204) {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        String action = json.optString("action");
                        
                        MyAccessibilityService service = MyAccessibilityService.getInstance();
                        if (service != null) {
                            service.executeAction(action);
                            sendAck(json.optInt("id"));
                        } else {
                            Log.e(TAG, "A11y Service not running!");
                        }
                    }
                }
                Thread.sleep(1500);
            } catch (Exception e) {
                Log.e(TAG, "Loop error: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void sendAck(int id) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder().url(Config.BASE_URL + "/ack").post(body).build();
            client.newCall(req).execute().close();
        } catch (Exception ignored) {}
    }

    public void uploadScreenshot(File file) {
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("image/jpeg"));
        Request req = new Request.Builder().url(Config.ENDPOINT_UPLOAD).post(fileBody).build();
        try (Response resp = client.newCall(req).execute()) {
            Log.d(TAG, "Upload status: " + resp.code());
        } catch (IOException e) {
            Log.e(TAG, "Upload failed", e);
        } finally {
            file.delete();
        }
    }
}
