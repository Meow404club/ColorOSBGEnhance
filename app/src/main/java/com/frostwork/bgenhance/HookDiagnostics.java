package com.frostwork.bgenhance;

import android.content.ContentResolver;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBootId;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireEncoder;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWriteBatch;
import com.frostwork.bgenhance.diagnostics.NormalizedDiagnosticsEvent;
import com.frostwork.bgenhance.diagnostics.RawHookHit;
import com.frostwork.bgenhance.residency.decision.DecisionTrace;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.IdentityResolutionStatus;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking, epoch-aware diagnostics sender used by hooked processes.
 * Hook callbacks only update bounded memory; Provider IPC and serialization run on one worker.
 */
public final class HookDiagnostics {
    private HookDiagnostics() {}

    private static final long FLUSH_INTERVAL_MS = 5_000L;
    private static final int FLUSH_EVENT_THRESHOLD = 32;
    private static final int MAX_PENDING_EVENTS = 512;
    private static final int MAX_STICKY_STATES = 64;
    private static final Object OUTBOX_LOCK = new Object();
    private static final ArrayDeque<PendingEvent> EVENT_OUTBOX = new ArrayDeque<>();
    private static final Map<String, String> FEATURE_STATUSES = new ConcurrentHashMap<>();
    private static final Map<String, Long> FEATURE_UPDATED_AT = new ConcurrentHashMap<>();
    private static final Map<String, String> PROCESS_STAGES = new ConcurrentHashMap<>();
    private static final Map<String, String> PROCESS_ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PROCESS_UPDATED_AT = new ConcurrentHashMap<>();
    private static final AtomicInteger PENDING_EVENTS = new AtomicInteger();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong STICKY_GENERATION = new AtomicLong();
    private static final AtomicLong REPORTED_STICKY_GENERATION = new AtomicLong(-1L);
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(
            1, runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceDiagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<ConfigReportState> CURRENT_CONFIG_REPORT =
            new AtomicReference<>(new ConfigReportState(
                    Integer.MIN_VALUE, "", "", 0L, ""));
    private static final String BOOT_ID = DiagnosticsBootId.readCurrent();
    private static final String SENDER_INSTANCE_ID = createSenderInstanceId();

    private static volatile ConfigReportState sLastReportedConfig;
    private static volatile long sLastConfigReportAt;
    private static volatile long sConfigUpdatedAt;
    private static volatile long sEpoch = -1L;
    private static volatile long sNextBatchSequence;
    private static volatile InFlightBatch sInFlight;
    private static volatile String sTransportError = "";
    private static volatile long sLastTransportLogAt;

    static {
        EXECUTOR.setRemoveOnCancelPolicy(true);
        EXECUTOR.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        EXECUTOR.scheduleWithFixedDelay(
                HookDiagnostics::flushPendingFromPeriodicTask,
                FLUSH_INTERVAL_MS,
                FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public static void reportProcess(String stage, Throwable error) {
        Bundle current = HookConfig.processExtras();
        String processId = current.getString(BridgeContract.EXTRA_PROCESS_ID, "unknown");
        PROCESS_STAGES.put(processId, stableId(stage, "UNKNOWN"));
        PROCESS_ERRORS.put(processId, errorText(error));
        PROCESS_UPDATED_AT.put(processId, System.currentTimeMillis());
        STICKY_GENERATION.incrementAndGet();
        scheduleFlush(0L);
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
        sConfigUpdatedAt = System.currentTimeMillis();
        STICKY_GENERATION.incrementAndGet();
        scheduleFlush(0L);
    }

    public static void reportFeature(String feature, String status) {
        String featureValue = stableId(feature, "");
        if (featureValue.isEmpty()) return;
        FEATURE_STATUSES.put(featureValue, stableId(status, "UNKNOWN"));
        FEATURE_UPDATED_AT.put(featureValue, System.currentTimeMillis());
        STICKY_GENERATION.incrementAndGet();
        scheduleFlush(0L);
    }

    public static void recordEvent(HookEvent event, String result) {
        recordEvent(event, result, null, true);
    }

    /** Updates bounded memory only while a system lock is held. */
    public static void recordEventFromLockedPath(HookEvent event, String result) {
        recordEvent(event, result, null, false);
    }

    public static void recordDecision(
            HookEvent event, String result, DecisionTrace trace) {
        recordEvent(event, result, trace, true);
    }

    public static void recordDecisionFromLockedPath(
            HookEvent event, String result, DecisionTrace trace) {
        recordEvent(event, result, trace, false);
    }

    private static void recordEvent(
            HookEvent event, String result, DecisionTrace trace,
            boolean scheduleImmediately) {
        if (event == null || stableId(event.feature, "").isEmpty()) return;
        RuntimeConfigSnapshot snapshot = HookConfig.runtimeSnapshot();
        PendingEvent pending = new PendingEvent(
                event,
                stableId(result, DiagnosticsResult.PASSED),
                trace,
                System.currentTimeMillis(),
                diagnosticRevision(trace == null
                        ? snapshot.configRevision() : trace.configRevision()),
                diagnosticRevision(trace == null
                        ? snapshot.policyRevision() : trace.policyRevision()),
                SENDER_INSTANCE_ID + ":" + EVENT_SEQUENCE.getAndIncrement());
        boolean accepted;
        synchronized (OUTBOX_LOCK) {
            accepted = EVENT_OUTBOX.size() < MAX_PENDING_EVENTS;
            if (accepted) {
                EVENT_OUTBOX.addLast(pending);
                PENDING_EVENTS.incrementAndGet();
            }
        }
        if (!accepted) {
            sTransportError = "diagnostics_queue_full";
            STICKY_GENERATION.incrementAndGet();
            return;
        }
        if (scheduleImmediately && PENDING_EVENTS.get() >= FLUSH_EVENT_THRESHOLD) {
            scheduleFlush(0L);
        } else if (scheduleImmediately) {
            scheduleFlush(FLUSH_INTERVAL_MS);
        }
    }

    private static void flushPendingFromPeriodicTask() {
        try {
            if (hasPendingData()) flushSnapshot();
        } catch (Throwable error) {
            logTransportFailure("periodic_flush", error);
        }
    }

    private static boolean hasPendingData() {
        return sInFlight != null
                || PENDING_EVENTS.get() > 0
                || STICKY_GENERATION.get() > REPORTED_STICKY_GENERATION.get();
    }

    private static void scheduleFlush(long delayMs) {
        if (!FLUSH_SCHEDULED.compareAndSet(false, true)) return;
        try {
            EXECUTOR.schedule(() -> {
                FLUSH_SCHEDULED.set(false);
                flushSnapshot();
                if (hasPendingData()) scheduleFlush(FLUSH_INTERVAL_MS);
            }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            FLUSH_SCHEDULED.set(false);
            logTransportFailure("schedule_flush", error);
        }
    }

    private static void flushSnapshot() {
        if (BOOT_ID.isEmpty()) {
            sTransportError = "boot_id_unavailable";
            return;
        }
        if (sEpoch < 0L && !refreshBridgeState()) return;
        InFlightBatch inFlight = sInFlight;
        if (inFlight == null) {
            inFlight = buildInFlight();
            sInFlight = inFlight;
        }
        if (inFlight == null) return;
        sendInFlight(inFlight);
    }

    private static InFlightBatch buildInFlight() {
        String processId = currentProcessId();
        long stickyGeneration = STICKY_GENERATION.get();
        boolean includeSticky = stickyGeneration > REPORTED_STICKY_GENERATION.get();
        List<DiagnosticsHookState> states = includeSticky
                ? stickyStates(processId) : Collections.emptyList();
        int eventCapacity = Math.max(0,
                (DiagnosticsWireParser.MAX_WIRE_ITEMS - states.size()) / 2);
        List<PendingEvent> pending = snapshotEvents(eventCapacity);
        if (pending.isEmpty() && states.isEmpty()) return null;

        DiagnosticsWriteBatch.Builder batch = DiagnosticsWriteBatch.builder()
                .wireIdentity(
                        DiagnosticsWireParser.WIRE_VERSION,
                        BOOT_ID,
                        processId,
                        SENDER_INSTANCE_ID,
                        sEpoch,
                        sNextBatchSequence)
                .countingMode(DiagnosticsWriteBatch.CountingMode.TYPED);
        for (PendingEvent value : pending) {
            EventPair pair = value.toPair(processId);
            batch.addRawHit(pair.rawHit);
            if (pair.event != null) batch.addEvent(pair.event);
        }
        for (DiagnosticsHookState state : states) batch.addHookState(state);
        DiagnosticsWriteBatch payload = batch.build();
        return new InFlightBatch(
                DiagnosticsWireEncoder.encodeTyped(payload),
                pending.size(),
                includeSticky ? stickyGeneration : -1L,
                payload.size(),
                sEpoch,
                sNextBatchSequence);
    }

    private static List<PendingEvent> snapshotEvents(int limit) {
        if (limit <= 0) return Collections.emptyList();
        ArrayList<PendingEvent> values = new ArrayList<>(Math.min(limit, PENDING_EVENTS.get()));
        synchronized (OUTBOX_LOCK) {
            for (PendingEvent event : EVENT_OUTBOX) {
                if (values.size() >= limit) break;
                values.add(event);
            }
        }
        return values;
    }

    private static void sendInFlight(InFlightBatch inFlight) {
        Bundle response = callProvider(BridgeContract.METHOD_REPORT_BATCH, inFlight.payload);
        if (response == null) return;
        String remoteBoot = response.getString(BridgeContract.EXTRA_BOOT_ID, "");
        long remoteEpoch = response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH, -1L);
        if (!BOOT_ID.equals(remoteBoot)) {
            sTransportError = "bridge_boot_mismatch";
            return;
        }
        if (remoteEpoch >= 0L && remoteEpoch != inFlight.epoch) {
            resetForEpoch(remoteEpoch);
            return;
        }
        boolean accepted = response.getBoolean(BridgeContract.EXTRA_OK, false)
                && response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS, -1)
                == inFlight.itemCount
                && response.getInt(BridgeContract.EXTRA_DROPPED_ITEMS, 0) == 0;
        if (!accepted) {
            sTransportError = stableId(
                    response.getString(BridgeContract.EXTRA_ERROR, "bridge_rejected"),
                    "bridge_rejected");
            return;
        }
        commitEvents(inFlight.eventCount);
        if (inFlight.stickyGeneration >= 0L) {
            REPORTED_STICKY_GENERATION.accumulateAndGet(
                    inFlight.stickyGeneration, Math::max);
        }
        sNextBatchSequence = inFlight.sequence + 1L;
        sInFlight = null;
        sTransportError = "";
    }

    private static void commitEvents(int count) {
        if (count <= 0) return;
        synchronized (OUTBOX_LOCK) {
            int removed = 0;
            while (removed < count && !EVENT_OUTBOX.isEmpty()) {
                EVENT_OUTBOX.removeFirst();
                removed++;
            }
            PENDING_EVENTS.addAndGet(-removed);
            if (PENDING_EVENTS.get() < 0) PENDING_EVENTS.set(0);
        }
    }

    private static boolean refreshBridgeState() {
        Bundle response = callProvider(BridgeContract.METHOD_PING, null);
        if (response == null) return false;
        if (response.getInt(BridgeContract.EXTRA_BRIDGE_VERSION, -1)
                != BridgeContract.BRIDGE_VERSION) {
            sTransportError = "bridge_version_mismatch";
            return false;
        }
        String remoteBoot = response.getString(BridgeContract.EXTRA_BOOT_ID, "");
        long remoteEpoch = response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH, -1L);
        if (!response.getBoolean(BridgeContract.EXTRA_OK, false)
                || !BOOT_ID.equals(remoteBoot)
                || remoteEpoch < 0L) {
            sTransportError = stableId(
                    response.getString(BridgeContract.EXTRA_ERROR, "bridge_not_ready"),
                    "bridge_not_ready");
            return false;
        }
        if (sEpoch >= 0L && sEpoch != remoteEpoch) {
            resetForEpoch(remoteEpoch);
        } else {
            sEpoch = remoteEpoch;
        }
        return true;
    }

    private static void resetForEpoch(long epoch) {
        synchronized (OUTBOX_LOCK) {
            EVENT_OUTBOX.clear();
            PENDING_EVENTS.set(0);
        }
        sInFlight = null;
        sEpoch = Math.max(0L, epoch);
        sNextBatchSequence = 0L;
        REPORTED_STICKY_GENERATION.set(-1L);
        sTransportError = "";
    }

    private static Bundle callProvider(String method, Bundle extras) {
        ContentResolver resolver = HookConfig.resolver();
        if (resolver == null) {
            sTransportError = "diagnostics_context_unavailable";
            return null;
        }
        try {
            return resolver.call(BridgeContract.URI, method, null, extras);
        } catch (Throwable error) {
            sTransportError = "diagnostics_bridge_unavailable";
            logTransportFailure(method, error);
            return null;
        }
    }

    private static List<DiagnosticsHookState> stickyStates(String processId) {
        ArrayList<DiagnosticsHookState> values = new ArrayList<>();
        String stage = PROCESS_STAGES.get(processId);
        if (stage != null) {
            values.add(DiagnosticsHookState.builder()
                    .processId(processId)
                    .hookTargetId(DiagnosticsTargetIds.RUNTIME_PROCESS)
                    .installStatus(stage)
                    .bootId(BOOT_ID)
                    .lastError(PROCESS_ERRORS.getOrDefault(processId, sTransportError))
                    .updatedAt(PROCESS_UPDATED_AT.getOrDefault(
                            processId, System.currentTimeMillis()))
                    .build());
        }
        ConfigReportState config = CURRENT_CONFIG_REPORT.get();
        if (config.revision != Integer.MIN_VALUE) {
            values.add(DiagnosticsHookState.builder()
                    .processId(processId)
                    .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                    .installStatus(config.error.isEmpty() ? "CONFIG_READY" : "CONFIG_ERROR")
                    .bootId(BOOT_ID)
                    .configRevision(config.revision)
                    .lastError(config.error)
                    .updatedAt(sConfigUpdatedAt > 0L
                            ? sConfigUpdatedAt : System.currentTimeMillis())
                    .build());
        }
        ArrayList<String> targets = new ArrayList<>(FEATURE_STATUSES.keySet());
        Collections.sort(targets);
        for (String target : targets) {
            if (values.size() >= MAX_STICKY_STATES) break;
            values.add(DiagnosticsHookState.builder()
                    .processId(processId)
                    .featureId(featureForHookTarget(target))
                    .hookTargetId(target)
                    .installStatus(FEATURE_STATUSES.getOrDefault(target, "UNKNOWN"))
                    .bootId(BOOT_ID)
                    .configRevision(HookConfig.revision())
                    .lastError("")
                    .updatedAt(FEATURE_UPDATED_AT.getOrDefault(
                            target, System.currentTimeMillis()))
                    .build());
        }
        return values;
    }

    private static String currentProcessId() {
        return stableId(HookConfig.processExtras().getString(
                BridgeContract.EXTRA_PROCESS_ID, "unknown"), "unknown");
    }

    private static String featureForHookTarget(String target) {
        if (HookTargetIds.ATHENA_PERIODIC.equals(target)) return FeatureIds.ATHENA_PERIODIC;
        if (HookTargetIds.OGUARD_KILL.equals(target)) return FeatureIds.OGUARD_KILL;
        if (HookTargetIds.OGUARD_FORCE_STOP.equals(target)) return FeatureIds.OGUARD_FORCE_STOP;
        if (HookTargetIds.TOURNAMENT.equals(target)) return FeatureIds.TOURNAMENT;
        if (HookTargetIds.OSENSE_PROCESS_KILL.equals(target)) return FeatureIds.OSENSE_PER_PROCESS;
        if (HookTargetIds.HANS_FREEZE_DISPATCH.equals(target)
                || HookTargetIds.HANS_FAST_FREEZE_DISPATCH.equals(target)) return FeatureIds.HANS;
        if (HookTargetIds.HANS_WAKE_DISPATCH.equals(target)
                || HookTargetIds.ALARM_WAKE_DELIVERY.equals(target)) return FeatureIds.HANS_WAKE;
        if (HookTargetIds.HANS_RESOURCE_DISPATCH.equals(target)) return FeatureIds.HANS_RESOURCE;
        if (HookTargetIds.HANS_M_TO_F_TIMING.equals(target)) return FeatureIds.HANS_REFREEZE;
        if (HookTargetIds.HANS_ACTIVE_RECOVERY.equals(target)) return FeatureIds.HANS_RECOVERY;
        return "";
    }

    private static String hookTargetFor(HookEvent event, DecisionTrace trace) {
        if (trace != null && !stableId(trace.hookTargetId(), "").isEmpty()) {
            return stableId(trace.hookTargetId(), "unmapped_event");
        }
        String feature = event.feature;
        if (FeatureIds.ATHENA_PERIODIC.equals(feature)) return HookTargetIds.ATHENA_PERIODIC;
        if (FeatureIds.OGUARD_KILL.equals(feature)) return HookTargetIds.OGUARD_KILL;
        if (FeatureIds.OGUARD_FORCE_STOP.equals(feature)) return HookTargetIds.OGUARD_FORCE_STOP;
        if (FeatureIds.FAST_CAMERA_START.equals(feature)
                || FeatureIds.FAST_CAMERA_OPERATION.equals(feature)
                || FeatureIds.FAST_GAME_START.equals(feature)
                || FeatureIds.FAST_AI.equals(feature)
                || FeatureIds.FAST_CLEAR_OTHER.equals(feature)
                || FeatureIds.ATHENA_OTHER.equals(feature)) {
            return HookTargetIds.FAST_CLEAR_DISPATCH;
        }
        if (FeatureIds.TOURNAMENT.equals(feature)) return HookTargetIds.TOURNAMENT;
        if (FeatureIds.MEMORY_FORCE.equals(feature)
                || FeatureIds.MEMORY_OVER.equals(feature)
                || FeatureIds.MEMORY_GUARD_OTHER.equals(feature)) {
            return HookTargetIds.MEMORY_GUARD_DISPATCH;
        }
        if (FeatureIds.NIRVANA.equals(feature)
                || FeatureIds.CAMERA_LOW_FREE.equals(feature)
                || FeatureIds.GAME_LOW_FREE.equals(feature)) {
            return HookTargetIds.OSENSE_GROUP_CLEAN;
        }
        if (FeatureIds.OSENSE_PER_PROCESS.equals(feature)) {
            return HookTargetIds.OSENSE_PROCESS_KILL;
        }
        if (FeatureIds.HANS.equals(feature)) {
            return event.scene.contains("cgroup_v2")
                    ? HookTargetIds.HANS_FAST_FREEZE_DISPATCH
                    : HookTargetIds.HANS_FREEZE_DISPATCH;
        }
        if (FeatureIds.HANS_WAKE.equals(feature)) {
            return "alarm_delivery".equals(event.scene)
                    ? HookTargetIds.ALARM_WAKE_DELIVERY
                    : HookTargetIds.HANS_WAKE_DISPATCH;
        }
        if (FeatureIds.HANS_RESOURCE.equals(feature)) return HookTargetIds.HANS_RESOURCE_DISPATCH;
        if (FeatureIds.HANS_REFREEZE.equals(feature)) return HookTargetIds.HANS_M_TO_F_TIMING;
        if (FeatureIds.HANS_RECOVERY.equals(feature)) return HookTargetIds.HANS_ACTIVE_RECOVERY;
        return "unmapped_event";
    }

    private static String behaviorFor(String feature) {
        if (FeatureIds.HANS.equals(feature)
                || FeatureIds.SUPER_FREEZE.equals(feature)
                || FeatureIds.ANDROID_FREEZE.equals(feature)) return "FREEZE";
        if (FeatureIds.HANS_WAKE.equals(feature)) return "WAKE";
        if (FeatureIds.HANS_RESOURCE.equals(feature)) return "RESOURCE";
        if (FeatureIds.HANS_REFREEZE.equals(feature)) return "REFREEZE";
        if (FeatureIds.HANS_RECOVERY.equals(feature)) return "RECOVERY";
        return "KILL";
    }

    static String sourceFor(HookEvent event) {
        if (FeatureIds.HANS.equals(event.feature)) {
            FreezeSource[] values = FreezeSource.values();
            if (event.code >= 0 && event.code < values.length) return values[event.code].name();
        }
        if (FeatureIds.HANS_WAKE.equals(event.feature)) {
            WakeSource[] values = WakeSource.values();
            if (event.code >= 0 && event.code < values.length) return values[event.code].name();
        }
        if (FeatureIds.HANS_RESOURCE.equals(event.feature)) {
            if (event.code == 100) return "NETWORK";
            ResourceType[] values = ResourceType.values();
            if (event.code >= 1 && event.code <= values.length) {
                return values[event.code - 1].name();
            }
        }
        if (FeatureIds.HANS_REFREEZE.equals(event.feature)) {
            WakeSource[] values = WakeSource.values();
            if (event.code >= 1 && event.code <= values.length) {
                return values[event.code - 1].name();
            }
        }
        return "";
    }

    private static String decisionFor(String result, DecisionTrace trace) {
        if (trace != null) return trace.reason().name();
        if (DiagnosticsResult.BLOCKED.equals(result)) return "BLOCK_ACTION";
        if (DiagnosticsResult.THROTTLE_BLOCKED.equals(result)) return "THROTTLE_BLOCK";
        if (DiagnosticsResult.THROTTLE_ALLOWED.equals(result)) return "THROTTLE_ALLOW";
        if (DiagnosticsResult.OBSERVED.equals(result)) return "DRY_RUN";
        if (DiagnosticsResult.CANDIDATE_FILTERED.equals(result)) return "FILTER_CANDIDATE";
        if (DiagnosticsResult.RECOVERED.equals(result)) return "RECOVER";
        if (DiagnosticsResult.NO_ACTION_NEEDED.equals(result)) return "NO_ACTION";
        if (DiagnosticsResult.SAFETY_ABORTED.equals(result)) return "SAFETY_ABORT";
        if (DiagnosticsResult.countsAsFailOpen(result)) return "FAIL_OPEN";
        return "ALLOW_SYSTEM";
    }

    private static boolean identityResolved(DecisionTrace trace, HookEvent event) {
        if (trace == null) return event.uid >= 0 && !event.packageName.isEmpty();
        IdentityResolutionStatus status = trace.identity().status();
        return status == IdentityResolutionStatus.EXACT_PACKAGE
                || status == IdentityResolutionStatus.UID_SINGLE_RULE
                || status == IdentityResolutionStatus.SHARED_UID_SAME_POLICY;
    }

    private static String modeFor(DecisionTrace trace) {
        if (trace == null) return "";
        return trace.identity().policy().map(value -> value.mode().name()).orElse("");
    }

    private static int diagnosticRevision(long revision) {
        if (revision < 0L) return -1;
        return revision > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) revision;
    }

