package com.frostwork.bgenhance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

/** Private diagnostics storage. Hook processes write through {@link ModuleBridgeProvider}. */
public final class DiagnosticsStore {
    private DiagnosticsStore() {}

    private static final String PREFS_NAME = "hook_diagnostics";

    public static final class ProcessSnapshot {
        public final String id;
        public final String packageName;
        public final String processName;
        public final String stage;
        public final String error;
        public final long loadedAt;
        public final long configLoadedAt;
        public final int configRevision;
        public final String configSource;
        public final String configPath;
        public final long reloadLatencyMs;
        public final int bootCount;

        ProcessSnapshot(String id, String packageName, String processName, String stage,
                        String error, long loadedAt, long configLoadedAt,
                        int configRevision, String configSource, String configPath,
                        long reloadLatencyMs, int bootCount) {
            this.id = id;
            this.packageName = packageName;
            this.processName = processName;
            this.stage = stage;
            this.error = error;
            this.loadedAt = loadedAt;
            this.configLoadedAt = configLoadedAt;
            this.configRevision = configRevision;
            this.configSource = configSource;
            this.configPath = configPath;
            this.reloadLatencyMs = reloadLatencyMs;
            this.bootCount = bootCount;
        }

        public boolean isCurrentBoot(Context context) {
            int current = currentBootCount(context);
            return bootCount != Integer.MIN_VALUE && (current < 0 || bootCount == current);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int currentBootCount(Context context) {
        if (context == null) return -1;
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static synchronized void reportProcess(Context context, String processId,
                                                  String packageName, String processName,
                                                  String stage, String error) {
        if (context == null) return;
        String prefix = processPrefix(processId);
        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putString(prefix + "package", safe(packageName))
                .putString(prefix + "process", safe(processName))
                .putString(prefix + "stage", safe(stage))
                .putString(prefix + "error", safe(error))
                .putLong(prefix + "loaded_at", now)
                .putInt(prefix + "boot", currentBootCount(context))
                .commit();
    }

    public static synchronized void reportConfigLoaded(Context context, String processId,
                                                       String packageName, String processName,
                                                       int revision, String source, String path,
                                                       long reloadLatencyMs, String error) {
        if (context == null) return;
        String prefix = processPrefix(processId);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(prefix + "package", safe(packageName))
                .putString(prefix + "process", safe(processName))
                .putInt(prefix + "config_revision", revision)
                .putString(prefix + "config_source", safe(source))
                .putString(prefix + "config_path", safe(path))
                .putLong(prefix + "reload_latency", reloadLatencyMs)
                .putLong(prefix + "config_at", now)
                .putInt(prefix + "boot", currentBootCount(context));
        if (TextUtils.isEmpty(error)) {
            editor.putString(prefix + "stage", "CONFIG_READY")
                    .putString(prefix + "error", "");
        } else {
            editor.putString(prefix + "stage", "CONFIG_ERROR")
                    .putString(prefix + "error", error);
        }
        editor.commit();
    }

    public static ProcessSnapshot getProcess(Context context, String processId) {
        if (context == null) {
            return new ProcessSnapshot(processId, "", "", "UNKNOWN", "", 0L, 0L,
                    -1, "", "", 0L, Integer.MIN_VALUE);
        }
        SharedPreferences sp = prefs(context);
        String prefix = processPrefix(processId);
        return new ProcessSnapshot(
                processId,
                sp.getString(prefix + "package", ""),
                sp.getString(prefix + "process", ""),
                sp.getString(prefix + "stage", "UNKNOWN"),
                sp.getString(prefix + "error", ""),
                sp.getLong(prefix + "loaded_at", 0L),
                sp.getLong(prefix + "config_at", 0L),
                sp.getInt(prefix + "config_revision", -1),
                sp.getString(prefix + "config_source", ""),
                sp.getString(prefix + "config_path", ""),
                sp.getLong(prefix + "reload_latency", 0L),
                sp.getInt(prefix + "boot", Integer.MIN_VALUE));
    }

    public static synchronized void setSupportStatus(Context context, String feature, String status) {
        if (context == null || TextUtils.isEmpty(feature)) return;
        String prefix = supportPrefix(feature);
        prefs(context).edit()
                .putString(prefix + "status", safe(status))
                .putLong(prefix + "time", System.currentTimeMillis())
                .putInt(prefix + "boot", currentBootCount(context))
                .commit();
    }

    public static String getSupportStatus(Context context, String feature) {
        if (context == null || TextUtils.isEmpty(feature)) return "UNKNOWN";
        SharedPreferences sp = prefs(context);
        String prefix = supportPrefix(feature);
        int storedBoot = sp.getInt(prefix + "boot", Integer.MIN_VALUE);
        int currentBoot = currentBootCount(context);
        if (storedBoot == Integer.MIN_VALUE || (currentBoot >= 0 && storedBoot != currentBoot)) {
            return "UNKNOWN";
        }
        String value = sp.getString(prefix + "status", "UNKNOWN");
        return TextUtils.isEmpty(value) ? "UNKNOWN" : value;
    }

    public static long getSupportUpdatedAt(Context context, String feature) {
        if (context == null || "UNKNOWN".equals(getSupportStatus(context, feature))) return 0L;
        return prefs(context).getLong(supportPrefix(feature) + "time", 0L);
    }


    public static synchronized void mergeSnapshot(Context context, Bundle snapshot) {
        if (context == null || snapshot == null) return;
        java.util.ArrayList<String> features = snapshot.getStringArrayList(BridgeContract.EXTRA_FEATURES);
        if (features == null || features.isEmpty()) return;
        SharedPreferences sp = prefs(context);
        SharedPreferences.Editor editor = sp.edit();
        for (String featureId : features) {
            Bundle feature = snapshot.getBundle("feature." + featureId);
            if (feature == null) continue;
            String prefix = statsPrefix(featureId);
            int observed = sp.getInt(prefix + "observed", 0)
                    + feature.getInt("observed_delta", 0);
            int blocked = sp.getInt(prefix + "blocked", 0)
                    + feature.getInt("blocked_delta", 0);
            int failOpen = sp.getInt(prefix + "fail_open", 0)
                    + feature.getInt("fail_open_delta", 0);
            editor.putInt(prefix + "observed", observed)
                    .putInt(prefix + "blocked", blocked)
                    .putInt(prefix + "fail_open", failOpen);
            long incomingAt = feature.getLong("last_at", 0L);
            long currentAt = sp.getLong(prefix + "last_at", 0L);
            if (incomingAt >= currentAt) {
                editor.putLong(prefix + "last_at", incomingAt)
                        .putString(prefix + "last_pkg", feature.getString("last_pkg", ""))
                        .putString(prefix + "last_process", feature.getString("last_process", ""))
                        .putString(prefix + "last_scene", feature.getString("last_scene", ""))
                        .putString(prefix + "last_reason", feature.getString("last_reason", ""))
                        .putString(prefix + "last_result", feature.getString("last_result", ""));
            }
        }
        editor.commit();
    }

    public static synchronized void recordEvent(Context context, HookEvent event, String result) {
        if (context == null || event == null || TextUtils.isEmpty(event.feature)) return;
        SharedPreferences sp = prefs(context);
        String prefix = statsPrefix(event.feature);
        int observed = sp.getInt(prefix + "observed", 0) + 1;
        int blocked = sp.getInt(prefix + "blocked", 0);
        if ("blocked".equals(result)) blocked++;
        long now = System.currentTimeMillis();
        sp.edit()
                .putInt(prefix + "observed", observed)
                .putInt(prefix + "blocked", blocked)
                .putLong(prefix + "last_at", now)
                .putString(prefix + "last_pkg", safe(event.packageName))
                .putString(prefix + "last_process", safe(event.processName))
                .putString(prefix + "last_scene", safe(event.scene))
                .putString(prefix + "last_reason", String.valueOf(event.code))
                .putString(prefix + "last_result", safe(result))
                .putString("last.feature", event.feature)
                .putString("last.package", safe(event.packageName))
                .putString("last.result", safe(result))
                .putLong("last.time", now)
                .commit();
    }

    public static int getStatInt(Context context, String feature, String suffix) {
        if (context == null) return 0;
        return prefs(context).getInt(statsPrefix(feature) + suffix, 0);
    }

    public static String getStatString(Context context, String feature, String suffix) {
        if (context == null) return "";
        return prefs(context).getString(statsPrefix(feature) + suffix, "");
    }

    public static long getStatLong(Context context, String feature, String suffix) {
        if (context == null) return 0L;
        return prefs(context).getLong(statsPrefix(feature) + suffix, 0L);
    }

    public static synchronized void resetStats(Context context, Iterable<String> features) {
        if (context == null) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String feature : features) {
            String prefix = statsPrefix(feature);
            editor.remove(prefix + "observed")
                    .remove(prefix + "blocked")
                    .remove(prefix + "fail_open")
                    .remove(prefix + "last_at")
                    .remove(prefix + "last_pkg")
                    .remove(prefix + "last_process")
                    .remove(prefix + "last_scene")
                    .remove(prefix + "last_reason")
                    .remove(prefix + "last_result");
        }
        editor.remove("last.feature").remove("last.package")
                .remove("last.result").remove("last.time").commit();
    }

    private static String processPrefix(String processId) {
        return "process." + BridgeContract.sanitize(processId) + ".";
    }

    private static String supportPrefix(String feature) {
        return "support." + BridgeContract.sanitize(feature) + ".";
    }

    private static String statsPrefix(String feature) {
        return "stats." + BridgeContract.sanitize(feature) + ".";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
