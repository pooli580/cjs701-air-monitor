package com.lkwang.cjs701;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

final class DashboardPage extends LinearLayout {
    private static final String REMOTE_HOST = "md1000-monitor.invalid";
    private static final String REMOTE_URL = "https://" + REMOTE_HOST + "/";
    private final WebView dashboard;
    private final TextView loadStatus;
    private final TextView sourceLabel;
    private final ScheduledThreadPoolExecutor probe = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> probeTask;
    private int generation;
    private volatile CloudMonitor cloud;
    private volatile boolean remote;
    private volatile String lanBase = "";
    private boolean active;
    private boolean hasSource;
    private boolean loadFailed;

    DashboardPage(Context context) {
        super(context);
        probe.setRemoveOnCancelPolicy(true);
        setOrientation(VERTICAL);
        setBackgroundColor(AppUi.BACKGROUND);

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(20), dp(6), dp(12), dp(6));
        addView(toolbar, new LayoutParams(-1, -2));

        TextView title = AppUi.text(context, "MD1000", 25, AppUi.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine();
        toolbar.addView(title, new LayoutParams(0, -2, 1));

        sourceLabel = AppUi.text(context, "检查连接", 12, AppUi.MUTED);
        sourceLabel.setPadding(dp(12), dp(7), dp(12), dp(7));
        sourceLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sourceLabel.setBackground(AppUi.shape(context, AppUi.SELECTED, 18, 0));
        toolbar.addView(sourceLabel);

        ImageButton refresh = new ImageButton(context);
        refresh.setImageResource(R.drawable.ic_refresh);
        refresh.setImageTintList(ColorStateList.valueOf(AppUi.TEAL));
        refresh.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        refresh.setContentDescription("刷新监控");
        refresh.setTooltipText("刷新监控");
        refresh.setBackground(AppUi.action(context, Color.TRANSPARENT, 24, 0));
        refresh.setOnClickListener(view -> watchLan(true));
        LayoutParams refreshSize = new LayoutParams(dp(48), dp(48));
        refreshSize.leftMargin = dp(4);
        toolbar.addView(refresh, refreshSize);

        loadStatus = AppUi.text(context, "正在检查 MD1000 连接…", 14, AppUi.MUTED);
        int inset = dp(20);
        loadStatus.setPadding(inset, inset, inset, inset);
        loadStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(loadStatus);

        dashboard = new WebView(context);
        dashboard.setBackgroundColor(AppUi.BACKGROUND);
        WebSettings settings = dashboard.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        dashboard.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !currentUrl().equals(request.getUrl().toString());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                        WebResourceRequest request) {
                Uri url = request.getUrl();
                String path = url.getPath();
                if ("GET".equals(request.getMethod()) && ("/".equals(path) || "/api/history".equals(path))) {
                    if (!remote && isLan(url)) return null;
                    if (remote && isRemote(url)) return remoteResponse(url, path);
                }
                return jsonError(403, "Forbidden", "请求不在监控台范围内");
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                if (currentUrl().equals(url) && dashboard.getVisibility() == View.VISIBLE) {
                    loadStatus.setVisibility(View.GONE);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   WebResourceError error) {
                if (request.isForMainFrame() && currentUrl().equals(request.getUrl().toString())) pageFailed();
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                       WebResourceResponse response) {
                if (request.isForMainFrame() && currentUrl().equals(request.getUrl().toString())) pageFailed();
            }
        });
        addView(dashboard, new LayoutParams(-1, 0, 1));
    }

    private int dp(float value) {
        return AppUi.dp(getContext(), value);
    }

    private boolean isLan(Uri url) {
        String base = lanBase;
        if (base.isEmpty()) return false;
        Uri expected = Uri.parse(base);
        return expected.getScheme().equals(url.getScheme())
                && expected.getEncodedAuthority().equals(url.getEncodedAuthority());
    }

    private static boolean isRemote(Uri url) {
        return "https".equals(url.getScheme()) && REMOTE_HOST.equals(url.getHost()) && url.getPort() == -1
                && url.getUserInfo() == null;
    }

    private String currentUrl() {
        return remote ? REMOTE_URL : lanBase;
    }

    private WebResourceResponse remoteResponse(Uri url, String path) {
        if ("/".equals(path)) {
            try {
                return new WebResourceResponse("text/html", "UTF-8",
                        getContext().getAssets().open("md1000-monitor.html"));
            } catch (IOException e) {
                return jsonError(500, "Internal Server Error", "App 内的监控台页面无法加载");
            }
        }
        CloudMonitor source = cloud;
        if (source == null) return jsonError(503, "Service Unavailable", "正在连接公网 MQTT…");
        try {
            String history = source.history(url.getQueryParameter("range"));
            if (history != null) return json(200, "OK", history);
            return jsonError(503, "Service Unavailable", source.waitingMessage());
        } catch (JSONException e) {
            return jsonError(400, "Bad Request", "无法读取这个时间范围");
        }
    }

    private static WebResourceResponse jsonError(int code, String reason, String message) {
        return json(code, reason, "{\"message\":" + JSONObject.quote(message) + "}");
    }

    private static WebResourceResponse json(int code, String reason, String body) {
        return new WebResourceResponse("application/json", "UTF-8", code, reason,
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void watchLan(boolean reload) {
        if (probeTask != null) probeTask.cancel(true);
        int token = ++generation;
        String base = lanBase;
        AtomicBoolean refreshOnce = new AtomicBoolean(reload);
        probeTask = probe.scheduleWithFixedDelay(() -> {
            boolean reachable = lanAvailable(base);
            boolean refresh = refreshOnce.getAndSet(false);
            post(() -> {
                if (active && token == generation) selectSource(!reachable, refresh);
            });
        }, 0, 5, TimeUnit.SECONDS);
    }

    private static boolean lanAvailable(String base) {
        if (base.isEmpty()) return false;
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(base + "api/history?view=system");
            connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (data.size() + count > 16384) return false;
                    data.write(buffer, 0, count);
                }
                return new JSONObject(data.toString(StandardCharsets.UTF_8.name())).has("system");
            }
        } catch (IOException | JSONException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void selectSource(boolean useRemote, boolean refresh) {
        boolean changed = !hasSource || remote != useRemote;
        hasSource = true;
        remote = useRemote;
        if (remote) startCloud();
        else stopCloud();
        if (changed || refresh || loadFailed) loadDashboard();
    }

    private void loadDashboard() {
        loadFailed = false;
        dashboard.stopLoading();
        String topic = MonitorService.settings(getContext()).getString(AppConfig.MONITOR_TOPIC, "");
        sourceLabel.setText(remote ? (topic.isEmpty() ? "待配置" : "远程") : "局域网");
        sourceLabel.setTextColor(AppUi.TEAL);
        loadStatus.setText(remote ? "正在打开远程监控…" : "正在连接 MD1000…");
        loadStatus.setVisibility(View.VISIBLE);
        dashboard.setVisibility(View.VISIBLE);
        dashboard.loadUrl(currentUrl());
    }

    private void pageFailed() {
        loadFailed = true;
        loadStatus.setText(remote ? "无法打开远程监控页。请点刷新。"
                : "正在重新检测 MD1000 的局域网连接…");
        loadStatus.setVisibility(View.VISIBLE);
        dashboard.setVisibility(View.INVISIBLE);
    }

    private void startCloud() {
        Context context = getContext();
        if (cloud == null) cloud = new CloudMonitor(MonitorService.settings(context).getAll());
    }

    private void stopCloud() {
        CloudMonitor previous = cloud;
        cloud = null;
        if (previous != null) previous.close();
    }

    void activate() {
        if (active) return;
        active = true;
        String address = MonitorService.settings(getContext()).getString(AppConfig.MONITOR_LAN, "");
        if (!address.equals(lanBase)) {
            lanBase = address;
            hasSource = false;
        }
        dashboard.onResume();
        watchLan(false);
    }

    void deactivate() {
        if (!active) return;
        active = false;
        ++generation;
        if (probeTask != null) probeTask.cancel(true);
        stopCloud();
        dashboard.onPause();
    }

    void destroy() {
        deactivate();
        probe.shutdownNow();
        dashboard.stopLoading();
        removeView(dashboard);
        dashboard.destroy();
    }
}
