package com.frostwork.bgenhance;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final CopyOnWriteArraySet<StateListener> STATE_LISTENERS =
            new CopyOnWriteArraySet<>();

    private static volatile XposedService sService;
    private static volatile int sServiceApi = -1;
    private static volatile int sSyncedRevision = -1;
    private static volatile String sSyncError = "service_not_connected";

    @Override
    public void onCreate() {
        super.onCreate();
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
        SharedPreferences local = ModuleSettings.prefs(this);
        int revision = ModuleSettings.getConfigRevision(this);
        try {
            SharedPreferences.Editor editor = service
                    .getRemotePreferences(REMOTE_CONFIG_GROUP).edit();
            for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
                editor.putBoolean(entry.getKey(),
                        local.getBoolean(entry.getKey(), entry.getValue()));
            }
            editor.putString(ModuleSettings.KEY_ACTIVE_PRESET,
                    local.getString(ModuleSettings.KEY_ACTIVE_PRESET,
                            ModuleSettings.PRESET_BALANCED));
            editor.putInt(ModuleSettings.KEY_CONFIG_REVISION, revision);
            if (!editor.commit()) throw new IllegalStateException("remote preferences commit failed");
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
