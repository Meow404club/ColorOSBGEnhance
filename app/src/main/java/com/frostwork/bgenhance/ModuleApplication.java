package com.frostwork.bgenhance;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import com.frostwork.bgenhance.residency.config.GlobalFeatureConfig;
import com.frostwork.bgenhance.residency.config.RuntimeConfigCodec;
import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBootId;
import com.frostwork.bgenhance.diagnostics.DiagnosticsRetentionPolicy;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the modern Xposed service connection and mirrors local UI settings to RemotePreferences. */
public final class ModuleApplication extends Application
        implements XposedServiceHelper.OnServiceListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String REMOTE_CONFIG_GROUP = "config";

    private static final ScheduledExecutorService SYNC_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceRemoteConfig");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean SYNC_QUEUED = new AtomicBoolean();
    private static final RuntimeConfigCodec RUNTIME_CONFIG_CODEC = new RuntimeConfigCodec();
    private static final CopyOnWriteArraySet<StateListener> STATE_LISTENERS =
            new CopyOnWriteArraySet<>();
    private static final Object DIAGNOSTICS_LOCK = new Object();
    private static final AtomicLong DIAGNOSTICS_EPOCH = new AtomicLong(-1L);

    private static volatile XposedService sService;
    private static volatile int sServiceApi = -1;
    private static volatile int sSyncedRevision = -1;
    private static volatile String sSyncError = "service_not_connected";
    private static volatile ResidencyDiagnosticsBackend sDiagnosticsBackend;
    private static volatile String sDiagnosticsBootId = "";

    @Override
    public void onCreate() {
        super.onCreate();
        initializeDiagnostics(this);
        ModuleSettings.ensureDefaults(this);
        ModuleSettings.prefs(this).registerOnSharedPreferenceChangeListener(this);
        XposedServiceHelper.registerListener(this);
    }

    public interface StateListener {
        void onStateChanged();
    }

    public static void addStateListener(StateListener listener) {
        if (listener != null) STATE_LISTENERS.add(listener);
    }

    public static void removeStateListener(StateListener listener) {
        if (listener != null) STATE_LISTENERS.remove(listener);
    }

    private static void notifyStateChanged() {
        for (StateListener listener : STATE_LISTENERS) {
            try {
                listener.onStateChanged();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        sService = service;
        try {
            sServiceApi = service.getApiVersion();
            sSyncError = "";
        } catch (Throwable t) {
            sServiceApi = -1;
            sSyncError = errorText(t);
        }
        notifyStateChanged();
        requestConfigSync();
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        if (sService == service) {
            sService = null;
            sServiceApi = -1;
            sSyncError = "service_disconnected";
            notifyStateChanged();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null || ModuleSettings.KEY_CONFIG_REVISION.equals(key)
                || ModuleSettings.KEY_CONFIG_UPDATED_AT.equals(key)
                || ModuleSettings.KEY_RESIDENCY_POLICY_JSON.equals(key)
                || ModuleSettings.KEY_ACTIVE_PRESET.equals(key)
                || ModuleSettings.defaults().containsKey(key)) {
            requestConfigSync();
        }
    }

    public static void requestConfigSync(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        if (application instanceof ModuleApplication) {
            ((ModuleApplication) application).requestConfigSync();
        }
    }

    public static boolean isServiceConnected() {
        return sService != null;
    }

    public static int serviceApiVersion() {
        return sServiceApi;
    }

    public static int syncedRevision() {
        return sSyncedRevision;
    }

    public static String syncError() {
        return sSyncError;
    }

    public static boolean isConfigSynced(Context context) {
        return isServiceConnected()
                && sSyncedRevision >= ModuleSettings.getConfigRevision(context);
    }

    /** Returns the process-wide diagnostics backend, creating it at most once. */
    public static ResidencyDiagnosticsBackend initializeDiagnostics(Context context) {
        if (context == null) throw new IllegalArgumentException("diagnostics context required");
        ResidencyDiagnosticsBackend current = sDiagnosticsBackend;
        synchronized (DIAGNOSTICS_LOCK) {
            current = sDiagnosticsBackend;
            if (current == null) {
                Context application = context.getApplicationContext();
                if (application == null) application = context;
                String bootId = DiagnosticsBootId.readCurrent();
                current = ResidencyDiagnosticsBackend.open(
                        application, DiagnosticsRetentionPolicy.defaults(), bootId);
                sDiagnosticsBootId = bootId;
                sDiagnosticsBackend = current;
                current.currentEpochAsync().whenComplete((epoch, error) -> {
                    if (error == null && epoch != null && epoch >= 0L) {
                        DIAGNOSTICS_EPOCH.set(epoch);
                    } else {
                        DIAGNOSTICS_EPOCH.set(-1L);
                        if (error != null) {
                            Log.w(ModuleSettings.TAG,
                                    "diagnostics epoch initialization failed", error);
                        }
                    }
                });
            }
            return current;
        }
    }

    public static ResidencyDiagnosticsBackend diagnosticsBackend() {
        return sDiagnosticsBackend;
    }

    public static String diagnosticsBootId() {
        return sDiagnosticsBootId;
    }

    public static long diagnosticsEpoch() {
        return DIAGNOSTICS_EPOCH.get();
    }

    public static void publishDiagnosticsEpoch(long epoch) {
        if (epoch >= 0L) DIAGNOSTICS_EPOCH.set(epoch);
    }

    private void requestConfigSync() {
        scheduleConfigSync(0L);
    }

    private void scheduleConfigSync(long delayMs) {
        if (!SYNC_QUEUED.compareAndSet(false, true)) return;
        SYNC_EXECUTOR.schedule(() -> {
            try {
                syncConfigNow();
            } finally {
                SYNC_QUEUED.set(false);
                if (sService != null
                        && sSyncedRevision < ModuleSettings.getConfigRevision(this)) {
                    scheduleConfigSync(5_000L);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void syncConfigNow() {
        XposedService service = sService;
        if (service == null) {
            sSyncError = "service_not_connected";
            return;
        }
        ModuleSettings.ensureDefaults(this);
        RuntimeConfigDocument document;
        String encoded;
        try {
            document = ModuleSettings.getRuntimeConfig(this);
            encoded = RUNTIME_CONFIG_CODEC.encode(document);
            int revision = Math.toIntExact(document.revision());
            GlobalFeatureConfig global = document.global();
            SharedPreferences remote = service.getRemotePreferences(REMOTE_CONFIG_GROUP);
            SharedPreferences.Editor editor = remote.edit();
            for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
                String key = entry.getKey();
                boolean value;
                if (ModuleSettings.KEY_MASTER_ENABLE.equals(key)) {
                    value = global.masterEnabled();
                } else if (ModuleSettings.KEY_DRY_RUN.equals(key)) {
                    value = global.dryRun();
                } else if (ModuleSettings.KEY_VERBOSE_LOG.equals(key)) {
                    value = global.verboseDiagnostics();
                } else {
                    value = global.legacyFeatures().getOrDefault(key, entry.getValue());
                }
                editor.putBoolean(key, value);
            }
            editor.putString(ModuleSettings.KEY_ACTIVE_PRESET, global.activePreset());
            editor.putInt(ModuleSettings.KEY_CONFIG_REVISION, revision);
            editor.putString(ModuleSettings.KEY_RUNTIME_CONFIG_JSON, encoded);
            if (!editor.commit()) throw new IllegalStateException("remote preferences commit failed");

            String published = remote.getString(ModuleSettings.KEY_RUNTIME_CONFIG_JSON, null);
            RuntimeConfigDocument verified = RUNTIME_CONFIG_CODEC.decode(published);
            if (verified.revision() != document.revision()
                    || !encoded.equals(RUNTIME_CONFIG_CODEC.encode(verified))) {
                throw new IllegalStateException("remote runtime configuration verification failed");
            }
            sSyncedRevision = revision;
            sSyncError = "";
            notifyStateChanged();
        } catch (Throwable t) {
            sSyncError = errorText(t);
            notifyStateChanged();
            Log.w(ModuleSettings.TAG, "remote config sync failed", t);
        }
    }

    private static String errorText(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + message);
    }
}
