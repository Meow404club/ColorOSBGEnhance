package com.frostwork.bgenhance;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Hook-side, read-only configuration snapshot backed by modern RemotePreferences.
 * Hook callbacks only read the immutable in-memory snapshot and always fail open when unavailable.
 */
public final class HookConfig {
    private HookConfig() {}

    private static volatile Map<String, Boolean> sSnapshot = Collections.emptyMap();
    private static volatile SharedPreferences sRemotePreferences;
    private static volatile ContentResolver sResolver;
    private static volatile String sPackageName = "";
    private static volatile String sProcessName = "";
    private static volatile String sProcessId = "unknown";
    private static volatile int sRevision = -1;
    private static volatile boolean sConfigAvailable;
    private static volatile String sLastError = "not_initialized";
    private static volatile long sLastReloadAt;
    private static final long RELOAD_INTERVAL_MS = 2_000L;

    private static final SharedPreferences.OnSharedPreferenceChangeListener REMOTE_LISTENER =
            (sharedPreferences, key) -> reloadSnapshot(sharedPreferences);

    public static void initialize(XposedModule module, String packageName, String processName) {
        setProcess(packageName, processName);
        if (module == null) {
            markUnavailable("module_unavailable", 0L);
            return;
        }
        long started = SystemClock.elapsedRealtime();
        try {
            SharedPreferences remote = module.getRemotePreferences(
                    ModuleApplication.REMOTE_CONFIG_GROUP);
            sRemotePreferences = remote;
            remote.registerOnSharedPreferenceChangeListener(REMOTE_LISTENER);
            reloadSnapshot(remote, started);
        } catch (Throwable t) {
            markUnavailable(errorText(t), SystemClock.elapsedRealtime() - started);
        }
    }

    public static void setProcess(String packageName, String processName) {
        sPackageName = packageName == null ? "" : packageName;
        sProcessName = processName == null ? "" : processName;
        sProcessId = BridgeContract.processId(sPackageName, sProcessName);
    }

    public static void attach(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Context resolverContext = app != null ? app : context;
        try {
            sResolver = resolverContext.getContentResolver();
        } catch (Throwable t) {
            Log.w(ModuleSettings.TAG, "diagnostics context attach failed", t);
        }
        reportCurrentConfig();
    }

    public static void ensureDiagnosticsContext() {
        ensureContext();
    }

    public static ContentResolver resolver() {
        ensureContext();
        return sResolver;
    }

    public static boolean isReady() {
        if (!sConfigAvailable && sRemotePreferences != null) {
            long now = SystemClock.elapsedRealtime();
            if (now - sLastReloadAt >= RELOAD_INTERVAL_MS) {
                reloadSnapshot(sRemotePreferences);
            }
        }
        return sConfigAvailable;
    }

    public static int revision() {
        return sRevision;
    }

    public static String error() {
        return sLastError;
    }

    public static boolean getBoolean(String key) {
        Boolean value = sSnapshot.get(key);
        return value != null ? value : ModuleSettings.defaultFor(key);
    }

    private static void reloadSnapshot(SharedPreferences preferences) {
        reloadSnapshot(preferences, SystemClock.elapsedRealtime());
    }

    private static synchronized void reloadSnapshot(SharedPreferences preferences, long started) {
        sLastReloadAt = SystemClock.elapsedRealtime();
        if (preferences == null) {
            markUnavailable("remote_preferences_unavailable", 0L);
            return;
        }
        try {
            if (!preferences.contains(ModuleSettings.KEY_CONFIG_REVISION)) {
                markUnavailable("remote_preferences_not_initialized",
                        SystemClock.elapsedRealtime() - started);
                return;
            }
            int revision = preferences.getInt(ModuleSettings.KEY_CONFIG_REVISION, -1);
            if (revision < 0) throw new IllegalStateException("invalid config revision");
            Map<String, Boolean> next = new HashMap<>();
            for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
                next.put(entry.getKey(),
                        preferences.getBoolean(entry.getKey(), entry.getValue()));
            }
            sSnapshot = Collections.unmodifiableMap(next);
            sRevision = revision;
            sConfigAvailable = true;
            sLastError = "";
            HookDiagnostics.reportConfig(revision, "remote_preferences",
                    ModuleApplication.REMOTE_CONFIG_GROUP,
                    SystemClock.elapsedRealtime() - started, "");
        } catch (Throwable t) {
            markUnavailable(errorText(t), SystemClock.elapsedRealtime() - started);
        }
    }

    private static void markUnavailable(String error, long latencyMs) {
        sConfigAvailable = false;
        sLastError = error == null ? "unknown" : error;
        HookDiagnostics.reportConfig(sRevision, "remote_preferences",
                ModuleApplication.REMOTE_CONFIG_GROUP, latencyMs, sLastError);
        Log.w(ModuleSettings.TAG, "remote config unavailable; fail-open: " + sLastError);
    }

    private static void reportCurrentConfig() {
        HookDiagnostics.forceReportConfig(sRevision, "remote_preferences",
                ModuleApplication.REMOTE_CONFIG_GROUP, 0L,
                sConfigAvailable ? "" : sLastError);
    }

    static Bundle processExtras() {
        Bundle extras = new Bundle();
        extras.putString(BridgeContract.EXTRA_PROCESS_ID, sProcessId);
        extras.putString(BridgeContract.EXTRA_PACKAGE, sPackageName);
        extras.putString(BridgeContract.EXTRA_PROCESS, sProcessName);
        return extras;
    }

    private static void ensureContext() {
        if (sResolver != null) return;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                attach((Context) application);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread != null) {
                Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                Object context = getSystemContext.invoke(thread);
                if (context instanceof Context) attach((Context) context);
            }
        } catch (Throwable ignored) {}
    }

    private static String errorText(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + message);
    }
}
