package com.lkwang.cjs701;

import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class CloudAir {
    static final String USER = "mqtt_user";
    static final String SECRET = "mqtt_secret";
    static final String ENABLED = "mqtt_enabled";
    private static final String KEY_ALIAS = "cjs701-mqtt-password";
    private static final Executor CLOSE_IO = Executors.newSingleThreadExecutor();
    private static Connection connection;
    private static int readers;

    private CloudAir() {}

    static synchronized void acquire() {
        readers++;
    }

    static synchronized void release() {
        if (readers > 0 && --readers == 0) closeConnection();
    }

    static synchronized void settingsChanged() {
        closeConnection();
    }

    private static void closeConnection() {
        Connection previous = connection;
        connection = null;
        if (previous != null) CLOSE_IO.execute(previous::close);
    }

    static boolean fallbackEnabled(Map<String, ?> settings) {
        return Boolean.TRUE.equals(settings.get(ENABLED));
    }

    static synchronized AirClient.Sample read(Map<String, ?> settings) {
        if (readers == 0) return new AirClient.Sample("offline");
        String username = AppConfig.value(settings, USER);
        String secret = AppConfig.value(settings, SECRET);
        String uri = AppConfig.value(settings, AppConfig.MQTT_URI);
        String topic = AppConfig.value(settings, AppConfig.AIR_TOPIC);
        String ca = AppConfig.value(settings, AppConfig.MQTT_CA);
        if (uri.isEmpty() || topic.isEmpty() || (!username.isEmpty() && secret.isEmpty())) {
            return new AirClient.Sample("mqtt_settings");
        }
        try {
            if (connection == null) {
                connection = new Connection(uri, topic, username, secret, ca);
            }
            return connection.read();
        } catch (Exception e) {
            return new AirClient.Sample("mqtt_error");
        }
    }

    static String encrypt(String password) throws GeneralSecurityException, java.io.IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static char[] decrypt(String secret) throws GeneralSecurityException, java.io.IOException {
        String[] fields = secret.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(),
                new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
        byte[] plain = cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP));
        try {
            return new String(plain, StandardCharsets.UTF_8).toCharArray();
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    private static synchronized SecretKey deviceKey() throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static MqttConnectOptions connectOptions(String username, String secret, String ca) throws Exception {
        KeyStore caStore = null;
        if (!ca.isEmpty()) {
            caStore = KeyStore.getInstance(KeyStore.getDefaultType());
            caStore.load(null, null);
            int index = 0;
            for (Certificate certificate : AppConfig.certificates(ca.getBytes(StandardCharsets.US_ASCII))) {
                caStore.setCertificateEntry("mqtt-ca-" + index++, certificate);
            }
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(caStore);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setSocketFactory(tls.getSocketFactory());
        options.setHttpsHostnameVerificationEnabled(true);
        if (!username.isEmpty()) {
            options.setUserName(username);
            char[] password = decrypt(secret);
            options.setPassword(password);
            java.util.Arrays.fill(password, '\0');
        }
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(30);
        return options;
    }

    private static final class Delivery {
        final AirClient.Sample sample;
        final long sampledAt;

        Delivery(AirClient.Sample sample, long sampledAt) {
            this.sample = sample;
            this.sampledAt = sampledAt;
        }
    }

    private static final class Connection implements MqttCallback {
        private final MqttAsyncClient client;
        private final MqttConnectOptions options;
        private final String topic;
        private volatile Delivery delivery;
        private volatile String status = "waiting";
        private volatile boolean connecting;
        private volatile long retryAt;

        Connection(String uri, String topic, String username, String secret, String ca) throws Exception {
            this.topic = topic;
            options = connectOptions(username, secret, ca);
            client = new MqttAsyncClient(uri, "air-phone-" + UUID.randomUUID().toString().substring(0, 12),
                    new MemoryPersistence());
            client.setCallback(this);
        }

        AirClient.Sample read() {
            if (!client.isConnected() && !connecting && SystemClock.elapsedRealtime() >= retryAt) {
                connecting = true;
                try {
                    client.connect(options, null, new IMqttActionListener() {
                        @Override public void onSuccess(IMqttToken token) {
                            connecting = false;
                            status = "waiting";
                            try {
                                client.subscribe(topic, 1, null, new IMqttActionListener() {
                                    @Override public void onSuccess(IMqttToken subscription) {
                                        int[] grants = subscription.getGrantedQos();
                                        if (grants.length == 0 || grants[0] == 128) status = "mqtt_denied";
                                    }
                                    @Override public void onFailure(IMqttToken subscription, Throwable error) {
                                        status = "mqtt_denied";
                                    }
                                });
                            } catch (MqttException e) {
                                status = "mqtt_error";
                            }
                        }
                        @Override public void onFailure(IMqttToken token, Throwable error) {
                            retry(error);
                        }
                    });
                } catch (MqttException e) {
                    retry(e);
                }
            }
            Delivery current = delivery;
            if (current == null) return new AirClient.Sample(status);
            if ("live".equals(current.sample.status)
                    && Math.abs(System.currentTimeMillis() / 1000 - current.sampledAt) > 60) {
                return new AirClient.Sample("stale");
            }
            return current.sample;
        }

        private void retry(Throwable error) {
            int code = error instanceof MqttException ? ((MqttException) error).getReasonCode() : 0;
            status = code == 4 || code == 5 ? "mqtt_denied" : "offline";
            delivery = null;
            retryAt = SystemClock.elapsedRealtime() + 10000;
            connecting = false;
        }

        @Override public void connectionLost(Throwable cause) {
            retry(cause);
        }

        @Override public void messageArrived(String receivedTopic, MqttMessage message) {
            if (!topic.equals(receivedTopic) || message.getPayload().length > 4096) return;
            try {
                String body = new String(message.getPayload(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(body);
                AirClient.Sample parsed = "offline".equals(json.optString("status"))
                        ? new AirClient.Sample("offline") : AirClient.parse(body);
                delivery = new Delivery(parsed, json.optLong("sampled_at", 0));
            } catch (org.json.JSONException e) {
                delivery = new Delivery(new AirClient.Sample("invalid"), 0);
            }
        }

        @Override public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {}

        void close() {
            try {
                client.disconnectForcibly(0, 500);
            } catch (MqttException ignored) {
                // The connection can already be closed.
            }
            try {
                client.close(true);
            } catch (MqttException ignored) {
                // No open connection remains to reuse.
            }
        }
    }
}
