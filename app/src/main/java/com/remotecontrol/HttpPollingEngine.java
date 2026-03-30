// app/src/main/java/your/package/HttpPollingEngine.java

package com.remotecontrol;

import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpPollingEngine {

    public interface CommandExecutor {
        void execute(JSONObject command) throws Exception;
    }

    public interface ScreenRequester {
        void request(int commandId) throws Exception;
    }

    public interface AckSender {
        void send(int commandId);
    }

    private final String baseUrl;
    private final CommandExecutor commandExecutor;
    private final ScreenRequester screenRequester;
    private final AckSender ackSender;

    private final OkHttpClient client = new OkHttpClient();
    private final AtomicBoolean isBusy = new AtomicBoolean(false);

    private int lastCommandId = -1;

    public HttpPollingEngine(String baseUrl,
                             CommandExecutor commandExecutor,
                             ScreenRequester screenRequester,
                             AckSender ackSender) {
        this.baseUrl = baseUrl;
        this.commandExecutor = commandExecutor;
        this.screenRequester = screenRequester;
        this.ackSender = ackSender;
    }

    public void start() {
        new Thread(() -> {
            while (true) {
                try {

                    if (isBusy.get()) {
                        Thread.sleep(200);
                        continue;
                    }

                    Request request = new Request.Builder()
                            .url(baseUrl + "/get_command")
                            .get()
                            .build();

                    try (Response response = client.newCall(request).execute()) {

                        if (!response.isSuccessful()) continue;

                        String body = response.body() != null ? response.body().string() : null;
                        if (body == null || body.isEmpty()) continue;

                        JSONObject json = new JSONObject(body);
                        int id = json.optInt("id", -1);

                        if (id == -1 || id == lastCommandId) continue;

                        isBusy.set(true);
                        lastCommandId = id;

                        try {
                            commandExecutor.execute(json);
                        } catch (Exception e) {
                            Log.e("HttpPolling", "command error", e);
                        }

                        try {
                            screenRequester.request(id);
                        } catch (Exception e) {
                            Log.e("HttpPolling", "screen error", e);

                            try {
                                ackSender.send(id);
                            } catch (Exception ex) {
                                Log.e("HttpPolling", "ack fallback error", ex);
                            }

                            isBusy.set(false);
                        }
                    }

                    Thread.sleep(300);

                } catch (Exception e) {
                    Log.e("HttpPolling", "loop error", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    public void onScreenSent() {
        isBusy.set(false);
    }
}
