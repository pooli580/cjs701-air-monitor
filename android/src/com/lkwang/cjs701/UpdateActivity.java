package com.lkwang.cjs701;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class UpdateActivity extends Activity {
    private static final String SOURCE_KEY = "update_feed_url";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Future<?> job;
    private SharedPreferences preferences;
    private EditText source;
    private TextView status;
    private Button action;
    private long installedCode;
    private boolean updateReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = MonitorService.settings(this);
        PackageInfo installed;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            finish();
            return;
        }
        installedCode = UpdateFeed.versionCode(installed);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(AppUi.BACKGROUND);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(20));
        scroll.addView(page);
        TextView title = AppUi.text(this, "软件更新", 29, AppUi.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        page.addView(title);
        page.addView(AppUi.text(this, "当前版本 " + installed.versionName, 14, AppUi.MUTED));

        TextView caption = AppUi.text(this, "HTTPS 更新源", 14, AppUi.INK);
        caption.setPadding(0, dp(26), 0, 0);
        page.addView(caption);
        source = new EditText(this);
        source.setSingleLine(true);
        source.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        source.setTextSize(15);
        source.setContentDescription("HTTPS 更新源");
        source.setHint("https://…/latest.json");
        source.setText(AppConfig.updateFeed(preferences.getString(SOURCE_KEY, "")));
        page.addView(source, new LinearLayout.LayoutParams(-1, -2));

        action = new Button(this);
        action.setAllCaps(false);
        action.setText("检查更新");
        action.setTextColor(Color.WHITE);
        action.setBackground(AppUi.action(this, AppUi.TEAL, 14, 0));
        action.setOnClickListener(view -> {
            if (updateReady) installUpdate();
            else checkUpdates();
        });
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, dp(50));
        actionSize.topMargin = dp(18);
        page.addView(action, actionSize);

        status = AppUi.text(this, "", 14, AppUi.MUTED);
        status.setPadding(0, dp(12), 0, 0);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        setContentView(scroll);
    }

    private int dp(float value) { return AppUi.dp(this, value); }

    private void checkUpdates() {
        URL feedUrl;
        try {
            feedUrl = UpdateFeed.httpsUrl(source.getText().toString().trim());
        } catch (IOException e) {
            source.setError("请输入 HTTPS 更新清单地址");
            return;
        }
        preferences.edit().putString(SOURCE_KEY, feedUrl.toString()).apply();
        setBusy(true, "正在检查更新…");
        job = io.submit(() -> {
            try {
                UpdateFeed release = UpdateFeed.fetch(feedUrl);
                showResult(() -> {
                    setBusy(false, release.code > installedCode ? "发现可用更新" : "当前已是最新版本");
                    if (release.code > installedCode) showRelease(release);
                });
            } catch (IOException | JSONException e) {
                showResult(() -> setBusy(false, e instanceof JSONException ? "更新清单格式有误" : "无法读取更新源"));
            }
        });
    }

    private void showRelease(UpdateFeed release) {
        new AlertDialog.Builder(this).setTitle("更新到 " + release.version)
                .setMessage(release.notes.isEmpty() ? "下载后由安卓确认安装。" : release.notes)
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载并安装", (dialog, which) -> download(release))
                .show();
    }

    private void download(UpdateFeed release) {
        setBusy(true, "正在下载并校验安装包…");
        job = io.submit(() -> {
            try {
                synchronized (UpdateFeed.class) {
                    release.download(getApplicationContext());
                }
                showResult(() -> {
                    updateReady = true;
                    action.setText("安装更新");
                    setBusy(false, "安装包已校验");
                    installUpdate();
                });
            } catch (Exception e) {
                showResult(() -> setBusy(false,
                        e instanceof UpdateFeed.DownloadFailure ? e.getMessage() : "下载或校验失败，请检查更新源后重试"));
            }
        });
    }

    private void installUpdate() {
        if (!UpdateFileProvider.file(this).isFile()) {
            updateReady = false;
            action.setText("检查更新");
            setBusy(false, "请重新下载更新");
            return;
        }
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this).setTitle("允许安装更新")
                        .setMessage("安卓需要先允许 CJS-701 发起安装。返回后点「安装更新」。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("打开系统设置", (dialog, which) -> openInstallSettings())
                        .show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(UpdateFileProvider.URI, UpdateFileProvider.APK_TYPE);
            intent.setClipData(ClipData.newRawUri("CJS701 update", UpdateFileProvider.URI));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText("系统安装程序当前不可用");
        }
    }

    private void openInstallSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText("当前设备无法授予安装权限");
        }
    }

    private void setBusy(boolean busy, String message) {
        source.setEnabled(!busy && !updateReady);
        action.setEnabled(!busy);
        status.setText(message);
    }

    private void showResult(Runnable result) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) result.run();
        });
    }

    @Override public void onDestroy() {
        if (job != null) job.cancel(true);
        io.shutdownNow();
        super.onDestroy();
    }
}
