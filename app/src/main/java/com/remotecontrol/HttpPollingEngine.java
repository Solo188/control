package com.remotecontrol;

import android.util.Log;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpPollingEngine {

    public interface CommandExecutor {
        void execute(Command command);
    }

    public interface ScreenRequester {
        void request(int commandId);
    }

    public interface AckSender {
        void sendAck(int commandId);
    }

    public static class Command {
        public int id;
        public String action;
        public double x;
        public double y;
    }

    private final String baseUrl;
    private final CommandExecutor executor;
    private final ScreenRequester requester;
    private final AckSender ackSender;

    private final OkHttpClient client = new OkHttpClient();

    private volatile boolean running = true;
    private volatile boolean isBusy = false;

    private int lastCommandId = -1;

    public HttpPollingEngine(String baseUrl,
                             CommandExecutor executor,
                             ScreenRequester requester,
                             AckSender ackSender) {
        this.baseUrl = baseUrl;
        this.executor = executor;
        this.requester = requester;
        this.ackSender = ackSender;
    }

    public void start() {
        new Thread(() -> {
            while (running) {
                poll();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }
        }, "HttpPollingThread").start();
    }

    public void stop() {
        running = false;
    }

    private void poll() {
        if (isBusy) return;

        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/command")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return;

                String body = response.body().string();
                if (body == null || body.isEmpty()) return;

                JSONObject json = new JSONObject(body);

                Command command = new Command();
                command.id = json.getInt("id");
                command.action = json.getString("a");
                command.x = json.optDouble("x", 0);
                command.y = json.optDouble("y", 0);

                if (command.id == lastCommandId) return;

                lastCommandId = command.id;
                isBusy = true;

                executor.execute(command);
                requester.request(command.id);

            }

        } catch (Exception e) {
            Log.e("Polling", "Error", e);
        }
    }

    public void onScreenSent() {
        isBusy = false;
    }
}
