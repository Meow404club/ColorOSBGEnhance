package com.frostwork.bgenhance;

import android.app.AndroidAppHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hook-side configuration cache backed by the module provider.
 * Provider IPC always runs on a dedicated configuration thread; Hook callbacks only read memory.
 */
public final class HookConfig {
    private HookConfig() {}

    private static final long RELOAD_INTERVAL_MS = 2_000L;
    private static final Map<String, Boolean> CACHE = new HashMap<>();
    private static final AtomicBoolean RELOAD_QUEUED = new AtomicBoolean();
    private static final ThreadPoolExecutor CONFIG_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceConfig");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());

    private static volatile Context sContext;
    private static volatile ContentResolver sResolver;
    private static volatile String sPackageName = "";
    private static volatile String sProcessName = "";
    private static volatile String sProcessId = "unknown";
    private static volatile long sLastRequestAt;
    private static volatile int sRevision = -1;
    private static volatile boolean sConfigAvailable;
    private static volatile boolean sObserverRegistered;

    private static final ContentObserver CONFIG_OBSERVER = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            requestReload(true);
        }
    };

    public static void setProcess(String packageName, String processName) {
        sPackageName = packageName == null ? "" : packageName;
        sProcessName = processName == null ? "" : processName;
        sProcessId = BridgeContract.processId(sPackageName, sProcessName);
    }

    public static void attach(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        sContext = app != null ? app : context;
        try {
            sResolver = sContext.getContentResolver();
            registerObserver();
        } catch (Throwable t) {
            Log.w(ModuleSettings.TAG, "config context attach failed", t);
        }
        requestReload(true);
    }

    public static Context context() {
        ensureContext();
        return sContext;
    }

    public static ContentResolver resolver() {
        ensureContext();
        return sResolver;
    }

    public static boolean isReady() {
        requestReload(false);
        return sConfigAvailable;
    }

    public static int revision() {
        return sRevision;
    }

    public static boolean getBoolean(String key) {
        requestReload(false);
        synchronized (CACHE) {
            Boolean value = CACHE.get(key);
            return value != null ? value : ModuleSettings.defaultFor(key);
        }
    }

    private static void requestReload(boolean force) {
        ensureContext();
        if (sResolver == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - sLastRequestAt < RELOAD_INTERVAL_MS) return;
        if (!RELOAD_QUEUED.compareAndSet(false, true)) return;
        sLastRequestAt = now;
        CONFIG_EXECUTOR.execute(() -> {
            try {
                reloadNow();
            } finally {
                RELOAD_QUEUED.set(false);
            }
        });
    }

    private static void reloadNow() {
        long started = SystemClock.elapsedRealtime();
        ContentResolver resolver = resolver();
        if (resolver == null) {
            sConfigAvailable = false;
            HookDiagnostics.reportConfig(sRevision, "module_provider", BridgeContract.URI.toString(),
                    0L, "content resolver unavailable");
            return;
        }
        try {
            Bundle out = resolver.call(
                    BridgeContract.URI, BridgeContract.METHOD_GET_CONFIG, null, null);
            if (out == null || !out.getBoolean(BridgeContract.EXTRA_OK, false)) {
                throw new IllegalStateException(out == null ? "empty provider response"
                        : out.getString(BridgeContract.EXTRA_ERROR, "provider rejected config"));
            }
            int revision = out.getInt(BridgeContract.EXTRA_CONFIG_REVISION, -1);
            if (revision < 0) throw new IllegalStateException("invalid config revision");
            synchronized (CACHE) {
                for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
                    CACHE.put(entry.getKey(), out.getBoolean(entry.getKey(), entry.getValue()));
                }
            }
            sRevision = revision;
            sConfigAvailable = true;
            HookDiagnostics.reportConfig(revision, "module_provider", BridgeContract.URI.toString(),
                    SystemClock.elapsedRealtime() - started, "");
        } catch (Throwable t) {
            sConfigAvailable = false;
            HookDiagnostics.reportConfig(sRevision, "module_provider", BridgeContract.URI.toString(),
                    SystemClock.elapsedRealtime() - started,
                    t.getClass().getSimpleName() + ":" + safe(t.getMessage()));
            Log.w(ModuleSettings.TAG, "provider config unavailable; fail-open", t);
        }
    }

    static Bundle processExtras() {
        Bundle extras = new Bundle();
        extras.putString(BridgeContract.EXTRA_PROCESS_ID, sProcessId);
        extras.putString(BridgeContract.EXTRA_PACKAGE, sPackageName);
        extras.putString(BridgeContract.EXTRA_PROCESS, sProcessName);
        return extras;
    }

    private static void registerObserver() {
        if (sObserverRegistered || sResolver == null) return;
        synchronized (HookConfig.class) {
            if (sObserverRegistered || sResolver == null) return;
            try {
                sResolver.registerContentObserver(
                        BridgeContract.CONFIG_URI, false, CONFIG_OBSERVER);
                sObserverRegistered = true;
            } catch (Throwable t) {
                Log.w(ModuleSettings.TAG, "config change observer registration failed", t);
            }
        }
    }

    private static void ensureContext() {
        if (sResolver != null) return;
        try {
            Context app = AndroidAppHelper.currentApplication();
            if (app != null) {
                attach(app);
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
