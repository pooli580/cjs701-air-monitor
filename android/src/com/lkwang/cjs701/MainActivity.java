package com.lkwang.cjs701;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class MainActivity extends Activity {
    private static final int INK = AppUi.INK;
    private static final int MUTED = AppUi.MUTED;
    private static final int TEAL = AppUi.TEAL;
    private static final int START_PERMISSION = 1;
    private static final int TEST_PERMISSION = 2;
    private static final int IMPORT_CA = 3;
    private final ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor();
    private final EnumMap<AirClient.Metric, TextView> values = new EnumMap<>(AirClient.Metric.class);
    private ScheduledFuture<?> polling;
    private SharedPreferences settings;
    private TextView status;
    private Button monitor;
    private int generation;
    private FrameLayout content;
    private ScrollView airPage;
    private DashboardPage dashboardPage;
    private Tab airTab;
    private Tab dashboardTab;
    private boolean showingDashboard;
    private boolean foreground;
    private Connections connectionsDialog;
    private android.window.OnBackInvokedCallback backToAir;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = MonitorService.settings(this);
        MonitorService.createChannels(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppUi.BACKGROUND);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
            getWindow().setDecorFitsSystemWindows(false);
        }
        content = new SwipeContent();
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        airPage = createAirPage();
        content.addView(airPage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(createNavigation());
        setContentView(root);
        if (savedInstanceState != null && savedInstanceState.getBoolean("showingDashboard")) {
            selectPage(true, false);
        } else {
            updateTabs();
        }
        updateMonitorButton();
    }

    private ScrollView createAirPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(20));
        scroll.addView(page);

        LinearLayout heading = row();
        TextView title = text("空气质量", 30, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button configure = new Button(this, null, android.R.attr.borderlessButtonStyle);
        configure.setText("设置");
        configure.setTextColor(TEAL);
        configure.setTextSize(13);
        configure.setBackground(AppUi.action(this, Color.WHITE, 24, AppUi.LINE));
        configure.setMinHeight(dp(48));
        configure.setOnClickListener(view -> showSettings());
        heading.addView(configure, new LinearLayout.LayoutParams(dp(60), dp(48)));
        page.addView(heading);

        status = text("连接中", 13, MUTED);
        page.addView(status);
        LinearLayout comfort = row();
        comfort.setPadding(dp(16), dp(15), dp(16), dp(15));
        comfort.setBackground(AppUi.shape(this, 0xFFEAF2EB, 18, 0));
        addComfort(comfort, AirClient.Metric.TEMPERATURE);
        addComfort(comfort, AirClient.Metric.HUMIDITY);
        LinearLayout.LayoutParams comfortSize = new LinearLayout.LayoutParams(-1, -2);
        comfortSize.topMargin = dp(17);
        comfortSize.bottomMargin = dp(12);
        page.addView(comfort, comfortSize);

        LinearLayout readings = new LinearLayout(this);
        readings.setOrientation(LinearLayout.VERTICAL);
        readings.setPadding(dp(15), dp(2), dp(15), dp(2));
        readings.setBackground(AppUi.shape(this, Color.WHITE, 18, AppUi.LINE));
        boolean first = true;
        for (AirClient.Metric metric : AirClient.Metric.values()) {
            if (!metric.alertable) continue;
            addMetric(readings, metric, first);
            first = false;
        }
        page.addView(readings);

        monitor = new Button(this);
        monitor.setAllCaps(false);
        monitor.setTextColor(Color.WHITE);
        monitor.setBackground(AppUi.action(this, TEAL, 14, 0));
        monitor.setOnClickListener(view -> toggleMonitoring());
        LinearLayout.LayoutParams buttonSize = new LinearLayout.LayoutParams(-1, dp(52));
        buttonSize.topMargin = dp(14);
        page.addView(monitor, buttonSize);

        return scroll;
    }

    private View createNavigation() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xFFFCFDFB);
        View divider = new View(this);
        divider.setBackgroundColor(AppUi.LINE);
        bar.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout items = row();
        items.setPadding(dp(16), dp(4), dp(16), dp(5));
        bar.addView(items, new LinearLayout.LayoutParams(-1, -2));
        airTab = new Tab("空气质量", R.drawable.ic_air, false);
        dashboardTab = new Tab("MD1000", R.drawable.ic_md1000, true);
        items.addView(airTab, new LinearLayout.LayoutParams(0, -2, 1));
        items.addView(dashboardTab, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }

    private final class Tab extends LinearLayout {
        private final FrameLayout indicator;
        private final ImageView icon;
        private final TextView label;

        Tab(String title, int image, boolean dashboard) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setFocusable(true);
            setContentDescription(title);
            setMinimumHeight(dp(56));
            setPadding(0, dp(4), 0, dp(3));
            setBackground(AppUi.action(MainActivity.this, Color.TRANSPARENT, 16, 0));
            setOnClickListener(view -> selectPage(dashboard, true));

            indicator = new FrameLayout(MainActivity.this);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            icon = new ImageView(MainActivity.this);
            icon.setImageResource(image);
            indicator.addView(icon, new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
            addView(indicator, new LinearLayout.LayoutParams(dp(58), dp(29)));
            label = text(title, 12, MUTED);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label, new LinearLayout.LayoutParams(-1, -2));
        }

        void update(boolean active) {
            setSelected(active);
            indicator.setBackground(AppUi.shape(MainActivity.this,
                    active ? AppUi.SELECTED : Color.TRANSPARENT, 16, 0));
            icon.setImageTintList(ColorStateList.valueOf(active ? TEAL : MUTED));
            label.setTextColor(active ? INK : MUTED);
            label.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }

    private final class SwipeContent extends FrameLayout {
        private float startX;
        private float startY;
        private boolean tracking;

        SwipeContent() {
            super(MainActivity.this);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();
            if (action == MotionEvent.ACTION_DOWN) {
                startX = x;
                startY = y;
                AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
                tracking = x > dp(24) && x < getWidth() - dp(24)
                        && !accessibility.isTouchExplorationEnabled();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
                tracking = false;
            } else if (tracking && action == MotionEvent.ACTION_MOVE) {
                float vertical = Math.abs(y - startY);
                if (vertical > dp(28) && vertical > Math.abs(x - startX)) tracking = false;
            } else if (action == MotionEvent.ACTION_UP) {
                float dx = x - startX;
                boolean towardOtherPage = showingDashboard ? dx > dp(56) : dx < -dp(56);
                boolean swipe = tracking && towardOtherPage && Math.abs(dx) > 1.7f * Math.abs(y - startY);
                tracking = false;
                if (swipe) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    selectPage(!showingDashboard, true);
                    return true;
                }
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private void createDashboard() {
        dashboardPage = new DashboardPage(this);
        content.addView(dashboardPage, new FrameLayout.LayoutParams(-1, -1));
    }

    private void selectPage(boolean dashboard, boolean animate) {
        if (showingDashboard == dashboard) return;
        if (foreground) deactivatePage();
        showingDashboard = dashboard;
        if (dashboardPage == null) createDashboard();
        View incoming = dashboard ? dashboardPage : airPage;
        View outgoing = dashboard ? airPage : dashboardPage;
        outgoing.animate().cancel();
        outgoing.setVisibility(View.GONE);
        incoming.setVisibility(View.VISIBLE);
        incoming.setTranslationX(0);
        incoming.setAlpha(1);
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            incoming.setTranslationX(dp(dashboard ? 22 : -22));
            incoming.setAlpha(0.84f);
            incoming.animate().translationX(0).alpha(1).setDuration(175).start();
        }
        updateTabs();
        if (foreground) activatePage();
    }

    private void updateTabs() {
        airTab.update(!showingDashboard);
        dashboardTab.update(showingDashboard);
        if (Build.VERSION.SDK_INT >= 33) {
            if (showingDashboard && backToAir == null) {
                backToAir = () -> selectPage(false, true);
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToAir);
            } else if (!showingDashboard && backToAir != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backToAir);
                backToAir = null;
            }
        }
    }

    private void activatePage() {
        if (showingDashboard) {
            dashboardPage.activate();
        } else {
            CloudAir.acquire();
            startReading();
        }
    }

    private void deactivatePage() {
        if (showingDashboard) {
            dashboardPage.deactivate();
        } else {
            stopReading();
            CloudAir.release();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("showingDashboard", showingDashboard);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (showingDashboard) selectPage(false, true);
        else super.onBackPressed();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String content, float size, int color) {
        return AppUi.text(this, content, size, color);
    }

    private int dp(float value) {
        return AppUi.dp(this, value);
    }

    private void addComfort(LinearLayout row, AirClient.Metric metric) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(text(metric.label, 14, MUTED));
        TextView value = text("—", 28, INK);
        value.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        column.addView(value);
        values.put(metric, value);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void addMetric(LinearLayout page, AirClient.Metric metric, boolean first) {
        if (!first) {
            View rule = new View(this);
            rule.setBackgroundColor(AppUi.LINE);
            page.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        LinearLayout row = row();
        row.setMinimumHeight(dp(46));
        row.addView(text(metric.label, 16, INK), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text("—", 17, INK);
        values.put(metric, value);
        row.addView(value);
        page.addView(row);
    }

    @Override public void onStart() {
        super.onStart();
        foreground = true;
        activatePage();
    }

    @Override public void onStop() {
        deactivatePage();
        foreground = false;
        super.onStop();
    }

    @Override public void onDestroy() {
        if (dashboardPage != null) dashboardPage.destroy();
        reader.shutdownNow();
        super.onDestroy();
    }

    private void startReading() {
        stopReading();
        int token = generation;
        String ip = settings.getString(AirClient.HOST_KEY, "");
        Map<String, ?> snapshot = settings.getAll();
        boolean cloud = CloudAir.enabled(snapshot);
        polling = reader.scheduleWithFixedDelay(() -> {
            AirClient.Sample sample = CloudAir.read(snapshot);
            runOnUiThread(() -> {
                if (generation == token) render(sample, cloud, ip);
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void stopReading() {
        ++generation;
        if (polling != null) polling.cancel(true);
    }

    private void render(AirClient.Sample sample, boolean cloud, String ip) {
        String message;
        switch (sample.status) {
            case "live":
                message = (cloud ? "公网 MQTT" : "局域网") + " · 已更新";
                break;
            case "waiting":
                message = cloud ? "公网 MQTT · 等待传感器上报" : "等待传感器上报";
                break;
            case "stale":
                message = "暂无有效数据";
                break;
            case "invalid":
                message = "ESP32 返回的数据异常";
                break;
            case "mqtt_settings":
                message = "请在设置中填写 MQTT 地址、空气主题和账号信息";
                break;
            case "unconfigured":
                message = "在设置中添加 ESP32 或 MQTT 数据源";
                break;
            case "mqtt_denied":
                message = "MQTT 账号或订阅权限被拒绝";
                break;
            case "mqtt_error":
                message = "MQTT 连接初始化失败";
                break;
            default:
                message = cloud ? "公网 MQTT · 当前离线" : "连接失败 · " + ip;
        }
        status.setText(message);
        status.setTextColor("live".equals(sample.status) ? TEAL : MUTED);
        for (Map.Entry<AirClient.Metric, TextView> entry : values.entrySet()) {
            Double value = sample.values.get(entry.getKey());
            entry.getValue().setText(value == null ? "—" : entry.getKey().display(value));
        }
        updateMonitorButton();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(6), dp(24), dp(10));
        scroll.addView(form);

        Button updates = new Button(this, null, android.R.attr.borderlessButtonStyle);
        updates.setText("检查更新");
        updates.setTextColor(TEAL);
        updates.setOnClickListener(view -> startActivity(new Intent(this, UpdateActivity.class)));
        form.addView(updates, new LinearLayout.LayoutParams(-1, -2));

        Connections connection = new Connections(form);
        connectionsDialog = connection;
        TextView help = text("填写需要提醒的阈值，其余留空。", 14, MUTED);
        help.setPadding(0, dp(13), 0, dp(3));
        form.addView(help);
        EnumMap<AirClient.Metric, EditText> thresholds = new EnumMap<>(AirClient.Metric.class);
        for (AirClient.Metric metric : AirClient.Metric.values()) {
            if (!metric.alertable) continue;
            String label = metric.unit.isEmpty() ? metric.label : metric.label + "  " + metric.unit;
            thresholds.put(metric, field(form, label,
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                    settings.getString(metric.key, "")));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setNeutralButton("测试通知", (ignored, which) -> handleAlertPermission(TEST_PERMISSION))
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> saveSettings(dialog, connection, thresholds)));
        dialog.setOnDismissListener(ignored -> { if (connectionsDialog == connection) connectionsDialog = null; });
        dialog.show();
    }

    private final class Connections {
        final CheckBox useCloud;
        final EditText ip, monitor, broker, airTopic, monitorTopic, account, password;
        final Button certificate;
        String ca;

        Connections(LinearLayout form) {
            ip = savedField(form, "ESP32 局域网 IPv4", InputType.TYPE_CLASS_PHONE, AirClient.HOST_KEY);
            monitor = savedField(form, "监控台局域网地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    AppConfig.MONITOR_LAN);
            monitor.setHint("http://局域网地址:8090/");
            useCloud = new CheckBox(MainActivity.this);
            useCloud.setText("空气质量使用公网 MQTT");
            useCloud.setChecked(CloudAir.enabled(settings.getAll()));
            form.addView(useCloud);
            broker = savedField(form, "MQTT TLS 地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    AppConfig.MQTT_URI);
            broker.setHint("ssl://主机:8883");
            airTopic = savedField(form, "MQTT 空气主题", InputType.TYPE_CLASS_TEXT, AppConfig.AIR_TOPIC);
            monitorTopic = savedField(form, "MQTT 监控主题", InputType.TYPE_CLASS_TEXT, AppConfig.MONITOR_TOPIC);
            account = savedField(form, "MQTT 用户名", InputType.TYPE_CLASS_TEXT, CloudAir.USER);
            password = field(form, "MQTT 密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
            if (settings.contains(CloudAir.SECRET)) password.setHint("已保存，留空继续使用");
            ca = settings.getString(AppConfig.MQTT_CA, "");
            certificate = new Button(MainActivity.this, null, android.R.attr.borderlessButtonStyle);
            certificate.setTextColor(TEAL);
            updateCertificateLabel();
            certificate.setOnClickListener(view -> {
                String[] choices = {"导入 CA 证书", "使用系统证书"};
                new AlertDialog.Builder(MainActivity.this).setTitle("MQTT TLS 证书")
                        .setItems(choices, (dialog, which) -> {
                            if (which == 0) chooseCa();
                            else { ca = ""; updateCertificateLabel(); }
                        }).show();
            });
            form.addView(certificate);
        }

        void updateCertificateLabel() {
            certificate.setText(ca.isEmpty() ? "TLS：使用系统证书" : "TLS：已配置自定义 CA");
        }
    }

    private EditText savedField(LinearLayout form, String label, int type, String key) {
        return field(form, label, type, settings.getString(key, ""));
    }

    private void chooseCa() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, IMPORT_CA);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "无法打开证书选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Connections form = connectionsDialog;
        if (request != IMPORT_CA || result != RESULT_OK || data == null || form == null) return;
        Uri file = data.getData();
        if (file == null) return;
        reader.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(file);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new IOException("Missing certificate");
                byte[] block = new byte[1024];
                int size;
                while ((size = input.read(block)) != -1) {
                    if (bytes.size() + size > 32768) throw new IOException("Certificate too large");
                    bytes.write(block, 0, size);
                }
                String pem = AppConfig.pem(bytes.toByteArray());
                runOnUiThread(() -> {
                    if (connectionsDialog == form) { form.ca = pem; form.updateCertificateLabel(); }
                });
            } catch (IOException | java.security.cert.CertificateException | SecurityException e) {
                runOnUiThread(() -> {
                    if (connectionsDialog == form) Toast.makeText(this, "无法读取 CA 证书", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private EditText field(LinearLayout form, String label, int type, String initialValue) {
        TextView caption = text(label, 14, MUTED);
        form.addView(caption);
        EditText input = new EditText(this);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setText(initialValue);
        input.setContentDescription(label);
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private static String validated(EditText input, Function<String, String> parse) {
        try {
            String value = input.getText().toString().trim();
            return value.isEmpty() ? "" : parse.apply(value);
        } catch (IllegalArgumentException ex) {
            input.setError(ex.getMessage());
            input.requestFocus();
            return null;
        }
    }

    private void saveSettings(AlertDialog dialog, Connections form, EnumMap<AirClient.Metric, EditText> thresholds) {
        String ip = validated(form.ip, AirClient::normalizeIp);
        String monitor = validated(form.monitor, AppConfig::lanUrl);
        String broker = validated(form.broker, AppConfig::mqttUri);
        String airTopic = validated(form.airTopic, AppConfig::topic);
        String monitorTopic = validated(form.monitorTopic, AppConfig::topic);
        if (ip == null || monitor == null || broker == null || airTopic == null || monitorTopic == null) return;
        boolean cloud = form.useCloud.isChecked();
        boolean mqttUsed = cloud || !monitorTopic.isEmpty();
        if (mqttUsed && broker.isEmpty()) { form.broker.setError("填写 MQTT TLS 地址"); form.broker.requestFocus(); return; }
        if (cloud && airTopic.isEmpty()) { form.airTopic.setError("填写空气数据主题"); form.airTopic.requestFocus(); return; }
        String username = form.account.getText().toString().trim();
        String input = form.password.getText().toString();
        boolean sameAccount = username.equals(settings.getString(CloudAir.USER, ""))
                && broker.equals(settings.getString(AppConfig.MQTT_URI, ""));
        if (mqttUsed && !username.isEmpty() && input.isEmpty() && (!sameAccount || !settings.contains(CloudAir.SECRET))) {
            form.password.setError("填写此服务器和用户名对应的密码");
            form.password.requestFocus();
            return;
        }
        if (username.isEmpty() && !input.isEmpty()) { form.account.setError("填写账号或清空密码"); form.account.requestFocus(); return; }
        SharedPreferences.Editor changes = settings.edit().putString(AirClient.HOST_KEY, ip)
                .putString(AppConfig.MONITOR_LAN, monitor).putString(AppConfig.MQTT_URI, broker)
                .putString(AppConfig.AIR_TOPIC, airTopic).putString(AppConfig.MONITOR_TOPIC, monitorTopic)
                .putString(AppConfig.MQTT_CA, form.ca).putBoolean(CloudAir.ENABLED, cloud).putString(CloudAir.USER, username);
        if (!input.isEmpty()) {
            try {
                changes.putString(CloudAir.SECRET, CloudAir.encrypt(input));
            } catch (java.security.GeneralSecurityException | java.io.IOException e) {
                form.password.setError("无法保存 MQTT 密码");
                return;
            }
        } else if (!sameAccount || username.isEmpty()) {
            changes.remove(CloudAir.SECRET);
        }
        for (Map.Entry<AirClient.Metric, EditText> entry : thresholds.entrySet()) {
            String value = entry.getValue().getText().toString().trim();
            if (value.isEmpty()) {
                changes.remove(entry.getKey().key);
                continue;
            }
            try {
                double limit = Double.parseDouble(value.replace(',', '.'));
                if (limit < 0 || !Double.isFinite(limit)) throw new NumberFormatException();
                changes.putString(entry.getKey().key,
                        java.math.BigDecimal.valueOf(limit).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ex) {
                entry.getValue().setError("请输入大于或等于 0 的数");
                return;
            }
        }
        changes.apply();
        CloudAir.settingsChanged();
        if (!MonitorService.hasThreshold(settings)) stopService(new Intent(this, MonitorService.class));
        dialog.dismiss();
        status.setText("连接中");
        startReading();
    }

    private void toggleMonitoring() {
        if (MonitorService.running) {
            stopService(new Intent(this, MonitorService.class));
            monitor.setText("开启阈值监测");
        } else if (MonitorService.hasThreshold(settings)) {
            handleAlertPermission(START_PERMISSION);
        } else {
            showSettings();
        }
    }

    private void handleAlertPermission(int action) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, action);
        } else if (MonitorService.alertsEnabled(this)) {
            performNotificationAction(action);
        } else {
            Toast.makeText(this, "请在系统设置中允许浓度阈值提醒", Toast.LENGTH_LONG).show();
        }
    }

    private void performNotificationAction(int action) {
        if (action == TEST_PERMISSION) {
            MonitorService.testAlert(this);
        } else if (action == START_PERMISSION && MonitorService.hasThreshold(settings)) {
            startForegroundService(new Intent(this, MonitorService.class));
            monitor.setText("停止阈值监测");
        }
    }

    private void updateMonitorButton() {
        monitor.setText(MonitorService.running ? "停止阈值监测" : "开启阈值监测");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (results.length != 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            handleAlertPermission(requestCode);
        }
    }
}
