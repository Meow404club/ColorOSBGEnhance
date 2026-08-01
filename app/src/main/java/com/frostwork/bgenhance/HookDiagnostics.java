package com.frostwork.bgenhance;

import android.content.ContentResolver;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking diagnostics reporter used by hooked processes.
 * Install/process states are sticky and retried after the configuration bridge becomes available.
 */
public final class HookDiagnostics {
    private HookDiagnostics() {}

    private static final long FLUSH_INTERVAL_MS = 5_000L;
    private static final int FLUSH_EVENT_THRESHOLD = 32;
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, String> FEATURE_STATUSES = new ConcurrentHashMap<>();
    private static final Map<String, String> PROCESS_STAGES = new ConcurrentHashMap<>();
    private static final Map<String, String> PROCESS_ERRORS = new ConcurrentHashMap<>();
    private static final AtomicInteger PENDING_EVENTS = new AtomicInteger();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean STICKY_RETRY_SCHEDULED = new AtomicBoolean();
    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(
            1, runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceDiagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<ConfigReportState> CURRENT_CONFIG_REPORT =
            new AtomicReference<>(new ConfigReportState(
                    Integer.MIN_VALUE, "", "", 0L, ""));
    private static volatile ConfigReportState sLastReportedConfig;
    private static volatile long sLastConfigReportAt;

    static {
        EXECUTOR.setRemoveOnCancelPolicy(true);
        EXECUTOR.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public static void reportProcess(String stage, Throwable error) {
        Bundle current = HookConfig.processExtras();
        String processId = current.getString(BridgeContract.EXTRA_PROCESS_ID, "unknown");
        String stageValue = stage == null ? "UNKNOWN" : stage;
        String errorValue = errorText(error);
        PROCESS_STAGES.put(processId, stageValue);
        PROCESS_ERRORS.put(processId, errorValue);
        Bundle extras = processExtrasFor(processId);
        extras.putString(BridgeContract.EXTRA_STAGE, stageValue);
        extras.putString(BridgeContract.EXTRA_ERROR, errorValue);
        submitCall(BridgeContract.METHOD_REPORT_PROCESS, extras);
    }

    public static void reportConfig(int revision, String source, String path,
                                    long reloadLatencyMs, String error) {
        reportConfigInternal(revision, source, path, reloadLatencyMs, error, false);
    }

    public static void forceReportConfig(int revision, String source, String path,
                                         long reloadLatencyMs, String error) {
        reportConfigInternal(revision, source, path, reloadLatencyMs, error, true);
    }

    private static void reportConfigInternal(
            int revision, String source, String path,
            long reloadLatencyMs, String error, boolean force) {
        reportConfigInternal(new ConfigReportState(
                revision,
                source == null ? "" : source,
                path == null ? "" : path,
                reloadLatencyMs,
                error == null ? "" : error), force);
    }

    private static synchronized void reportConfigInternal(
            ConfigReportState report, boolean force) {
        CURRENT_CONFIG_REPORT.set(report);
        long now = SystemClock.elapsedRealtime();
        if (!force && report.sameConfigurationAs(sLastReportedConfig)
                && now - sLastConfigReportAt < 30_000L) return;
        sLastReportedConfig = report;
        sLastConfigReportAt = now;

        Set<String> processIds = new HashSet<>();
        processIds.add(HookConfig.processExtras().getString(
                BridgeContract.EXTRA_PROCESS_ID, "unknown"));
        processIds.addAll(PROCESS_STAGES.keySet());
        for (String feature : FEATURE_STATUSES.keySet()) {
            processIds.add(featureProcessId(feature));
        }
        for (String processId : processIds) {
            Bundle extras = processExtrasFor(processId);
            extras.putInt(BridgeContract.EXTRA_CONFIG_REVISION, report.revision);
            extras.putString(BridgeContract.EXTRA_CONFIG_SOURCE, report.source);
            extras.putString(BridgeContract.EXTRA_CONFIG_PATH, report.path);
            extras.putLong(BridgeContract.EXTRA_RELOAD_LATENCY, report.reloadLatencyMs);
            extras.putString(BridgeContract.EXTRA_ERROR, report.error);
            submitCall(BridgeContract.METHOD_REPORT_CONFIG, extras);
        }
        resendStickyStates();
    }

    public static void reportFeature(String feature, String status) {
        String featureValue = feature == null ? "" : feature;
        String statusValue = status == null ? "UNKNOWN" : status;
        if (featureValue.isEmpty()) return;
        FEATURE_STATUSES.put(featureValue, statusValue);
        submitFeature(featureValue, statusValue);
    }

    public static void recordEvent(HookEvent event, String result) {
        if (event == null || event.feature == null || event.feature.isEmpty()) return;
        Counter counter = COUNTERS.computeIfAbsent(event.feature, key -> new Counter());
        counter.observed.incrementAndGet();
        if ("blocked".equals(result)) counter.blocked.incrementAndGet();
        if ("config_unavailable".equals(result)) counter.failOpen.incrementAndGet();
        counter.last.set(new LastEvent(event, result, System.currentTimeMillis()));
        int pending = PENDING_EVENTS.incrementAndGet();
        scheduleFlush(pending >= FLUSH_EVENT_THRESHOLD ? 0L : FLUSH_INTERVAL_MS);
    }

    private static void resendStickyStates() {
        for (Map.Entry<String, String> entry : PROCESS_STAGES.entrySet()) {
            Bundle extras = processExtrasFor(entry.getKey());
            extras.putString(BridgeContract.EXTRA_STAGE, entry.getValue());
            extras.putString(BridgeContract.EXTRA_ERROR,
                    PROCESS_ERRORS.getOrDefault(entry.getKey(), ""));
            submitCall(BridgeContract.METHOD_REPORT_PROCESS, extras);
        }
        for (Map.Entry<String, String> entry : FEATURE_STATUSES.entrySet()) {
            submitFeature(entry.getKey(), entry.getValue());
        }
    }

    private static void submitFeature(String feature, String status) {
        String processId = featureProcessId(feature);
        Bundle extras = processExtrasFor(processId);
        extras.putString(BridgeContract.EXTRA_FEATURE, feature);
        extras.putString(BridgeContract.EXTRA_STATUS, status);
        submitCall(BridgeContract.METHOD_REPORT_FEATURE, extras);
    }

    private static String featureProcessId(String feature) {
        if (FeatureIds.ATHENA_PERIODIC.equals(feature)
                || "fast_clear_dispatch".equals(feature)
                || "memory_guard_dispatch".equals(feature)
                || FeatureIds.TOURNAMENT.equals(feature)) {
            return BridgeContract.PROCESS_ATHENA;
        }
        return BridgeContract.PROCESS_SYSTEM_SERVER;
    }

    private static Bundle processExtrasFor(String processId) {
        Bundle extras = HookConfig.processExtras();
        extras.putString(BridgeContract.EXTRA_PROCESS_ID, processId);
        if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(processId)) {
            extras.putString(BridgeContract.EXTRA_PACKAGE, "android");
            extras.putString(BridgeContract.EXTRA_PROCESS, "system_server");
        } else if (BridgeContract.PROCESS_ATHENA.equals(processId)) {
            extras.putString(BridgeContract.EXTRA_PACKAGE, "com.oplus.athena");
            String process = extras.getString(BridgeContract.EXTRA_PROCESS, "");
            if (process == null || !process.startsWith("com.oplus.athena")) {
                extras.putString(BridgeContract.EXTRA_PROCESS, "com.oplus.athena");
            }
        }
        return extras;
    }

    private static void scheduleFlush(long delayMs) {
        if (!FLUSH_SCHEDULED.compareAndSet(false, true)) return;
        EXECUTOR.schedule(() -> {
            FLUSH_SCHEDULED.set(false);
            flushSnapshot();
            if (PENDING_EVENTS.get() > 0) scheduleFlush(FLUSH_INTERVAL_MS);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void flushSnapshot() {
        Bundle extras = HookConfig.processExtras();
        ArrayList<String> features = new ArrayList<>();
        ArrayList<DrainedCounter> drained = new ArrayList<>();
        int drainedObserved = 0;
        for (Map.Entry<String, Counter> entry : COUNTERS.entrySet()) {
            Counter counter = entry.getValue();
            int observed = counter.observed.getAndSet(0);
            int blocked = counter.blocked.getAndSet(0);
            int failOpen = counter.failOpen.getAndSet(0);
            if (observed == 0 && blocked == 0 && failOpen == 0) continue;
            LastEvent last = counter.last.get();
            Bundle feature = new Bundle();
            feature.putInt("observed_delta", observed);
            feature.putInt("blocked_delta", blocked);
            feature.putInt("fail_open_delta", failOpen);
            if (last != null) {
                feature.putLong("last_at", last.timestamp);
                feature.putString("last_pkg", last.event.packageName);
                feature.putString("last_process", last.event.processName);
                feature.putString("last_scene", last.event.scene);
                feature.putString("last_reason", String.valueOf(last.event.code));
                feature.putString("last_result", last.result);
            }
            features.add(entry.getKey());
            extras.putBundle("feature." + entry.getKey(), feature);
            drained.add(new DrainedCounter(counter, observed, blocked, failOpen));
            drainedObserved += observed;
        }
        if (features.isEmpty()) return;
        extras.putStringArrayList(BridgeContract.EXTRA_FEATURES, features);
        boolean success = callProvider(BridgeContract.METHOD_REPORT_SNAPSHOT, extras);
        if (success) {
            PENDING_EVENTS.addAndGet(-drainedObserved);
            if (PENDING_EVENTS.get() < 0) PENDING_EVENTS.set(0);
        } else {
            for (DrainedCounter item : drained) item.restore();
        }
    }

    private static void submitCall(String method, Bundle extras) {
        EXECUTOR.execute(() -> {
            if (!callProvider(method, extras)) scheduleStickyRetry();
        });
    }

    private static void scheduleStickyRetry() {
        if (!STICKY_RETRY_SCHEDULED.compareAndSet(false, true)) return;
        EXECUTOR.schedule(() -> {
            STICKY_RETRY_SCHEDULED.set(false);
            reportConfigInternal(CURRENT_CONFIG_REPORT.get(), true);
            if (PENDING_EVENTS.get() > 0) scheduleFlush(0L);
        }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean callProvider(String method, Bundle extras) {
        ContentResolver resolver = HookConfig.resolver();
        if (resolver == null) return false;
        try {
            Bundle response = resolver.call(BridgeContract.URI, method, null, extras);
            return response != null && response.getBoolean(BridgeContract.EXTRA_OK, false);
        } catch (Throwable t) {
            Log.w(ModuleSettings.TAG, "diagnostics bridge failed method=" + method + ": " + t);
            return false;
        }
    }

    private static String errorText(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ":" + message);
    }

    private static final class Counter {
        final AtomicInteger observed = new AtomicInteger();
        final AtomicInteger blocked = new AtomicInteger();
        final AtomicInteger failOpen = new AtomicInteger();
        final AtomicReference<LastEvent> last = new AtomicReference<>();
    }

    private static final class LastEvent {
        final HookEvent event;
        final String result;
        final long timestamp;
        LastEvent(HookEvent event, String result, long timestamp) {
            this.event = event;
            this.result = result == null ? "passed" : result;
            this.timestamp = timestamp;
        }
    }

    private static final class DrainedCounter {
        final Counter counter;
        final int observed;
        final int blocked;
        final int failOpen;
        DrainedCounter(Counter counter, int observed, int blocked, int failOpen) {
            this.counter = counter;
            this.observed = observed;
            this.blocked = blocked;
            this.failOpen = failOpen;
        }
        void restore() {
            counter.observed.addAndGet(observed);
            counter.blocked.addAndGet(blocked);
            counter.failOpen.addAndGet(failOpen);
        }
    }

    static final class ConfigReportState {
        final int revision;
        final String source;
        final String path;
        final long reloadLatencyMs;
        final String error;

        ConfigReportState(
                int revision,
                String source,
                String path,
                long reloadLatencyMs,
                String error) {
            this.revision = revision;
            this.source = Objects.requireNonNull(source, "source");
            this.path = Objects.requireNonNull(path, "path");
            this.reloadLatencyMs = reloadLatencyMs;
            this.error = Objects.requireNonNull(error, "error");
        }

        boolean sameConfigurationAs(ConfigReportState that) {
            if (that == null) return false;
            return revision == that.revision
                    && source.equals(that.source)
                    && path.equals(that.path)
                    && error.equals(that.error);
        }
    }
}
