package com.frostwork.bgenhance;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import com.frostwork.bgenhance.residency.config.GlobalFeatureConfig;
import com.frostwork.bgenhance.residency.config.RuntimeConfigCodec;
import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.runtime.HookRuntimeConfigState;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hook-side, read-only configuration snapshot backed by modern RemotePreferences.
 * Hook callbacks only read the immutable in-memory snapshot and always fail open when unavailable.
 */
public final class HookConfig {
    private HookConfig() {}

    private static final RuntimeConfigCodec RUNTIME_CONFIG_CODEC = new RuntimeConfigCodec();
    private static final ScheduledExecutorService CONFIG_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceHookConfig");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<HookRuntimeConfigState> STATE =
            new AtomicReference<>(HookRuntimeConfigState.unavailable(
                    "not_initialized", -1L, 0L));
    private static final AtomicBoolean RELOAD_ENQUEUED = new AtomicBoolean();
    private static final AtomicBoolean RELOAD_AGAIN = new AtomicBoolean();
    private static final Object RETRY_LOCK = new Object();
    private static volatile XposedModule sModule;
    private static volatile RemotePreferencesConnection sRemoteConnection;
    private static volatile ContentResolver sResolver;
    private static volatile Context sContext;
    private static volatile String sPackageName = "";
    private static volatile String sProcessName = "";
    private static volatile String sProcessId = "unknown";
    private static final long INITIAL_RETRY_DELAY_MS = 2_000L;
    private static final long MAX_RETRY_DELAY_MS = 60_000L;
    private static ScheduledFuture<?> sRetryFuture;
    private static long sRetryDelayMs = INITIAL_RETRY_DELAY_MS;

    private static final SharedPreferences.OnSharedPreferenceChangeListener REMOTE_LISTENER =
            HookConfig::onRemotePreferenceChanged;

