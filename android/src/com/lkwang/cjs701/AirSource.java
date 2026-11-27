package com.lkwang.cjs701;

import android.os.SystemClock;

import java.util.Map;

final class AirSource {
    private static final long LAN_RETRY_MS = 5_000L;
    private String host = "";
    private AirClient.Sample local = new AirClient.Sample("unconfigured");
    private long retryAt;

    static final class Reading {
        final AirClient.Sample sample;
        final boolean remote;

        Reading(AirClient.Sample sample, boolean remote) {
            this.sample = sample;
            this.remote = remote;
        }
    }

    Reading read(Map<String, ?> settings) {
        String configuredHost = AppConfig.value(settings, AirClient.HOST_KEY);
        if (!host.equals(configuredHost)) {
            host = configuredHost;
            local = new AirClient.Sample("unconfigured");
            retryAt = 0;
        }

        boolean fallback = CloudAir.fallbackEnabled(settings);
        if (!host.isEmpty() && (!fallback || SystemClock.elapsedRealtime() >= retryAt)) {
            local = AirClient.fetch(host);
            retryAt = available(local) ? 0 : SystemClock.elapsedRealtime() + LAN_RETRY_MS;
        }

        if (available(local) || !fallback) return new Reading(local, false);
        return new Reading(CloudAir.read(settings), true);
    }

    private static boolean available(AirClient.Sample sample) {
        // A reachable sensor may still be waiting for a valid measurement.
        return "live".equals(sample.status) || "waiting".equals(sample.status) || "stale".equals(sample.status);
    }
}
