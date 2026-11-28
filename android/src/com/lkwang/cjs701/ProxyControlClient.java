package com.lkwang.cjs701;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

final class ProxyControlClient {
    final String host;
    final int port;
    private final String token;
    private final SSLContext tls;

    static ProxyControlClient parse(String value) throws Exception {
        if (value.length() > 512) throw new IllegalArgumentException("配对码格式有误");
        JSONObject info = new JSONObject(value);
        return new ProxyControlClient(info.getString("host"), info.getInt("port"),
                info.getString("token"), info.getString("sha256"));
    }

    private ProxyControlClient(String host, int port, String token, String digest) throws GeneralSecurityException {
        if (!isLocalIp(host) || port < 1024 || port > 65535 || !token.matches("[A-Za-z0-9_-]{43}")
                || !digest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("需要有效的局域网配对码");
        }
        this.host = host;
        this.port = port;
        this.token = token;
        byte[] pin = new byte[32];
        for (int index = 0; index < pin.length; index++) {
            pin[index] = (byte) Integer.parseInt(digest.substring(index * 2, index * 2 + 2), 16);
        }
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

            @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                throw new CertificateException("No client certificate expected");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing device certificate");
                X509Certificate certificate = chain[0];
                certificate.checkValidity();
                try {
                    if (MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()))) return;
                } catch (GeneralSecurityException error) {
                    throw new CertificateException(error);
                }
                throw new CertificateException("Device certificate does not match the pairing code");
            }
        };
        tls = SSLContext.getInstance("TLS");
        tls.init(null, new X509TrustManager[]{trust}, null);
    }

    private static boolean isLocalIp(String host) {
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4 || Arrays.stream(segments).anyMatch(part -> !part.matches("0|[1-9][0-9]{0,2}"))) return false;
        int[] address = Arrays.stream(segments).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(address).anyMatch(part -> part > 255)) return false;
        return address[0] == 10 || address[0] == 172 && address[1] >= 16 && address[1] <= 31
                || address[0] == 192 && address[1] == 168;
    }

    JSONObject request(String path, JSONObject body) throws Exception {
        URL endpoint = new URL("https", host, port, path);
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
        try {
            connection.setSSLSocketFactory(tls.getSocketFactory());
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(12500);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                InputStream error = connection.getErrorStream();
                String message = error == null ? "控制请求失败（" + status + "）"
                        : new JSONObject(read(error)).optString("error", "控制请求失败");
                throw new ControlException(message);
            }
            return new JSONObject(read(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (output.size() + size > 262144) throw new IOException("Response too large");
                output.write(buffer, 0, size);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class ControlException extends IOException {
        ControlException(String message) { super(message); }
    }
}