    public static void initialize(XposedModule module, String packageName, String processName) {
        setProcess(packageName, processName);
        if (module == null) {
            markUnavailable("module_unavailable", 0L);
            return;
        }
        sModule = module;
        sRemoteConnection = new RemotePreferencesConnection(
                () -> module.getRemotePreferences(ModuleApplication.REMOTE_CONFIG_GROUP),
                REMOTE_LISTENER);
        try {
            requestReload(true);
        } catch (Throwable t) {
            markUnavailable(errorText(t), 0L);
            scheduleRetry();
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
        sContext = resolverContext;
        try {
            sResolver = resolverContext.getContentResolver();
        } catch (Throwable t) {
            Log.w(ModuleSettings.TAG, "diagnostics context attach failed", t);
        }
        HookResidencyRuntime.attach(resolverContext);
        reportCurrentConfig();
    }

    public static void ensureDiagnosticsContext() {
        ensureContext();
    }

    public static ContentResolver resolver() {
        ensureContext();
        return sResolver;
    }

    public static boolean isUserUnlocked() {
        ensureContext();
        Context context = sContext;
        if (context == null) return false;
        try {
            UserManager userManager = context.getSystemService(UserManager.class);
            return userManager != null && userManager.isUserUnlocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isReady() {
        return STATE.get().available();
    }

    public static int revision() {
        return diagnosticRevision(STATE.get().revision());
    }

    public static String error() {
        return STATE.get().error();
    }

    public static boolean getBoolean(String key) {
        HookRuntimeConfigState state = STATE.get();
        RuntimeConfigDocument document = state.document();
        if (!state.available() || document == null) return ModuleSettings.defaultFor(key);
        GlobalFeatureConfig global = document.global();
        if (ModuleSettings.KEY_MASTER_ENABLE.equals(key)) return global.masterEnabled();
        if (ModuleSettings.KEY_DRY_RUN.equals(key)) return global.dryRun();
        if (ModuleSettings.KEY_VERBOSE_LOG.equals(key)) return global.verboseDiagnostics();
        Boolean value = global.legacyFeatures().get(key);
        return value != null ? value : ModuleSettings.defaultFor(key);
    }

    public static RuntimeConfigSnapshot runtimeSnapshot() {
        return STATE.get().snapshot();
    }

    public static HookRuntimeConfigState state() {
        return STATE.get();
    }

    private static void onRemotePreferenceChanged(
            SharedPreferences preferences, String key) {
        if (preferences == null) return;
        HookRuntimeConfigState state = STATE.get();
        if (key == null
                || ModuleSettings.KEY_RUNTIME_CONFIG_JSON.equals(key)
                || !state.available()
                || "legacy_preferences".equals(state.source())) {
            requestReload(true);
        }
    }

    private static void requestReload(boolean resetBackoff) {
        if (sModule == null) return;
        if (resetBackoff) resetRetryBackoff();
        RELOAD_AGAIN.set(true);
        if (RELOAD_ENQUEUED.compareAndSet(false, true)) {
            CONFIG_EXECUTOR.execute(HookConfig::drainReloadRequests);
        }
    }

    private static void drainReloadRequests() {
        try {
            while (RELOAD_AGAIN.getAndSet(false)) {
                reloadConfiguration();
            }
        } finally {
            RELOAD_ENQUEUED.set(false);
            if (RELOAD_AGAIN.get()) requestReload(false);
            if (STATE.get().available()) {
                resetRetryBackoff();
            } else {
                scheduleRetry();
            }
        }
    }

    private static void scheduleRetry() {
        if (sModule == null) return;
        synchronized (RETRY_LOCK) {
            if (sRetryFuture != null && !sRetryFuture.isDone()) return;
            long delayMs = sRetryDelayMs;
            sRetryDelayMs = Math.min(MAX_RETRY_DELAY_MS, delayMs * 2L);
            sRetryFuture = CONFIG_EXECUTOR.schedule(() -> {
                synchronized (RETRY_LOCK) {
                    sRetryFuture = null;
                }
                requestReload(false);
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void resetRetryBackoff() {
        synchronized (RETRY_LOCK) {
            if (sRetryFuture != null) sRetryFuture.cancel(false);
            sRetryFuture = null;
            sRetryDelayMs = INITIAL_RETRY_DELAY_MS;
        }
    }

    private static void reloadConfiguration() {
        long started = SystemClock.elapsedRealtime();
        try {
            reloadSnapshot(ensureRemotePreferences(), started);
        } catch (Throwable t) {
            markUnavailable(errorText(t), SystemClock.elapsedRealtime() - started);
        }
    }

    private static SharedPreferences ensureRemotePreferences() {
        RemotePreferencesConnection connection = sRemoteConnection;
        if (connection == null) throw new IllegalStateException("module_unavailable");
        return connection.ensureConnected();
    }

    private static void reloadSnapshot(SharedPreferences preferences, long started) {
        if (preferences == null) {
            markUnavailable("remote_preferences_unavailable", 0L);
            return;
        }
        try {
            RuntimeConfigDocument document;
            String source;
            if (preferences.contains(ModuleSettings.KEY_RUNTIME_CONFIG_JSON)) {
                String encoded = preferences.getString(
                        ModuleSettings.KEY_RUNTIME_CONFIG_JSON, null);
                document = RUNTIME_CONFIG_CODEC.decode(encoded);
                source = "runtime_envelope";
            } else {
                document = readLegacyDocument(preferences);
                source = "legacy_preferences";
            }
            HookRuntimeConfigState next = HookRuntimeConfigState.available(
                    document, source, SystemClock.elapsedRealtime());
            STATE.set(next);
            HookResidencyRuntime.updateConfig(next);
            HookDiagnostics.reportConfig(diagnosticRevision(document.revision()), source,
                    ModuleApplication.REMOTE_CONFIG_GROUP,
                    SystemClock.elapsedRealtime() - started, "");
        } catch (Throwable t) {
            markUnavailable(errorText(t), SystemClock.elapsedRealtime() - started);
        }
    }

    private static void markUnavailable(String error, long latencyMs) {
        HookRuntimeConfigState previous = STATE.get();
        String message = error == null ? "unknown" : error;
        HookRuntimeConfigState unavailable = HookRuntimeConfigState.unavailable(
                message, previous.lastGoodRevision(), SystemClock.elapsedRealtime());
        STATE.set(unavailable);
        HookResidencyRuntime.updateConfig(unavailable);
        HookDiagnostics.reportConfig(diagnosticRevision(unavailable.lastGoodRevision()),
                "remote_preferences", ModuleApplication.REMOTE_CONFIG_GROUP,
                latencyMs, message);
        Log.w(ModuleSettings.TAG, "remote config unavailable; fail-open: " + message);
    }

    private static void reportCurrentConfig() {
        HookRuntimeConfigState state = STATE.get();
        HookDiagnostics.forceReportConfig(diagnosticRevision(state.revision()), state.source(),
                ModuleApplication.REMOTE_CONFIG_GROUP, 0L,
                state.available() ? "" : state.error());
    }

    private static RuntimeConfigDocument readLegacyDocument(SharedPreferences preferences) {
        if (!preferences.contains(ModuleSettings.KEY_CONFIG_REVISION)) {
            throw new IllegalStateException("remote_preferences_not_initialized");
        }
        int revision = preferences.getInt(ModuleSettings.KEY_CONFIG_REVISION, -1);
        if (revision < 0) throw new IllegalStateException("invalid config revision");

        Map<String, Boolean> featureFlags = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
            String key = entry.getKey();
            if (ModuleSettings.KEY_MASTER_ENABLE.equals(key)
                    || ModuleSettings.KEY_DRY_RUN.equals(key)
                    || ModuleSettings.KEY_VERBOSE_LOG.equals(key)) {
                continue;
            }
            featureFlags.put(key, preferences.getBoolean(key, entry.getValue()));
        }
        GlobalFeatureConfig global = new GlobalFeatureConfig(
                preferences.getBoolean(
                        ModuleSettings.KEY_MASTER_ENABLE,
                        ModuleSettings.defaultFor(ModuleSettings.KEY_MASTER_ENABLE)),
                preferences.getBoolean(
                        ModuleSettings.KEY_DRY_RUN,
                        ModuleSettings.defaultFor(ModuleSettings.KEY_DRY_RUN)),
                preferences.getBoolean(
                        ModuleSettings.KEY_VERBOSE_LOG,
                        ModuleSettings.defaultFor(ModuleSettings.KEY_VERBOSE_LOG)),
                preferences.getString(
                        ModuleSettings.KEY_ACTIVE_PRESET,
                        ModuleSettings.PRESET_BALANCED),
                featureFlags);
        return new RuntimeConfigDocument(
                RuntimeConfigDocument.DOCUMENT_TYPE,
                RuntimeConfigDocument.CURRENT_SCHEMA_VERSION,
                revision,
                0L,
                global,
                ResidencyPolicyDocument.empty());
    }

    private static int diagnosticRevision(long revision) {
        if (revision < 0L) return -1;
        return revision > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) revision;
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
