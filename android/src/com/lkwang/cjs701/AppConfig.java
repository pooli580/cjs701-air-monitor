package com.lkwang.cjs701;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Map;

final class AppConfig {
    static final String MQTT_URI = "mqtt_uri";
    static final String AIR_TOPIC = "mqtt_air_topic";
    static final String MONITOR_TOPIC = "mqtt_monitor_topic";
    static final String MONITOR_LAN = "monitor_lan_url";
    static final String MQTT_CA = "mqtt_ca_pem";
    static final String UPDATE_FEED = "https://github.com/pooli580/cjs701-air-monitor/releases/latest/download/latest.json";

    private AppConfig() {}

    static String value(Map<String, ?> settings, String key) {
        Object value = settings.get(key);
        return value instanceof String ? (String) value : "";
    }

    static String mqttUri(String value) {
        if (value.isEmpty()) return "";
        URI address = parse(value);
        if (!"ssl".equals(address.getScheme()) || !address.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("请输入 ssl://主机:端口");
        }
        return address.toASCIIString();
    }

    static String lanUrl(String value) {
        if (value.isEmpty()) return "";
        URI address = parse(value);
        if ("http".equals(address.getScheme())) AirClient.normalizeIp(address.getHost());
        else if (!"https".equals(address.getScheme())) throw new IllegalArgumentException("请输入 HTTP 或 HTTPS 地址");
        if (!address.getRawPath().isEmpty() && !"/".equals(address.getRawPath())) {
            throw new IllegalArgumentException("请输入监控台根地址");
        }
        return address.getScheme() + "://" + address.getRawAuthority() + "/";
    }

    private static URI parse(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getPort() != 0 && uri.getPort() <= 65535) return uri;
        } catch (URISyntaxException ignored) {
            // Report a short input hint to the user.
        }
        throw new IllegalArgumentException("地址格式有误");
    }

    static String topic(String value) {
        if (value.length() > 255 || value.indexOf('#') >= 0 || value.indexOf('+') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("请输入完整主题，主题中不要使用通配符");
        }
        return value;
    }

    static Collection<? extends Certificate> certificates(byte[] data) throws CertificateException {
        Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(data));
        if (certificates.isEmpty()) throw new CertificateException("No X.509 certificate");
        for (Certificate item : certificates) {
            if (!(item instanceof X509Certificate) || ((X509Certificate) item).getBasicConstraints() < 0) {
                throw new CertificateException("Select a CA certificate");
            }
        }
        return certificates;
    }

    static String pem(byte[] data) throws CertificateException {
        StringBuilder result = new StringBuilder();
        for (Certificate item : certificates(data)) {
            result.append("-----BEGIN CERTIFICATE-----\n")
                    .append(Base64.encodeToString(item.getEncoded(), Base64.NO_WRAP))
                    .append("\n-----END CERTIFICATE-----\n");
        }
        return result.toString();
    }
}
