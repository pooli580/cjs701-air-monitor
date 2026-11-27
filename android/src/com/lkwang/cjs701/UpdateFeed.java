package com.lkwang.cjs701;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

final class UpdateFeed {
    private static final int MAX_FEED_BYTES = 32768;
    private static final int MAX_APK_BYTES = 64 * 1024 * 1024;
    final long code;
    final String version;
    final URL apk;
    final String sha256;
    final String notes;

    static final class DownloadFailure extends IOException {
        DownloadFailure(String message) { super(message); }
    }

    private UpdateFeed(long code, String version, URL apk, String sha256, String notes) {
        this.code = code;
        this.version = version;
        this.apk = apk;
        this.sha256 = sha256;
        this.notes = notes;
    }

    static URL httpsUrl(String address) throws IOException {
        if (address.isEmpty()) throw new IOException("请填写更新源地址");
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                || url.getUserInfo() != null || url.getRef() != null) {
            throw new IOException("更新地址必须是有效的 HTTPS 链接");
        }
        return url;
    }

    private static HttpsURLConnection open(URL url) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            boolean keep = false;
            try {
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    keep = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (code >= 300 && code <= 399 && location != null) {
                    url = httpsUrl(new URL(url, location).toString());
                } else {
                    throw new IOException("更新服务器返回 HTTP " + code);
                }
            } finally {
                if (!keep) connection.disconnect();
            }
        }
        throw new IOException("更新地址重定向过多");
    }

    static UpdateFeed fetch(URL url) throws IOException, JSONException {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        HttpsURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream text = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while ((length = input.read(buffer)) != -1) {
                checkDeadline(deadline);
                if (text.size() + length > MAX_FEED_BYTES) throw new IOException("更新清单过大");
                text.write(buffer, 0, length);
            }
            JSONObject data = new JSONObject(text.toString(StandardCharsets.UTF_8.name()));
            Object raw = data.get("versionCode");
            if (!(raw instanceof Integer || raw instanceof Long)) throw new JSONException("versionCode");
            long code = ((Number) raw).longValue();
            String version = data.getString("versionName").trim();
            String sha256 = data.getString("sha256").toLowerCase(Locale.ROOT);
            String notes = data.optString("notes", "").trim();
            String downloadUrl = data.getString("apkUrl").trim();
            if (code <= 0 || version.isEmpty() || version.length() > 32 || !sha256.matches("[a-f0-9]{64}")
                    || notes.length() > 2400 || downloadUrl.isEmpty()) throw new JSONException("Invalid release fields");
            URL apk = httpsUrl(new URL(url, downloadUrl).toString());
            return new UpdateFeed(code, version, apk, sha256, notes);
        } finally {
            connection.disconnect();
        }
    }

    File download(Context context) throws Exception {
        File pending = new File(context.getCacheDir(), "online-update-pending.apk");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        long deadline = SystemClock.elapsedRealtime() + 120000;
        HttpsURLConnection connection = open(apk);
        try (InputStream input = connection.getInputStream(); FileOutputStream file = new FileOutputStream(pending)) {
            byte[] buffer = new byte[16384];
            int length;
            while ((length = input.read(buffer)) != -1) {
                checkDeadline(deadline);
                total += length;
                if (total > MAX_APK_BYTES) throw new DownloadFailure("安装包超过大小上限");
                digest.update(buffer, 0, length);
                file.write(buffer, 0, length);
            }
        } finally {
            connection.disconnect();
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(Character.forDigit((value >> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        if (!sha256.contentEquals(hex)) throw new DownloadFailure("安装包校验失败");
        verifyPackage(context, pending);
        File verified = UpdateFileProvider.file(context);
        Files.move(pending.toPath(), verified.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        return verified;
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline) {
            throw new DownloadFailure("下载超时或已取消");
        }
    }

    @SuppressWarnings("deprecation")
    private void verifyPackage(Context context, File file) throws IOException, PackageManager.NameNotFoundException {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo downloaded = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (downloaded == null || !installed.packageName.equals(downloaded.packageName)
                || versionCode(downloaded) != code || code <= versionCode(installed)) {
            throw new DownloadFailure("安装包的应用或版本与更新清单不符");
        }
        Signature[] current = signers(installed);
        Signature[] candidate = signers(downloaded);
        if (current.length == 0 || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(candidate)))) {
            throw new DownloadFailure("安装包签名与当前 App 不一致");
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        return signatures == null ? new Signature[0] : signatures;
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
}
