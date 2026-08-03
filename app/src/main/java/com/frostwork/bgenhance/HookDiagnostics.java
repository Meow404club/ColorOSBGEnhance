package com.frostwork.bgenhance;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBootId;
import com.frostwork.bgenhance.diagnostics.DiagnosticsEventDeduplicator;
import com.frostwork.bgenhance.diagnostics.DiagnosticsEventNormalizer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
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

    interface UserUnlockProbe {
        boolean isUserUnlocked();
    }

    private static final long PERIODIC_FLUSH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5L);
    private static final long FLUSH_PROBE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5L);
    private static final long INITIAL_TRANSPORT_RETRY_INTERVAL_MS =
            TimeUnit.SECONDS.toMillis(15L);
    private static final long MAX_TRANSPORT_RETRY_INTERVAL_MS =
            PERIODIC_FLUSH_INTERVAL_MS;
    private static final long DRAIN_CONTINUATION_DELAY_MS = 1_000L;
    private static final long MAX_DRAIN_START_WINDOW_MS = 2_000L;
    private static final int MAX_BATCHES_PER_DRAIN = 8;
    private static final int PENDING_QUEUE_HIGH_WATERMARK = 256;
    private static final int MAX_PENDING_RAW_HITS = 2_048;
    private static final int MAX_PENDING_NORMALIZED_EVENTS = 2_048;
    private static final int MAX_OUTBOX_CAS_ATTEMPTS = 32;
    private static final int MAX_STICKY_STATES = 64;
    private static final Object FLUSH_SCHEDULE_LOCK = new Object();
    private static final AtomicReference<OutboxState> OUTBOX =
            new AtomicReference<>(new OutboxState());
    private static final Map<String, String> FEATURE_STATUSES = new ConcurrentHashMap<>();
    private static final Map<String, Long> FEATURE_UPDATED_AT = new ConcurrentHashMap<>();
    private static final Map<String, String> PROCESS_STAGES = new ConcurrentHashMap<>();
    private static final Map<String, String> PROCESS_ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PROCESS_UPDATED_AT = new ConcurrentHashMap<>();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong STICKY_GENERATION = new AtomicLong();
    private static final AtomicLong REPORTED_STICKY_GENERATION = new AtomicLong(-1L);
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final AtomicInteger MODULE_UID = new AtomicInteger(-1);
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
    private static ScheduledFuture<?> sScheduledFlush;
    private static long sScheduledFlushDeadlineNanos = Long.MAX_VALUE;
    private static volatile String sTransportError = "";
    private static volatile long sTransportRetryDelayMs = INITIAL_TRANSPORT_RETRY_INTERVAL_MS;
    private static volatile long sTransportRetryNotBeforeNanos;
    private static volatile long sLastEventBatchAt = SystemClock.elapsedRealtime();
    private static volatile boolean sDrainContinuationPending;
    private static volatile long sLastTransportLogAt;
    private static volatile UserUnlockProbe sUserUnlockProbe = HookConfig::isUserUnlocked;

    static {
        EXECUTOR.setRemoveOnCancelPolicy(true);
        EXECUTOR.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduleFlush(FLUSH_PROBE_INTERVAL_MS);
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
        recordEvent(event, result, null);
    }

    /** Updates bounded memory only while a system lock is held. */
    public static void recordEventFromLockedPath(HookEvent event, String result) {
        recordEvent(event, result, null);
    }

    public static void recordDecision(
            HookEvent event, String result, DecisionTrace trace) {
        recordEvent(event, result, trace);
    }

    public static void recordDecisionFromLockedPath(
            HookEvent event, String result, DecisionTrace trace) {
        recordEvent(event, result, trace);
    }

    /** Records a diagnostic-only Hook invocation without creating a user-visible event. */
    public static void recordRawHookHit(
            String hookTargetId,
            boolean parsed,
            boolean identityResolved,
            String errorCode) {
        recordRawHookHitInternal(
                hookTargetId, parsed, identityResolved, errorCode);
    }

    public static void recordRawHookHit(
            String hookTargetId,
            int uid,
            String packageName,
            boolean parsed,
            boolean identityResolved,
            String errorCode) {
        if (isModuleIdentity(uid, packageName)) return;
        recordRawHookHitInternal(
                hookTargetId, parsed, identityResolved, errorCode);
    }

    private static void recordRawHookHitInternal(
            String hookTargetId,
            boolean parsed,
            boolean identityResolved,
            String errorCode) {
        try {
            String target = stableId(hookTargetId, "");
            if (target.isEmpty()) return;
            RevisionPair revisions = currentRevisions(null);
            RawHookHit rawHit = RawHookHit.builder()
                    .timestampMillis(System.currentTimeMillis())
                    .bootId(BOOT_ID)
                    .processId(safeCurrentProcessId())
                    .featureId(featureForHookTarget(target))
                    .hookTargetId(target)
                    .configRevision(revisions.configRevision)
                    .policyRevision(revisions.policyRevision)
                    .parsed(parsed)
                    .identityResolved(identityResolved)
                    .errorCode(errorCode)
                    .build();
            if (!OUTBOX.get().offerRawHit(rawHit)) markQueueUnavailable("raw");
        } catch (Throwable ignored) {
            markRecordFailure();
        }
    }

    static void cacheModuleUid(Context context) {
        if (context == null || MODULE_UID.get() >= 0) return;
        try {
            int uid = context.getPackageManager().getPackageUid(
                    BridgeContract.MODULE_PACKAGE, 0);
            if (uid >= 0) MODULE_UID.compareAndSet(-1, uid);
        } catch (Throwable ignored) {
            // Package identity remains available through exact package-name checks.
        }
    }

    private static void recordEvent(
            HookEvent event, String result, DecisionTrace trace) {
        try {
            if (event == null
                    || isModuleIdentity(event.uid, event.packageName)
                    || stableId(event.feature, "").isEmpty()) return;
            String stableResult = stableId(result, DiagnosticsResult.PASSED);
            long timestamp = System.currentTimeMillis();
            String processId = safeCurrentProcessId();
            RevisionPair revisions = currentRevisions(trace);
            String hookTarget = safeHookTargetFor(event, trace);
            boolean resolved = safeIdentityResolved(trace, event);
            String failOpen = DiagnosticsResult.countsAsFailOpen(stableResult)
                    ? safeFailOpenReason(stableResult, trace) : "";
            RawHookHit rawHit = RawHookHit.builder()
                    .timestampMillis(timestamp)
                    .bootId(BOOT_ID)
                    .processId(processId)
                    .featureId(event.feature)
                    .hookTargetId(hookTarget)
                    .configRevision(revisions.configRevision)
                    .policyRevision(revisions.policyRevision)
                    .parsed(true)
                    .identityResolved(resolved)
                    .errorCode(failOpen)
                    .build();
            OutboxState outbox = OUTBOX.get();
            if (!outbox.offerRawHit(rawHit)) markQueueUnavailable("raw");
            try {
                DiagnosticsEventNormalizer.DecisionData decision =
                        normalizedDecision(event, stableResult, trace, failOpen);
                DiagnosticsEventNormalizer.Result normalized =
                        DiagnosticsEventNormalizer.normalize(rawHit, decision);
                if (normalized.accepted()) {
                    OfferResult offered = outbox.offerNormalizedEvent(normalized.event);
                    if (offered == OfferResult.UNAVAILABLE) markQueueUnavailable("event");
                }
            } catch (Throwable ignored) {
                markRecordFailure();
            }
        } catch (Throwable ignored) {
            markRecordFailure();
        }
    }

    private static boolean isModuleIdentity(int uid, String packageName) {
        if (BridgeContract.MODULE_PACKAGE.equals(packageName)) return true;
        return (packageName == null || packageName.isEmpty())
                && uid >= 0
                && uid == MODULE_UID.get();
    }

    private static boolean hasPendingData() {
        OutboxState outbox = OUTBOX.get();
        return sInFlight != null
                || outbox.pendingItemCount() > 0
                || STICKY_GENERATION.get() > REPORTED_STICKY_GENERATION.get();
    }

    private static void scheduleFlush(long delayMs) {
        long boundedDelayMs = Math.max(0L, delayMs);
        synchronized (FLUSH_SCHEDULE_LOCK) {
            long nowNanos = System.nanoTime();
            long deadlineNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(boundedDelayMs);
            long retryNotBefore = sTransportRetryNotBeforeNanos;
            if (retryNotBefore > deadlineNanos) deadlineNanos = retryNotBefore;
            ScheduledFuture<?> scheduled = sScheduledFlush;
            if (scheduled != null && !scheduled.isDone()) {
                boolean violatesBackoff = retryNotBefore > nowNanos
                        && sScheduledFlushDeadlineNanos < retryNotBefore;
                if (!violatesBackoff && sScheduledFlushDeadlineNanos <= deadlineNanos) return;
                if (!scheduled.cancel(false)) return;
            }
            try {
                FLUSH_SCHEDULED.set(true);
                sScheduledFlushDeadlineNanos = deadlineNanos;
                sScheduledFlush = EXECUTOR.schedule(
                        HookDiagnostics::runScheduledFlush,
                        Math.max(0L, deadlineNanos - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
            } catch (Throwable error) {
                sScheduledFlush = null;
                sScheduledFlushDeadlineNanos = Long.MAX_VALUE;
                FLUSH_SCHEDULED.set(false);
                logTransportFailure("schedule_flush", error);
            }
        }
    }

    private static void runScheduledFlush() {
        synchronized (FLUSH_SCHEDULE_LOCK) {
            sScheduledFlush = null;
            sScheduledFlushDeadlineNanos = Long.MAX_VALUE;
            FLUSH_SCHEDULED.set(false);
        }
        try {
            long retryDelayMs = transportRetryDelayRemainingMs();
            if (retryDelayMs > 0L) {
                scheduleFlush(retryDelayMs);
                return;
            }
            if (!shouldAttemptProvider()) {
                scheduleFlush(FLUSH_PROBE_INTERVAL_MS);
                return;
            }
            sDrainContinuationPending = false;
            drainPendingBatchesAndReschedule();
        } catch (Throwable error) {
            logTransportFailure("scheduled_flush", error);
            scheduleTransportRetry();
        }
    }

    private static void drainPendingBatchesAndReschedule() {
        DrainResult result = new DrainResult();
        long startedAt = SystemClock.elapsedRealtime();
        // This bounds when another batch may start; each Provider call keeps its own timeout.
        for (int batch = 0; batch < MAX_BATCHES_PER_DRAIN && hasPendingData(); batch++) {
            if (batch > 0
                    && SystemClock.elapsedRealtime() - startedAt
                    >= MAX_DRAIN_START_WINDOW_MS) break;
            FlushOutcome outcome = flushSnapshot();
            if (outcome == FlushOutcome.PROGRESSED) {
                result.progressed = true;
                continue;
            }
            result.transportDeferred = outcome == FlushOutcome.TRANSPORT_DEFERRED;
            break;
        }
        if (result.transportDeferred && isUserUnlockDeferred()) {
            sDrainContinuationPending = false;
            resetTransportRetryDelay();
            scheduleFlush(FLUSH_PROBE_INTERVAL_MS);
        } else if (result.transportDeferred) {
            sDrainContinuationPending = false;
            if (result.progressed) resetTransportRetryDelay();
            scheduleTransportRetry();
        } else if (!hasPendingData()) {
            sDrainContinuationPending = false;
            resetTransportRetryDelay();
            scheduleFlush(FLUSH_PROBE_INTERVAL_MS);
        } else if (result.progressed) {
            sDrainContinuationPending = true;
            resetTransportRetryDelay();
            scheduleFlush(DRAIN_CONTINUATION_DELAY_MS);
        } else {
            scheduleFlush(FLUSH_PROBE_INTERVAL_MS);
        }
    }

    private static boolean shouldAttemptProvider() {
        if (!hasPendingData()) return false;
        if (sDrainContinuationPending
                || sInFlight != null
                || STICKY_GENERATION.get() > REPORTED_STICKY_GENERATION.get()) return true;
        OutboxState outbox = OUTBOX.get();
        if (outbox.rawHitCount.get() >= PENDING_QUEUE_HIGH_WATERMARK
                || outbox.eventCount.get() >= PENDING_QUEUE_HIGH_WATERMARK) return true;
        return SystemClock.elapsedRealtime() - sLastEventBatchAt
                >= PERIODIC_FLUSH_INTERVAL_MS;
    }

    private static boolean isUserUnlockDeferred() {
        return "user_locked".equals(sTransportError)
                || "user_unlock_state_unavailable".equals(sTransportError);
    }

    private static long nextTransportRetryDelay() {
        long delay = sTransportRetryDelayMs;
        sTransportRetryDelayMs = delay >= MAX_TRANSPORT_RETRY_INTERVAL_MS / 2L
                ? MAX_TRANSPORT_RETRY_INTERVAL_MS
                : delay * 2L;
        return delay;
    }

    private static void resetTransportRetryDelay() {
        sTransportRetryDelayMs = INITIAL_TRANSPORT_RETRY_INTERVAL_MS;
        sTransportRetryNotBeforeNanos = 0L;
    }

    private static void scheduleTransportRetry() {
        long delayMs = nextTransportRetryDelay();
        sTransportRetryNotBeforeNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(delayMs);
        scheduleFlush(delayMs);
    }

    private static long transportRetryDelayRemainingMs() {
        long remainingNanos = sTransportRetryNotBeforeNanos - System.nanoTime();
        if (remainingNanos <= 0L) return 0L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static FlushOutcome flushSnapshot() {
        if (BOOT_ID.isEmpty()) {
            sTransportError = "boot_id_unavailable";
            return FlushOutcome.TRANSPORT_DEFERRED;
        }
        if (sEpoch < 0L && !refreshBridgeState()) return FlushOutcome.TRANSPORT_DEFERRED;
        InFlightBatch inFlight = sInFlight;
        if (inFlight == null) {
            inFlight = buildInFlight();
            sInFlight = inFlight;
        }
        if (inFlight == null) return FlushOutcome.NO_BATCH;
        sendInFlight(inFlight);
        if (sInFlight != null) return FlushOutcome.TRANSPORT_DEFERRED;
        if (inFlight.rawHitCount > 0 || inFlight.normalizedEventCount > 0) {
            sLastEventBatchAt = SystemClock.elapsedRealtime();
        }
        return FlushOutcome.PROGRESSED;
    }

    private static InFlightBatch buildInFlight() {
        String processId = currentProcessId();
        long stickyGeneration = STICKY_GENERATION.get();
        boolean includeSticky = stickyGeneration > REPORTED_STICKY_GENERATION.get();
        List<DiagnosticsHookState> states = includeSticky
                ? stickyStates(processId) : Collections.emptyList();
        int itemCapacity = Math.max(0, DiagnosticsWireParser.MAX_WIRE_ITEMS - states.size());
        OutboxState outbox = OUTBOX.get();
        Snapshot snapshot = outbox.snapshot(itemCapacity);
        if (snapshot.isEmpty() && states.isEmpty()) return null;

        DiagnosticsWriteBatch.Builder batch = DiagnosticsWriteBatch.builder()
                .wireIdentity(
                        DiagnosticsWireParser.WIRE_VERSION,
                        BOOT_ID,
                        processId,
                        SENDER_INSTANCE_ID,
                        sEpoch,
                        sNextBatchSequence)
                .countingMode(DiagnosticsWriteBatch.CountingMode.TYPED);
        for (RawHookHit rawHit : snapshot.rawHits) batch.addRawHit(rawHit);
        for (NormalizedDiagnosticsEvent event : snapshot.events) batch.addEvent(event);
        for (DiagnosticsHookState state : states) batch.addHookState(state);
        DiagnosticsWriteBatch payload = batch.build();
        return new InFlightBatch(
                DiagnosticsWireEncoder.encodeTyped(payload),
                outbox,
                snapshot.rawHits.size(),
                snapshot.events.size(),
                includeSticky ? stickyGeneration : -1L,
                payload.size(),
                sEpoch,
                sNextBatchSequence);
    }

    private static void sendInFlight(InFlightBatch inFlight) {
        Bundle response = callProvider(BridgeContract.METHOD_REPORT_BATCH, inFlight.payload);
        if (response == null) return;
        if (response.getInt(BridgeContract.EXTRA_BRIDGE_VERSION, -1)
                != BridgeContract.BRIDGE_VERSION) {
            sTransportError = "bridge_version_mismatch";
            return;
        }
        String remoteBoot = response.getString(BridgeContract.EXTRA_BOOT_ID, "");
        long remoteEpoch = response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH, -1L);
        if (!BOOT_ID.equals(remoteBoot)) {
            sTransportError = "bridge_boot_mismatch";
            return;
        }
        if (remoteEpoch < 0L) {
            sTransportError = "bridge_epoch_missing";
            return;
        }
        if (remoteEpoch != inFlight.epoch) {
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
        inFlight.outbox.commit(inFlight.rawHitCount, inFlight.normalizedEventCount);
        if (inFlight.stickyGeneration >= 0L) {
            REPORTED_STICKY_GENERATION.accumulateAndGet(
                    inFlight.stickyGeneration, Math::max);
        }
        sNextBatchSequence = inFlight.sequence + 1L;
        sInFlight = null;
        sTransportError = "";
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
        OUTBOX.set(new OutboxState());
        sInFlight = null;
        sEpoch = Math.max(0L, epoch);
        sNextBatchSequence = 0L;
        REPORTED_STICKY_GENERATION.set(-1L);
        sDrainContinuationPending = false;
        sTransportError = "";
    }

    private static Bundle callProvider(String method, Bundle extras) {
        if (!isUserUnlockedForDiagnostics()) return null;
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

    private static boolean isUserUnlockedForDiagnostics() {
        try {
            if (sUserUnlockProbe.isUserUnlocked()) return true;
            sTransportError = "user_locked";
        } catch (Throwable ignored) {
            sTransportError = "user_unlock_state_unavailable";
        }
        return false;
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

    private static String safeCurrentProcessId() {
        try {
            return currentProcessId();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static RevisionPair currentRevisions(DecisionTrace trace) {
        if (trace != null) {
            try {
                return new RevisionPair(
                        diagnosticRevision(trace.configRevision()),
                        diagnosticRevision(trace.policyRevision()));
            } catch (Throwable ignored) {
                return RevisionPair.unknown();
            }
        }
        try {
            RuntimeConfigSnapshot snapshot = HookConfig.runtimeSnapshot();
            return new RevisionPair(
                    diagnosticRevision(snapshot.configRevision()),
                    diagnosticRevision(snapshot.policyRevision()));
        } catch (Throwable ignored) {
            return RevisionPair.unknown();
        }
    }

    private static String safeHookTargetFor(HookEvent event, DecisionTrace trace) {
        try {
            return hookTargetFor(event, trace);
        } catch (Throwable ignored) {
            return "unmapped_event";
        }
    }

    private static boolean safeIdentityResolved(DecisionTrace trace, HookEvent event) {
        try {
            return identityResolved(trace, event);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String safeFailOpenReason(String result, DecisionTrace trace) {
        try {
            return trace == null ? result : trace.reason().name();
        } catch (Throwable ignored) {
            return result;
        }
    }

    private static DiagnosticsEventNormalizer.DecisionData normalizedDecision(
            HookEvent event, String result, DecisionTrace trace, String failOpen) {
        DiagnosticsEventNormalizer.DecisionData decision =
                new DiagnosticsEventNormalizer.DecisionData();
        decision.behavior = behaviorFor(event.feature);
        decision.source = sourceFor(event);
        decision.packageName = event.packageName;
        decision.uid = event.uid;
        decision.scene = event.scene;
        decision.reasonCode = event.code;
        decision.mode = modeFor(trace);
        decision.decision = decisionFor(result, trace);
        decision.result = result;
        decision.failOpenReason = failOpen;
        decision.rawHitCount = 1;
        return decision;
    }

    private static void markQueueUnavailable(String kind) {
        sTransportError = "diagnostics_" + kind + "_queue_unavailable";
        STICKY_GENERATION.incrementAndGet();
    }

    private static void markRecordFailure() {
        sTransportError = "diagnostics_record_failed";
        STICKY_GENERATION.incrementAndGet();
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

    static String hookTargetFor(HookEvent event, DecisionTrace trace) {
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
                || FeatureIds.FAST_CLEAR_OTHER.equals(feature)) {
            return HookTargetIds.FAST_CLEAR_DISPATCH;
        }
        if (FeatureIds.TOURNAMENT.equals(feature)) return HookTargetIds.TOURNAMENT;
        if (FeatureIds.MEMORY_FORCE.equals(feature)
                || FeatureIds.MEMORY_OVER.equals(feature)
                || FeatureIds.MEMORY_GUARD_OTHER.equals(feature)) {
            return HookTargetIds.MEMORY_GUARD_DISPATCH;
        }
        if (FeatureIds.ATHENA_OTHER.equals(feature)) {
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
        if (FeatureIds.SUPER_FREEZE.equals(feature)) {
            return HookTargetIds.HANS_FREEZE_DISPATCH;
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
        if (FeatureIds.ATHENA_OTHER.equals(event.feature)) return "OTHER";
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

    private static final class OutboxState {
        private final ConcurrentLinkedQueue<RawHookHit> rawHits =
                new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<NormalizedDiagnosticsEvent> events =
                new ConcurrentLinkedQueue<>();
        private final AtomicInteger rawHitCount = new AtomicInteger();
        private final AtomicInteger eventCount = new AtomicInteger();
        private final DiagnosticsEventDeduplicator deduplicator =
                new DiagnosticsEventDeduplicator();

        private boolean offerRawHit(RawHookHit rawHit) {
            if (rawHit == null || !rawHit.isStorable()
                    || !reserve(rawHitCount, MAX_PENDING_RAW_HITS)) {
                return false;
            }
            try {
                if (rawHits.offer(rawHit)) return true;
            } catch (Throwable ignored) {
                // Diagnostics admission is fail-open and never changes Hook behavior.
            }
            rawHitCount.decrementAndGet();
            return false;
        }

        private OfferResult offerNormalizedEvent(NormalizedDiagnosticsEvent event) {
            if (event == null || !event.isStorable()
                    || !reserve(eventCount, MAX_PENDING_NORMALIZED_EVENTS)) {
                return OfferResult.UNAVAILABLE;
            }
            try {
                if (!deduplicator.shouldRecord(event)) {
                    eventCount.decrementAndGet();
                    return OfferResult.DUPLICATE;
                }
                NormalizedDiagnosticsEvent accepted = event.copyBuilder()
                        .eventToken(SENDER_INSTANCE_ID + ":"
                                + EVENT_SEQUENCE.getAndIncrement())
                        .build();
                if (events.offer(accepted)) return OfferResult.ACCEPTED;
            } catch (Throwable ignored) {
                // Diagnostics admission is fail-open and never changes Hook behavior.
            }
            eventCount.decrementAndGet();
            return OfferResult.UNAVAILABLE;
        }

        private int pendingItemCount() {
            return rawHitCount.get() + eventCount.get();
        }

        private Snapshot snapshot(int limit) {
            if (limit <= 0) return Snapshot.empty();
            int rawPending = Math.min(rawHitCount.get(), limit);
            int eventPending = Math.min(eventCount.get(), limit);
            int rawLimit = Math.min(rawPending, (limit + 1) / 2);
            int eventLimit = Math.min(eventPending, limit - rawLimit);
            int remaining = limit - rawLimit - eventLimit;
            int extraRaw = Math.min(Math.max(0, rawPending - rawLimit), remaining);
            rawLimit += extraRaw;
            remaining -= extraRaw;
            eventLimit += Math.min(Math.max(0, eventPending - eventLimit), remaining);
            return new Snapshot(
                    snapshotQueue(rawHits, rawLimit),
                    snapshotQueue(events, eventLimit));
        }

        private void commit(int rawCount, int normalizedCount) {
            commitQueue(rawHits, rawHitCount, rawCount);
            commitQueue(events, eventCount, normalizedCount);
        }

        private static boolean reserve(AtomicInteger count, int capacity) {
            for (int attempt = 0; attempt < MAX_OUTBOX_CAS_ATTEMPTS; attempt++) {
                int current = count.get();
                if (current >= capacity) return false;
                if (count.compareAndSet(current, current + 1)) return true;
            }
            return false;
        }

        private static <T> List<T> snapshotQueue(ConcurrentLinkedQueue<T> queue, int limit) {
            if (limit <= 0) return Collections.emptyList();
            ArrayList<T> values = new ArrayList<>(limit);
            for (T value : queue) {
                if (values.size() >= limit) break;
                values.add(value);
            }
            return values;
        }

        private static <T> void commitQueue(
                ConcurrentLinkedQueue<T> queue, AtomicInteger count, int limit) {
            int removed = 0;
            while (removed < limit && queue.poll() != null) removed++;
            if (removed > 0) count.addAndGet(-removed);
        }
    }

    private enum OfferResult {
        ACCEPTED,
        DUPLICATE,
        UNAVAILABLE
    }

    private enum FlushOutcome {
        PROGRESSED,
        TRANSPORT_DEFERRED,
        NO_BATCH
    }

    private static final class DrainResult {
        boolean progressed;
        boolean transportDeferred;
    }

    private static final class Snapshot {
        final List<RawHookHit> rawHits;
        final List<NormalizedDiagnosticsEvent> events;

        Snapshot(List<RawHookHit> rawHits, List<NormalizedDiagnosticsEvent> events) {
            this.rawHits = rawHits;
            this.events = events;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyList());
        }

        boolean isEmpty() { return rawHits.isEmpty() && events.isEmpty(); }
    }

    private static final class RevisionPair {
        final int configRevision;
        final int policyRevision;

        RevisionPair(int configRevision, int policyRevision) {
            this.configRevision = configRevision;
            this.policyRevision = policyRevision;
        }

        static RevisionPair unknown() { return new RevisionPair(-1, -1); }
    }

    private static final class InFlightBatch {
        final Bundle payload;
        final OutboxState outbox;
        final int rawHitCount;
        final int normalizedEventCount;
        final long stickyGeneration;
        final int itemCount;
        final long epoch;
        final long sequence;

        InFlightBatch(
                Bundle payload, OutboxState outbox,
                int rawHitCount, int normalizedEventCount,
                long stickyGeneration, int itemCount, long epoch, long sequence) {
            this.payload = payload;
            this.outbox = outbox;
            this.rawHitCount = rawHitCount;
            this.normalizedEventCount = normalizedEventCount;
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