    private static String stableId(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback == null ? "" : fallback;
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '_' || current == '.' || current == '-') {
                sanitized.append(current);
            } else {
                sanitized.append('_');
            }
        }
        return sanitized.length() == 0
                ? (fallback == null ? "" : fallback)
                : sanitized.toString();
    }

    private static String createSenderInstanceId() {
        int processId = 0;
        try {
            processId = Math.max(0, Process.myPid());
        } catch (Throwable ignored) {
            // Plain JVM tests and early platform startup may not expose android.os.Process.
        }
        String nonce;
        try {
            nonce = UUID.randomUUID().toString();
        } catch (Throwable ignored) {
            nonce = Long.toHexString(System.nanoTime());
        }
        return "p" + processId + "-" + nonce;
    }

    private static String errorText(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ":" + message);
    }

    private static void logTransportFailure(String operation, Throwable error) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastTransportLogAt < 60_000L) return;
        sLastTransportLogAt = now;
        Log.w(ModuleSettings.TAG, "diagnostics transport failed operation=" + operation, error);
    }

    private static final class PendingEvent {
        final HookEvent event;
        final String result;
        final DecisionTrace trace;
        final long timestamp;
        final int configRevision;
        final int policyRevision;
        final String token;

        PendingEvent(
                HookEvent event, String result, DecisionTrace trace, long timestamp,
                int configRevision, int policyRevision, String token) {
            this.event = event;
            this.result = result;
            this.trace = trace;
            this.timestamp = timestamp;
            this.configRevision = configRevision;
            this.policyRevision = policyRevision;
            this.token = token;
        }

        EventPair toPair(String processId) {
            String hookTarget = hookTargetFor(event, trace);
            boolean resolved = identityResolved(trace, event);
            String failOpen = DiagnosticsResult.countsAsFailOpen(result)
                    ? (trace == null ? result : trace.reason().name()) : "";
            RawHookHit raw = RawHookHit.builder()
                    .timestampMillis(timestamp)
                    .bootId(BOOT_ID)
                    .processId(processId)
                    .featureId(event.feature)
                    .hookTargetId(hookTarget)
                    .configRevision(configRevision)
                    .policyRevision(policyRevision)
                    .parsed(true)
                    .identityResolved(resolved)
                    .errorCode(failOpen)
                    .build();
            NormalizedDiagnosticsEvent normalized = NormalizedDiagnosticsEvent.builder()
                    .timestampMillis(timestamp)
                    .bootId(BOOT_ID)
                    .processId(processId)
                    .featureId(event.feature)
                    .hookTargetId(hookTarget)
                    .behavior(behaviorFor(event.feature))
                    .source(sourceFor(event))
                    .packageName(event.packageName)
                    .uid(event.uid)
                    .scene(event.scene)
                    .reasonCode(event.code)
                    .configRevision(configRevision)
                    .policyRevision(policyRevision)
                    .mode(modeFor(trace))
                    .decision(decisionFor(result, trace))
                    .result(result)
                    .failOpenReason(failOpen)
                    .rawHitCount(1)
                    .identityResolved(resolved)
                    .eventToken(token)
                    .build();
            return new EventPair(raw, normalized.isStorable() ? normalized : null);
        }
    }

    private static final class EventPair {
        final RawHookHit rawHit;
        final NormalizedDiagnosticsEvent event;

        EventPair(RawHookHit rawHit, NormalizedDiagnosticsEvent event) {
            this.rawHit = rawHit;
            this.event = event;
        }
    }

    private static final class InFlightBatch {
        final Bundle payload;
        final int eventCount;
        final long stickyGeneration;
        final int itemCount;
        final long epoch;
        final long sequence;

        InFlightBatch(
                Bundle payload, int eventCount, long stickyGeneration,
                int itemCount, long epoch, long sequence) {
            this.payload = payload;
            this.eventCount = eventCount;
            this.stickyGeneration = stickyGeneration;
            this.itemCount = itemCount;
            this.epoch = epoch;
            this.sequence = sequence;
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
