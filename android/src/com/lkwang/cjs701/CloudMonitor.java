package com.lkwang.cjs701;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

final class CloudMonitor implements MqttCallback {
    private static final int MAX_PAYLOAD = 128 * 1024;
    private static final int MAX_JSON = 1024 * 1024;
    private final ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor();
    private final String topic;
    private volatile String status = "正在连接公网 MQTT…";
    private volatile Snapshot latest;
    private MqttClient client;

    CloudMonitor(Map<String, ?> settings) {
        String user = AppConfig.value(settings, CloudAir.USER);
        String secret = AppConfig.value(settings, CloudAir.SECRET);
        String uri = AppConfig.value(settings, AppConfig.MQTT_URI);
        String ca = AppConfig.value(settings, AppConfig.MQTT_CA);
        topic = AppConfig.value(settings, AppConfig.MONITOR_TOPIC);
        if (uri.isEmpty() || topic.isEmpty() || (!user.isEmpty() && secret.isEmpty())) {
            status = "请在设置中填写局域网监控地址，或配置 MQTT 监控主题";
            return;
        }
        reader.scheduleWithFixedDelay(() -> connect(uri, user, secret, ca), 0, 10, TimeUnit.SECONDS);
    }

    private void connect(String uri, String user, String secret, String ca) {
        if (client != null && client.isConnected()) return;
        try {
            if (client == null) {
                client = new MqttClient(uri, "md-phone-" + UUID.randomUUID().toString().substring(0, 12),
                        new MemoryPersistence());
                client.setTimeToWait(6000);
                client.setCallback(this);
            }
            client.connect(CloudAir.connectOptions(user, secret, ca));
            status = "已连上 MQTT，等待 MD1000 上报";
            client.subscribe(topic, 1);
        } catch (Exception error) {
            int reason = error instanceof MqttException ? ((MqttException) error).getReasonCode() : 0;
            status = reason == 4 || reason == 5 || reason == 128
                    ? "MQTT 账号或订阅权限被拒绝" : "远程连接失败，正在重试";
            disconnect();
        }
    }

    @Override public void connectionLost(Throwable cause) {
        status = "远程连接断开，正在重试";
    }

    @Override public void messageArrived(String receivedTopic, MqttMessage message) {
        if (!topic.equals(receivedTopic)) return;
        try {
            latest = decode(message.getPayload());
        } catch (IOException | JSONException failure) {
            status = "收到的 MD1000 记录无法读取";
        }
    }

    private static Snapshot decode(byte[] payload) throws JSONException, IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("MQTT payload is too large");
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(payload));
             ByteArrayOutputStream json = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096];
            int count;
            while ((count = input.read(block)) != -1) {
                if (json.size() + count > MAX_JSON) throw new IOException("MQTT data is too large");
                json.write(block, 0, count);
            }
            JSONObject body = new JSONObject(json.toString(StandardCharsets.UTF_8.name()));
            int interval = body.getInt("interval");
            long published = body.getLong("now");
            JSONArray rows = body.getJSONArray("samples");
            if (interval != 30 || rows.length() > 3000 || published > now() + 120) {
                throw new JSONException("Invalid monitor window");
            }
            long previous = published - 86401;
            for (int index = 0; index < rows.length(); index++) {
                long stamp = rows.getJSONObject(index).getLong("ts");
                if (stamp <= previous || stamp > published) throw new JSONException("Invalid sample time");
                previous = stamp;
            }
            return new Snapshot(published, interval, rows, body.optJSONObject("system"));
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    @Override public void deliveryComplete(IMqttDeliveryToken ignored) {}

    void close() {
        reader.execute(this::disconnect);
        reader.shutdown();
    }

    private void disconnect() {
        MqttClient previous = client;
        client = null;
        if (previous == null) return;
        try {
            previous.disconnectForcibly(0, 500);
        } catch (MqttException ignored) {
            // The connection can already be closed.
        }
        try {
            previous.close(true);
        } catch (MqttException ignored) {
            // This connection is no longer in use.
        }
    }

    private static final class Snapshot {
        final long published;
        final int interval;
        final JSONArray rows;
        final JSONObject system;

        Snapshot(long published, int interval, JSONArray rows, JSONObject system) {
            this.published = published;
            this.interval = interval;
            this.rows = rows;
            this.system = system;
        }
    }

    String waitingMessage() {
        return status;
    }

    String history(String range) throws JSONException {
        long duration;
        if ("1h".equals(range)) duration = 3600;
        else if ("6h".equals(range)) duration = 21600;
        else if ("24h".equals(range)) duration = 86400;
        else throw new JSONException("Invalid history range");
        Snapshot current = latest;
        if (current == null) return null;
        long end = Math.max(current.published, now());
        long start = end - duration;
        JSONArray filtered = new JSONArray();
        for (int index = 0; index < current.rows.length(); index++) {
            JSONObject row = current.rows.getJSONObject(index);
            if (row.getLong("ts") >= start) filtered.put(row);
        }
        return new JSONObject().put("now", end).put("start", start).put("interval", current.interval)
                .put("samples", filtered).put("system", current.system).toString();
    }
}
