package com.lkwang.cjs701;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

final class ProxyControlPage extends ScrollView {
    private static final String PAIRING = "md1000_proxy_pairing";
    private final Activity activity;
    private final SharedPreferences settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView endpoint;
    private final TextView summary;
    private final TextView remoteNode;
    private final TextView downloadNode;
    private final TextView phoneInfo;
    private final TextView speedInfo;
    private final Button pair;
    private final Button refresh;
    private final Button power;
    private final Button benchmark;
    private final Button pickRemote;
    private final Button pickDownload;
    private final Button speedDetails;
    private final Button allowPhone;
    private final Button wifiSettings;
    private JSONObject report;
    private boolean active;
    private boolean busy;
    private boolean starting;
    private int generation;
    private final Runnable followUp = () -> query(null, null);

    ProxyControlPage(Activity context, SharedPreferences settings) {
        super(context);
        activity = context;
        this.settings = settings;
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(18), dp(20), dp(20));
        addView(page);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("代理", 30, AppUi.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        pair = button("配对", false);
        pair.setOnClickListener(view -> pairDevice());
        header.addView(pair, new LinearLayout.LayoutParams(dp(72), dp(48)));
        page.addView(header);
        endpoint = text("MD1000 · 局域网", 13, AppUi.MUTED);
        page.addView(endpoint);

        LinearLayout status = card(page, "代理服务");
        summary = text("请先配对 MD1000", 16, AppUi.INK);
        status.addView(summary);
        power = button("开启代理", true);
        power.setOnClickListener(view -> setEnabled("/v1/proxy", !(running() || starting)));
        append(status, power, 12);

        LinearLayout phone = card(page, "手机 Wi-Fi 接入");
        phoneInfo = text("等待配对", 14, AppUi.MUTED);
        phone.addView(phoneInfo);
        allowPhone = button("启用本机接入", false);
        allowPhone.setOnClickListener(view -> setEnabled("/v1/phone", !report.optJSONObject("phone").optBoolean("enabled")));
        append(phone, allowPhone, 8);
        wifiSettings = button("打开 Wi-Fi 设置", false);
        wifiSettings.setOnClickListener(view -> activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        append(phone, wifiSettings, 6);

        LinearLayout remote = card(page, "远程 · 17890");
        remoteNode = text("—", 15, AppUi.MUTED);
        remote.addView(remoteNode);
        pickRemote = button("选择远程节点", false);
        pickRemote.setOnClickListener(view -> choose("remote"));
        append(remote, pickRemote, 8);

        LinearLayout download = card(page, "下载 · 17891");
        downloadNode = text("—", 15, AppUi.MUTED);
        download.addView(downloadNode);
        speedInfo = text("等待第一次自动测速", 14, AppUi.MUTED);
        speedInfo.setPadding(0, dp(8), 0, 0);
        download.addView(speedInfo);
        speedDetails = button("查看测速结果", false);
        speedDetails.setOnClickListener(view -> showSpeedDetails());
        append(download, speedDetails, 6);
        pickDownload = button("选择下载节点", false);
        pickDownload.setOnClickListener(view -> choose("download"));
        append(download, pickDownload, 8);
        benchmark = button("开启自动测速", false);
        benchmark.setOnClickListener(view -> setEnabled("/v1/benchmark", !report.optBoolean("benchmark")));
        append(download, benchmark, 6);

        refresh = button("刷新状态", false);
        refresh.setOnClickListener(view -> query(null, null));
        append(page, refresh, 12);
        render(null);
    }

    private void pairDevice() {
        EditText input = new EditText(activity);
        input.setHint("粘贴 MD1000 配对码");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMaxLines(3);
        LinearLayout field = column();
        field.setPadding(dp(22), dp(4), dp(22), 0);
        field.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("配对 MD1000").setView(field)
                .setNegativeButton("取消", null).setPositiveButton("连接", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            try {
                ProxyControlClient.parse(value);
                settings.edit().putString(PAIRING, CloudAir.encrypt(value)).apply();
                report = null;
                dialog.dismiss();
                query(null, null);
            } catch (Exception error) {
                input.setError("请粘贴完整的 MD1000 配对码");
            }
        }));
        dialog.show();
    }

    private void choose(String key) {
        JSONObject group = report.optJSONObject(key);
        JSONArray nodes = group == null ? null : group.optJSONArray("nodes");
        List<String> choices = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (key.equals("remote")) {
            choices.add("自动选择低延迟节点");
            names.add(null);
        }
        if (nodes != null) {
            for (int index = 0; index < nodes.length(); index++) {
                String node = nodes.optString(index);
                if (node.isEmpty() || node.equals("REJECT")) continue;
                choices.add(node + " · " + delayLabel(node));
                names.add(node);
            }
        }
        new AlertDialog.Builder(activity).setTitle(key.equals("remote") ? "远程节点" : "下载节点")
                .setItems(choices.toArray(new String[0]), (dialog, index) -> action("/v1/selection", "group", key,
                        "node", names.get(index) == null ? JSONObject.NULL : names.get(index)))
                .setNegativeButton("取消", null).show();
    }

    private String delayLabel(String node) {
        JSONObject delays = report == null ? null : report.optJSONObject("delays_ms");
        int latency = delays == null ? 0 : delays.optInt(node);
        return latency > 0 ? latency + " ms" : "暂无延迟";
    }

    private JSONObject speedRound() {
        JSONObject speed = report == null ? null : report.optJSONObject("speed");
        return speed == null ? null : speed.optJSONObject("latest");
    }

    private String rate(double value) {
        NumberFormat number = NumberFormat.getNumberInstance();
        number.setMaximumFractionDigits(1);
        return number.format(value) + " Mbps";
    }

    private String speedSummary() {
        JSONObject speed = report == null ? null : report.optJSONObject("speed");
        boolean running = speed != null && speed.optBoolean("running");
        JSONObject round = speedRound();
        if (round == null) return running ? "正在测下载速度…" : "等待第一次自动测速";
        String prefix = running ? "正在测下载速度…\n" : "";
        String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(round.optLong("finished_at") * 1000));
        JSONArray samples = round.optJSONArray("samples");
        int total = samples == null ? 0 : samples.length();
        int successful = 0;
        double fastest = 0;
        for (int index = 0; index < total; index++) {
            JSONObject sample = samples.optJSONObject(index);
            if (sample == null || sample.isNull("mbps")) continue;
            successful++;
            fastest = Math.max(fastest, sample.optDouble("mbps", 0));
        }
        String result;
        if (round.optString("outcome").equals("no_candidates")) result = "暂无通过延迟检查的节点";
        else if (successful == 0) result = "未取得完整下载样本";
        else result = "最快 " + rate(fastest) + " · " + successful + "/" + total + " 成功";
        return prefix + "上轮 " + time + "\n" + result;
    }

    private void showSpeedDetails() {
        JSONObject round = speedRound();
        if (round == null) return;
        JSONArray samples = round.optJSONArray("samples");
        if (samples == null) return;
        StringBuilder detail = new StringBuilder();
        for (int index = 0; index < samples.length(); index++) {
            JSONObject sample = samples.optJSONObject(index);
            if (sample == null) continue;
            if (detail.length() > 0) detail.append("\n\n");
            detail.append(sample.optString("node")).append('\n');
            detail.append(sample.optInt("delay_ms")).append(" ms · ");
            detail.append(sample.isNull("mbps") ? "下载未完成" : rate(sample.optDouble("mbps")));
        }
        new AlertDialog.Builder(activity).setTitle("最近一轮下载测速").setMessage(detail.toString())
                .setPositiveButton("关闭", null).show();
    }

    private void setEnabled(String path, boolean enabled) {
        action(path, "enabled", enabled);
    }

    private void action(String path, Object... fields) {
        JSONObject body = new JSONObject();
        try {
            for (int index = 0; index < fields.length; index += 2) body.put((String) fields[index], fields[index + 1]);
            query(path, body);
        } catch (JSONException error) {
            render("无法生成控制请求");
        }
    }

    private void query(String path, JSONObject data) {
        if (!active || busy || !paired()) return;
        removeCallbacks(followUp);
        int version = generation;
        String encrypted = settings.getString(PAIRING, "");
        busy = true;
        render(path == null ? "正在读取状态…" : "正在发送控制指令…");
        worker.execute(() -> {
            JSONObject result = null;
            String address = "";
            String error = null;
            try {
                char[] pairing = CloudAir.decrypt(encrypted);
                ProxyControlClient client;
                try {
                    client = ProxyControlClient.parse(new String(pairing));
                } finally {
                    Arrays.fill(pairing, '\0');
                }
                address = client.host + ":" + client.port;
                if (path != null) client.request(path, data);
                if ("/v1/proxy".equals(path) || "/v1/benchmark".equals(path)) Thread.sleep(400);
                result = client.request("/v1/status", null);
            } catch (SSLException problem) {
                error = "设备证书校验失败，请核对配对码";
            } catch (ProxyControlClient.ControlException problem) {
                error = problem.getMessage();
            } catch (Exception problem) {
                error = "连接失败，请检查手机与 MD1000 的局域网连接";
            }
            JSONObject reply = result;
            String label = address;
            String warning = error;
            activity.runOnUiThread(() -> {
                if (!active || version != generation) return;
                busy = false;
                report = reply;
                if (!label.isEmpty()) endpoint.setText("MD1000 · " + label);
                render(warning);
                postDelayed(followUp, starting ? 2000 : 15000);
            });
        });
    }

    private boolean paired() { return !settings.getString(PAIRING, "").isEmpty(); }

    private boolean running() { return report != null && report.optString("state").equals("active"); }

    private void render(String message) {
        String state = report == null ? "" : report.optString("state");
        starting = state.equals("activating") || state.equals("deactivating");
        boolean running = running();
        boolean automatic = running && report.optBoolean("benchmark");
        String status = "请先配对 MD1000";
        if (paired()) status = "等待读取状态";
        if (report != null) {
            if (running) status = "代理运行中";
            else if (state.equals("activating")) status = "正在启动代理…";
            else if (state.equals("deactivating")) status = "正在关闭代理…";
            else if (state.equals("failed")) status = "代理启动失败";
            else status = "代理已关闭";
        }
        summary.setText(message == null ? status : message);
        power.setText(running || starting ? "关闭代理" : "开启代理");
        benchmark.setText(automatic ? "暂停自动测速" : "开启自动测速");
        pair.setEnabled(!busy);
        refresh.setEnabled(paired() && !busy);
        power.setEnabled(report != null && !busy && !state.equals("deactivating"));
        benchmark.setEnabled(running && !busy);
        JSONObject phone = report == null ? null : report.optJSONObject("phone");
        boolean phoneAllowed = phone != null && phone.optBoolean("enabled");
        if (phone != null) {
            phoneInfo.setText((phoneAllowed ? "本机已获准接入 · 有效 12 小时" : "本机接入尚未启用")
                    + "\n主机 " + phone.optString("host") + "\n端口 17890 低延迟 / 17891 下载\n国内常见平台与局域网直连");
        } else {
            phoneInfo.setText(paired() ? "等待接入状态" : "配对后可启用本机接入");
        }
        allowPhone.setEnabled(phone != null && !busy);
        allowPhone.setText(phoneAllowed ? "关闭本机接入" : "启用本机接入");
        wifiSettings.setEnabled(phoneAllowed);
        remoteNode.setText(running ? nodeLabel("remote", report.optJSONObject("remote")) : "—");
        downloadNode.setText(running ? nodeLabel("download", report.optJSONObject("download")) : "—");
        speedInfo.setText(speedSummary());
        JSONObject round = speedRound();
        JSONArray samples = round == null ? null : round.optJSONArray("samples");
        speedDetails.setEnabled(samples != null && samples.length() > 0 && !busy);
        pickRemote.setEnabled(running && !busy);
        JSONObject download = running ? report.optJSONObject("download") : null;
        JSONArray candidates = download == null ? null : download.optJSONArray("nodes");
        pickDownload.setEnabled(running && !busy && candidates != null && candidates.length() > 0);
    }

    private String nodeLabel(String key, JSONObject group) {
        if (group == null || group.optString("node").equals("REJECT")) return "暂无可用节点";
        String mode = key.equals("remote") ? (group.optBoolean("fixed") ? "手动" : "自动")
                : (report.optBoolean("benchmark") ? "自动测速" : "已暂停测速");
        String node = group.optString("node", "等待节点载入");
        return mode + " · " + node + "\n" + delayLabel(node);
    }

    void activate() {
        active = true;
        busy = false;
        if (paired()) query(null, null);
        else render(null);
    }

    void deactivate() {
        active = false;
        generation++;
        removeCallbacks(followUp);
    }

    void destroy() {
        deactivate();
        worker.shutdownNow();
    }

    private LinearLayout card(LinearLayout page, String title) {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(12), dp(15), dp(12));
        card.setBackground(AppUi.shape(activity, Color.WHITE, 18, AppUi.LINE));
        TextView name = text(title, 17, AppUi.INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(name);
        append(page, card, 12);
        return card;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setText(title);
        button.setTextColor(primary ? Color.WHITE : AppUi.TEAL);
        button.setBackground(AppUi.action(activity, primary ? AppUi.TEAL : AppUi.SELECTED, 14, 0));
        return button;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void append(LinearLayout parent, android.view.View child, int margin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(margin);
        parent.addView(child, size);
    }

    private TextView text(String title, float size, int color) { return AppUi.text(activity, title, size, color); }
    private int dp(float value) { return AppUi.dp(activity, value); }
}
