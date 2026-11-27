package com.lkwang.cjs701;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;

final class AirClient {
    static final String HOST_KEY = "esp32_ip";

    enum Metric {
        TEMPERATURE("temperature_c", "温度", "°C", 2, false),
        HUMIDITY("humidity_pct", "相对湿度", "%RH", 2, false),
        HCHO("hcho_mg_m3", "甲醛", "mg/m³", 3, true),
        TVOC("tvoc_mg_m3", "TVOC", "mg/m³", 3, true),
        ECO2("eco2_ppm", "等效 CO₂", "ppm", 0, true),
        ECO("eco_ppm", "等效 CO", "ppm", 1, true),
        AQI("aqi", "模组 AQI", "", 0, true);

        final String key;
        final String label;
        final String unit;
        final int decimals;
        final boolean alertable;

        Metric(String key, String label, String unit, int decimals, boolean alertable) {
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.decimals = decimals;
            this.alertable = alertable;
        }

        String format(double value) {
            return String.format(Locale.US, "%." + decimals + "f", value);
        }

        String display(double value) {
            return format(value) + (unit.isEmpty() ? "" : " " + unit);
        }
    }

    static final class Sample {
        final String status;
        final EnumMap<Metric, Double> values = new EnumMap<>(Metric.class);

        Sample(String status) {
            this.status = status;
        }
    }

    private AirClient() {}

    static String normalizeIp(String input) {
        if (!input.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new IllegalArgumentException("请输入局域网 IPv4 地址");
        }
        String[] parts = input.split("\\.");
        int[] ip = new int[4];
        for (int i = 0; i < ip.length; i++) {
            ip[i] = Integer.parseInt(parts[i]);
            if (ip[i] > 255) {
                throw new IllegalArgumentException("IPv4 地址的每一段应在 0 到 255 之间");
            }
        }
        if (!(ip[0] == 10 || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31
                || ip[0] == 192 && ip[1] == 168)) {
            throw new IllegalArgumentException("请输入 ESP32 的局域网地址");
        }
        return ip[0] + "." + ip[1] + "." + ip[2] + "." + ip[3];
    }

    static Sample fetch(String ip) {
        if (ip == null || ip.isEmpty()) return new Sample("unconfigured");
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL("http://" + normalizeIp(ip) + ":8080/api/air");
            connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return new Sample("invalid");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[512];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    text.append(buffer, 0, count);
                    if (text.length() > 4096) return new Sample("invalid");
                }
                return parse(text.toString());
            }
        } catch (IOException e) {
            return new Sample("offline");
        } catch (IllegalArgumentException e) {
            return new Sample("invalid");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static Sample parse(String body) {
        try {
            JSONObject data = new JSONObject(body);
            String status = data.getString("status");
            if ("waiting".equals(status) || "stale".equals(status)) return new Sample(status);
            if (!"live".equals(status)) return new Sample("invalid");
            Sample sample = new Sample(status);
            for (Metric metric : Metric.values()) {
                Object raw = data.opt(metric.key);
                if (!(raw instanceof Number)) return new Sample("invalid");
                double value = ((Number) raw).doubleValue();
                if (!Double.isFinite(value)) return new Sample("invalid");
                sample.values.put(metric, value);
            }
            return sample;
        } catch (org.json.JSONException e) {
            return new Sample("invalid");
        }
    }
}
