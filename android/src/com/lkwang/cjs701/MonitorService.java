package com.lkwang.cjs701;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MonitorService extends Service {
    static final String ALERT_CHANNEL = "cjs701_alerts";
    private static final String MONITOR_CHANNEL = "cjs701_monitor";
    private static final String STOP = "com.lkwang.cjs701.STOP";
    private static final int STATUS_ID = 1;
    private static final int ALERT_ID = 2;
    private static final int TEST_ID = 3;
    static volatile boolean running;
    private ScheduledExecutorService executor;
    private boolean cloudReader;

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("air_settings", MODE_PRIVATE);
    }

    static boolean hasThreshold(SharedPreferences settings) {
        for (AirClient.Metric metric : AirClient.Metric.values()) {
            if (metric.alertable && settings.contains(metric.key)) return true;
        }
        return false;
    }

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                MONITOR_CHANNEL, "后台监测状态", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(
                ALERT_CHANNEL, "浓度阈值提醒", NotificationManager.IMPORTANCE_HIGH));
    }

    static boolean alertsEnabled(Context context) {
        createChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager.areNotificationsEnabled()
                && manager.getNotificationChannel(ALERT_CHANNEL).getImportance()
                != NotificationManager.IMPORTANCE_NONE;
    }

    static void testAlert(Context context) {
        context.getSystemService(NotificationManager.class).notify(TEST_ID,
                alertNotification(context, "通知测试", "你的手机可以接收 CJS-701 的阈值提醒。"));
    }

    private static PendingIntent openApp(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification alertNotification(Context context, String title, String content) {
        return new Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.notify_icon)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (executor != null) return START_STICKY;

        createChannels(this);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.notify_icon)
                .setContentTitle("空气质量监测已开启")
                .setContentText("按设定阈值检查 ESP32 读数")
                .setContentIntent(openApp(this))
                .addAction(new Notification.Action.Builder(null, "停止监测", stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        startForeground(STATUS_ID, notification);
        if (!hasThreshold(settings(this)) || !alertsEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        CloudAir.acquire();
        cloudReader = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkThresholds, 0, 30, TimeUnit.SECONDS);
        return START_STICKY;
    }

    private void checkThresholds() {
        if (!running) return;
        Map<String, ?> snapshot = settings(this).getAll();
        String ip = AppConfig.value(snapshot, AirClient.HOST_KEY);
        AirClient.Sample sample = CloudAir.read(snapshot);
        if (!running || !"live".equals(sample.status)
                || !snapshot.equals(settings(this).getAll()) || !alertsEnabled(this)) return;

        SharedPreferences state = getSharedPreferences("air_alert_state", MODE_PRIVATE);
        SharedPreferences.Editor changes = state.edit();
        List<String> alerts = new ArrayList<>();
        for (AirClient.Metric metric : AirClient.Metric.values()) {
            String limitText = (String) snapshot.get(metric.key);
            if (!metric.alertable || limitText == null) continue;
            double limit = Double.parseDouble(limitText);
            double value = sample.values.get(metric);
            String marker = (CloudAir.enabled(snapshot) ? "mqtt" : ip) + "/" + limitText;
            String old = state.getString(metric.key, "");
            if (value > limit && !marker.equals(old)) {
                alerts.add(metric.label + " " + metric.display(value) + "（设定值 " + limitText
                        + (metric.unit.isEmpty() ? "" : " " + metric.unit) + "）");
                changes.putString(metric.key, marker);
            } else if (value <= limit && !old.isEmpty()) {
                changes.remove(metric.key);
            }
        }
        if (running && !alerts.isEmpty()) {
            getSystemService(NotificationManager.class).notify(ALERT_ID, alertNotification(
                    this, "空气质量读数超过设定值", String.join("\n", alerts)));
        }
        changes.apply();
    }

    @Override public void onDestroy() {
        running = false;
        if (executor != null) executor.shutdownNow();
        if (cloudReader) CloudAir.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
