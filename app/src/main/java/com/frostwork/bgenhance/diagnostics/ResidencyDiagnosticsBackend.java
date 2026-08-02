package com.frostwork.bgenhance.diagnostics;

import android.content.Context;
import android.database.sqlite.SQLiteFullException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Fail-open asynchronous facade for diagnostics persistence.
 * Enqueue methods only touch bounded memory; every database call runs on one worker.
 */
public final class ResidencyDiagnosticsBackend implements AutoCloseable {
    public static final int EXPORT_FORMAT_VERSION = 1;
    private static final int MAX_PENDING_COMMANDS = 64;

    private final DiagnosticsRetentionPolicy policy;
    private final StorageFactory storageFactory;
    private final ScheduledExecutorService executor;
    private final String currentBootId;
    private final ArrayDeque<PendingBatch> queue = new ArrayDeque<>();
    private final Object stateLock = new Object();
    private final int commandCapacity;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong writeGeneration = new AtomicLong();
    private final AtomicLong appliedClearGeneration = new AtomicLong();
    private final AtomicLong acceptedWrites = new AtomicLong();
    private final AtomicLong droppedWrites = new AtomicLong();
    private final AtomicLong committedWrites = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private int pendingCommands;
    private int queuedWrites;
    private volatile ScheduledFuture<?> maintenanceFuture;
    private volatile ResidencyDiagnosticsStorage storage;

    public static ResidencyDiagnosticsBackend open(Context context) {
        return open(context, DiagnosticsRetentionPolicy.defaults());
    }

    public static ResidencyDiagnosticsBackend open(
            Context context, DiagnosticsRetentionPolicy retentionPolicy) {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        DiagnosticsRetentionPolicy policy = retentionPolicy == null
                ? DiagnosticsRetentionPolicy.defaults() : retentionPolicy;
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(
                1, runnable -> {
            Thread thread = new Thread(runnable, "BgEnhanceDiagnosticsDb");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        String currentBootId = DiagnosticsBootId.readCurrent();
        return new ResidencyDiagnosticsBackend(policy, () -> {
            if (applicationContext == null) {
                throw new IllegalStateException("diagnostics context unavailable");
            }
            return new ResidencyDiagnosticsDb(applicationContext, policy);
        }, worker, currentBootId);
    }

    ResidencyDiagnosticsBackend(
            DiagnosticsRetentionPolicy policy,
            ResidencyDiagnosticsStorage storage,
            ScheduledExecutorService executor,
            String currentBootId) {
        this(policy, () -> storage, executor, currentBootId);
    }

    private ResidencyDiagnosticsBackend(
            DiagnosticsRetentionPolicy policy,
            StorageFactory storageFactory,
            ScheduledExecutorService executor,
            String currentBootId) {
        this.policy = policy == null ? DiagnosticsRetentionPolicy.defaults() : policy;
        this.storageFactory = storageFactory;
        this.executor = executor;
        this.currentBootId = DiagnosticsValues.isStrictId(currentBootId, false)
                ? currentBootId : "";
        commandCapacity = Math.max(1, Math.min(MAX_PENDING_COMMANDS, this.policy.queueCapacity));
        startMaintenance();
    }

    public boolean recordRawHookHit(RawHookHit hit) {
        return enqueue(DiagnosticsWriteBatch.builder().addRawHit(hit).build()).accepted == 1;
    }

    public boolean recordEvent(NormalizedDiagnosticsEvent event) {
        return enqueue(DiagnosticsWriteBatch.builder().addEvent(event).build()).accepted == 1;
    }

    public boolean updateHookState(DiagnosticsHookState state) {
        return enqueue(DiagnosticsWriteBatch.builder().addHookState(state).build()).accepted == 1;
    }

    public EnqueueResult enqueue(DiagnosticsWriteBatch batch) {
        if (batch == null || batch.isEmpty()) return new EnqueueResult(0, 0);
        int total = batch.size();
        if (total > DiagnosticsRetentionPolicy.MAX_ACCEPTED_BATCH_ITEMS
                || !isValidBatch(batch)) {
            droppedWrites.addAndGet(total);
            return new EnqueueResult(0, total);
        }
        boolean accepted;
        int queuedAfter;
        synchronized (stateLock) {
            accepted = !closed.get() && total <= policy.queueCapacity - queuedWrites;
            if (accepted) {
                queue.addLast(new PendingBatch(batch, writeGeneration.get()));
                queuedWrites += total;
            }
            queuedAfter = queuedWrites;
        }
        if (!accepted) {
            droppedWrites.addAndGet(total);
            return new EnqueueResult(0, total);
        }
        acceptedWrites.addAndGet(total);
        requestDrain(queuedAfter >= policy.batchSize ? 0L : policy.flushDelayMillis);
        return new EnqueueResult(total, 0);
    }

    public CompletableFuture<Void> flushAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submitCommand(() -> {
            drainUntil(appliedClearGeneration.get());
            result.complete(null);
        }, () -> result.complete(null));
        return result;
    }

    public CompletableFuture<DiagnosticsQuery.EventPage> queryEventsAsync(
            DiagnosticsQuery.EventFilter filter) {
        return submitQuery(
                () -> storage().queryEvents(filter),
                () -> DiagnosticsQuery.EventPage.empty("database_unavailable"));
    }

    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query) {
        return submitQuery(
                () -> storage().queryAggregates(query),
                () -> DiagnosticsQuery.AggregatePage.empty("database_unavailable"));
    }

    public CompletableFuture<DiagnosticsQuery.HomeStatsSnapshot> queryHomeStatsAsync(
            Iterable<String> featureIds) {
        List<String> copied = copyFeatureIds(featureIds);
        return submitQuery(
                () -> storage().queryHomeStats(copied),
                () -> DiagnosticsQuery.HomeStatsSnapshot.empty("database_unavailable"));
    }

    public CompletableFuture<List<DiagnosticsHookState>> queryHookStatesAsync(int limit) {
        return submitQuery(
                () -> storage().queryHookStates(limit),
                Collections::emptyList);
    }

    public CompletableFuture<Long> currentEpochAsync() {
        return submitQuery(() -> storage().currentEpoch(), () -> 0L);
    }

    public CompletableFuture<DiagnosticsQuery.ExportSnapshot> exportAsync(
            DiagnosticsQuery.ExportOptions requestedOptions,
            Iterable<String> homepageFeatureIds) {
        DiagnosticsQuery.ExportOptions options = requestedOptions == null
                ? DiagnosticsQuery.ExportOptions.redactedDefaults() : requestedOptions;
        List<String> featureIds = copyFeatureIds(homepageFeatureIds);
        return submitQuery(() -> buildExport(options, featureIds),
                () -> DiagnosticsQuery.ExportSnapshot.empty("database_unavailable"));
    }

    public CompletableFuture<DiagnosticsQuery.ClearResult> clearAsync(
            DiagnosticsQuery.ClearScope scope) {
        CompletableFuture<DiagnosticsQuery.ClearResult> result = new CompletableFuture<>();
        long clearGeneration;
        boolean submitted;
        synchronized (stateLock) {
            if (closed.get() || pendingCommands >= commandCapacity) {
                submitted = false;
                clearGeneration = -1L;
            } else {
                clearGeneration = writeGeneration.incrementAndGet();
                submitted = submitCommandLocked(() -> {
                    try {
                        drainUntil(clearGeneration - 1L);
                        DiagnosticsQuery.ClearResult clearResult = clearWithRecovery(scope);
                        appliedClearGeneration.set(clearGeneration);
                        result.complete(clearResult == null
                                ? DiagnosticsQuery.ClearResult.failed("database_unavailable")
                                : clearResult);
                    } catch (Throwable error) {
                        failedBatches.incrementAndGet();
                        appliedClearGeneration.set(clearGeneration);
                        result.complete(DiagnosticsQuery.ClearResult.failed(
                                DiagnosticsValues.throwableCode(error)));
                    } finally {
                        requestDrain(0L);
                    }
                }, () -> {
                    appliedClearGeneration.set(clearGeneration);
                    result.complete(DiagnosticsQuery.ClearResult.failed("backend_closed"));
                });
            }
        }
        if (!submitted) {
            result.complete(DiagnosticsQuery.ClearResult.failed(
                    closed.get() ? "backend_closed" : "backend_busy"));
        }
        return result;
    }

    private DiagnosticsQuery.ClearResult clearWithRecovery(
            DiagnosticsQuery.ClearScope scope) {
        ResidencyDiagnosticsStorage current = storage();
        try {
            return current.clear(scope);
        } catch (SQLiteFullException full) {
            current.performMaintenance(System.currentTimeMillis(), true);
            return current.clear(scope);
        }
    }

    public DiagnosticsQuery.BackendHealth health() {
        return new DiagnosticsQuery.BackendHealth(
                acceptedWrites.get(), droppedWrites.get(), committedWrites.get(),
                failedBatches.get(), queuedWriteCount(), closed.get());
    }

    /**
     * Closes asynchronously after all commands accepted before this call and all queued writes.
     * Calls made after the close barrier receive their fail-open result without touching storage.
     */
    public CompletableFuture<Void> closeAsync() {
        synchronized (stateLock) {
            if (!closed.compareAndSet(false, true)) return closeCompletion;
            ScheduledFuture<?> periodic = maintenanceFuture;
            if (periodic != null) periodic.cancel(false);
            try {
                executor.execute(() -> {
                    try {
                        drainUntil(Long.MAX_VALUE);
                        closeStorage();
                    } catch (Throwable ignored) {
                        failedBatches.incrementAndGet();
                    } finally {
                        executor.shutdownNow();
                        closeCompletion.complete(null);
                    }
                });
            } catch (Throwable error) {
                failedBatches.incrementAndGet();
                closeStorage();
                executor.shutdownNow();
                closeCompletion.complete(null);
            }
            return closeCompletion;
        }
    }

    @Override
    public void close() {
        closeAsync();
    }

    private void closeStorage() {
        ResidencyDiagnosticsStorage current = storage;
        if (current == null) return;
        try {
            current.close();
        } catch (Throwable ignored) {
            failedBatches.incrementAndGet();
        }
    }

    private void startMaintenance() {
        try {
            executor.execute(this::performRegularMaintenanceSafely);
            maintenanceFuture = executor.scheduleWithFixedDelay(
                    this::performRegularMaintenanceSafely,
                    policy.maintenanceIntervalMillis,
                    policy.maintenanceIntervalMillis,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            failedBatches.incrementAndGet();
        }
    }

    private void performRegularMaintenanceSafely() {
        if (closed.get()) return;
        ResidencyDiagnosticsStorage current = storageOrNull();
        if (current == null) {
            failedBatches.incrementAndGet();
            return;
        }
        performRegularMaintenanceSafely(current);
    }

    private void performRegularMaintenanceSafely(ResidencyDiagnosticsStorage current) {
        long now = System.currentTimeMillis();
        try {
            current.performMaintenance(now, false);
        } catch (SQLiteFullException full) {
            try {
                current.performMaintenance(now, true);
                current.performMaintenance(now, false);
            } catch (Throwable recoveryFailure) {
                failedBatches.incrementAndGet();
            }
        } catch (Throwable error) {
            failedBatches.incrementAndGet();
        }
    }

    private void requestDrain(long delayMillis) {
        if (closed.get() || !drainScheduled.compareAndSet(false, true)) return;
        try {
            executor.schedule(() -> {
                try {
                    drainOneBatch(appliedClearGeneration.get());
                } finally {
                    drainScheduled.set(false);
                    PendingBatch next = peekBatch();
                    if (next != null && next.generation <= appliedClearGeneration.get()) {
                        requestDrain(queuedWriteCount() >= policy.batchSize
                                ? 0L : policy.flushDelayMillis);
                    }
                }
            }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            drainScheduled.set(false);
            failedBatches.incrementAndGet();
        }
    }

    private void drainUntil(long maximumGeneration) {
        while (drainOneBatch(maximumGeneration) > 0) {
            // Continue until the generation barrier or queue end is reached.
        }
    }

    private int drainOneBatch(long maximumGeneration) {
        PendingBatch pending;
        synchronized (stateLock) {
            pending = queue.peekFirst();
            if (pending == null || pending.generation > maximumGeneration) return 0;
            queue.removeFirst();
            queuedWrites -= pending.size;
        }
        int drained = pending.size;
        ResidencyDiagnosticsStorage current = storageOrNull();
        if (current != null && writeWithRecovery(current, pending.batch)) {
            committedWrites.addAndGet(drained);
            performRegularMaintenanceSafely(current);
        } else {
            recordFailedWrite(drained);
        }
        return drained;
    }

    private boolean writeWithRecovery(
            ResidencyDiagnosticsStorage current, DiagnosticsWriteBatch batch) {
        try {
            current.writeBatch(batch);
            return true;
        } catch (SQLiteFullException full) {
            try {
                current.performMaintenance(System.currentTimeMillis(), true);
                current.writeBatch(batch);
                return true;
            } catch (Throwable retryFailure) {
                return false;
            }
        } catch (Throwable error) {
            return false;
        }
    }

    private void recordFailedWrite(int dropped) {
        failedBatches.incrementAndGet();
        droppedWrites.addAndGet(dropped);
    }

    private <T> CompletableFuture<T> submitQuery(
            Supplier<T> query, Supplier<T> fallback) {
        CompletableFuture<T> result = new CompletableFuture<>();
        submitCommand(() -> {
            try {
                drainUntil(appliedClearGeneration.get());
                T value = query.get();
                result.complete(value == null ? fallback.get() : value);
            } catch (Throwable error) {
                failedBatches.incrementAndGet();
                result.complete(fallback.get());
            }
        }, () -> result.complete(fallback.get()));
        return result;
    }

    private boolean submitCommand(Runnable action, Runnable rejected) {
        boolean submitted;
        synchronized (stateLock) {
            submitted = submitCommandLocked(action, rejected);
        }
        if (!submitted) runQuietly(rejected);
        return submitted;
    }

    private boolean submitCommandLocked(Runnable action, Runnable rejected) {
        if (closed.get() || pendingCommands >= commandCapacity) return false;
        pendingCommands++;
        try {
            executor.execute(() -> {
                try {
                    action.run();
                } catch (Throwable error) {
                    failedBatches.incrementAndGet();
                    runQuietly(rejected);
                } finally {
                    synchronized (stateLock) {
                        pendingCommands--;
                    }
                }
            });
            return true;
        } catch (Throwable error) {
            pendingCommands--;
            failedBatches.incrementAndGet();
            return false;
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // Diagnostics completion callbacks must not escape into their caller.
        }
    }

    private PendingBatch peekBatch() {
        synchronized (stateLock) {
            return queue.peekFirst();
        }
    }

    private int queuedWriteCount() {
        synchronized (stateLock) {
            return queuedWrites;
        }
    }

    private boolean isValidBatch(DiagnosticsWriteBatch batch) {
        DiagnosticsWriteBatch.BatchIdentity identity = batch.batchIdentity;
        if (identity != null && (currentBootId.isEmpty()
                || !currentBootId.equals(identity.bootId))) return false;
        for (RawHookHit hit : batch.rawHits) {
            if (hit == null || !hit.isStorable()
                    || (identity != null && (!identity.processId.equals(hit.processId)
                    || !identity.bootId.equals(hit.bootId)))) return false;
        }
        for (NormalizedDiagnosticsEvent event : batch.events) {
            if (event == null || !event.isStorable()
                    || (identity != null && (!identity.processId.equals(event.processId)
                    || !identity.bootId.equals(event.bootId)))) return false;
        }
        for (DiagnosticsWriteBatch.LegacyFeatureDelta delta : batch.legacyFeatureDeltas) {
            if (delta == null || !delta.isStorable()) return false;
        }
        for (DiagnosticsHookState state : batch.hookStates) {
            if (state == null || !state.isStorable()
                    || (identity != null && !state.matchesBatchIdentity(identity))) return false;
        }
        return true;
    }

    private ResidencyDiagnosticsStorage storageOrNull() {
        try {
            return storage();
        } catch (Throwable error) {
            return null;
        }
    }

    private ResidencyDiagnosticsStorage storage() {
        ResidencyDiagnosticsStorage current = storage;
        if (current != null) return current;
        synchronized (this) {
            current = storage;
            if (current == null) {
                current = storageFactory.open();
                if (current == null) throw new IllegalStateException("storage unavailable");
                storage = current;
            }
            return current;
        }
    }

    private DiagnosticsQuery.ExportSnapshot buildExport(
            DiagnosticsQuery.ExportOptions options, List<String> featureIds) {
        DiagnosticsQuery.EventPage eventPage = storage().queryEvents(
                DiagnosticsQuery.EventFilter.builder()
                        .limit(options.recentEventLimit)
                        .build());
        DiagnosticsQuery.AggregateQuery.Builder aggregateBuilder =
                DiagnosticsQuery.AggregateQuery.builder()
                        .dimension(DiagnosticsQuery.AggregateDimension.APPLICATION)
                        .dimension(DiagnosticsQuery.AggregateDimension.FEATURE)
                        .dimension(DiagnosticsQuery.AggregateDimension.HOOK_TARGET)
                        .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                        .dimension(DiagnosticsQuery.AggregateDimension.SOURCE)
                        .dimension(DiagnosticsQuery.AggregateDimension.DECISION)
                        .dimension(DiagnosticsQuery.AggregateDimension.RESULT)
                        .limit(options.aggregateLimit);
        DiagnosticsQuery.AggregatePage aggregates =
                storage().queryAggregates(aggregateBuilder.build());
        List<DiagnosticsQuery.RawHookSummary> rawHooks = storage().queryRawHooks(
                0L, Long.MAX_VALUE, options.rawHookLimit);
        List<DiagnosticsHookState> hookStates =
                storage().queryHookStates(options.hookStateLimit);
        DiagnosticsQuery.HomeStatsSnapshot homeStats = storage().queryHomeStats(featureIds);
        return redactExport(new DiagnosticsQuery.ExportSnapshot(
                EXPORT_FORMAT_VERSION,
                storage().schemaVersion(),
                System.currentTimeMillis(),
                policy.retentionMillis,
                options.includePackageNames,
                options.includeUids,
                options.includeSensitiveDetails,
                eventPage.items,
                aggregates.items,
                rawHooks,
                hookStates,
                homeStats,
                ""), options);
    }

    private static DiagnosticsQuery.ExportSnapshot redactExport(
            DiagnosticsQuery.ExportSnapshot source,
            DiagnosticsQuery.ExportOptions options) {
        ArrayList<NormalizedDiagnosticsEvent> events = new ArrayList<>();
        for (NormalizedDiagnosticsEvent event : source.recentEvents) {
            events.add(event.copyBuilder()
                    .bootId(options.includeSensitiveDetails ? event.bootId : "")
                    .processId(options.includeSensitiveDetails ? event.processId : "")
                    .packageName(exportPackage(event.packageName, options))
                    .uid(options.includeUids ? event.uid : -1)
                    .scene(options.includeSensitiveDetails ? event.scene : "")
                    .failOpenReason(
                            options.includeSensitiveDetails ? event.failOpenReason : "")
                    .eventToken("")
                    .build());
        }
        ArrayList<DiagnosticsQuery.AggregateRow> aggregates = new ArrayList<>();
        for (DiagnosticsQuery.AggregateRow row : source.aggregates) {
            aggregates.add(new DiagnosticsQuery.AggregateRow(
                    exportPackage(row.packageName, options),
                    options.includeUids ? row.uid : -1,
                    row.featureId, row.hookTargetId, row.behavior, row.source,
                    row.decision, row.result, row.rawHitCount, row.eventCount,
                    row.blockedCount, row.failOpenCount, row.identityResolvedCount,
                    row.latencyMicrosTotal, row.latencyMicrosMax, row.firstAt, row.lastAt));
        }
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> featureStats = new LinkedHashMap<>();
        for (Map.Entry<String, DiagnosticsQuery.FeatureStats> entry
                : source.homeStats.features.entrySet()) {
            DiagnosticsQuery.FeatureStats value = entry.getValue();
            featureStats.put(entry.getKey(), new DiagnosticsQuery.FeatureStats(
                    value.featureId, value.observed, value.blocked, value.failOpen,
                    value.lastAt,
                    exportPackage(value.lastPackage, options),
                    options.includeSensitiveDetails ? value.lastProcess : "",
                    options.includeSensitiveDetails ? value.lastScene : "",
                    options.includeSensitiveDetails ? value.lastReason : "",
                    value.lastResult));
        }
        ArrayList<DiagnosticsQuery.RawHookSummary> rawHooks = new ArrayList<>();
        for (DiagnosticsQuery.RawHookSummary value : source.rawHooks) {
            rawHooks.add(new DiagnosticsQuery.RawHookSummary(
                    options.includeSensitiveDetails ? value.processId : "",
                    value.featureId,
                    value.hookTargetId,
                    value.hitCount,
                    value.parsedCount,
                    value.identityResolvedCount,
                    value.lastHitAt,
                    options.includeSensitiveDetails ? value.lastError : ""));
        }
        ArrayList<DiagnosticsHookState> hookStates = new ArrayList<>();
        for (DiagnosticsHookState value : source.hookStates) {
            hookStates.add(DiagnosticsHookState.builder()
                    .processId(options.includeSensitiveDetails ? value.processId : "")
                    .featureId(value.featureId)
                    .hookTargetId(value.hookTargetId)
                    .installStatus(options.includeSensitiveDetails
                            ? value.installStatus : stableInstallStatus(value.installStatus))
                    .bootId(options.includeSensitiveDetails ? value.bootId : "")
                    .lastHookAt(value.lastHookAt)
                    .lastEventAt(value.lastEventAt)
                    .rawHitCount(value.rawHitCount)
                    .parsedCount(value.parsedCount)
                    .identityResolvedCount(value.identityResolvedCount)
                    .configRevision(value.configRevision)
                    .policyRevision(value.policyRevision)
                    .lastError(options.includeSensitiveDetails ? value.lastError : "")
                    .updatedAt(value.updatedAt)
                    .build());
        }
        return new DiagnosticsQuery.ExportSnapshot(
                source.formatVersion, source.databaseSchemaVersion, source.generatedAt,
                source.retentionMillis, options.includePackageNames, options.includeUids,
                options.includeSensitiveDetails,
                events, aggregates, rawHooks, hookStates,
                new DiagnosticsQuery.HomeStatsSnapshot(featureStats, source.homeStats.errorCode),
                source.errorCode);
    }

    private static String exportPackage(
            String packageName, DiagnosticsQuery.ExportOptions options) {
        if (!options.includePackageNames || packageName == null) return "";
        return packageName;
    }

    private static String stableInstallStatus(String value) {
        String normalized = DiagnosticsValues.id(value);
        if (normalized.startsWith("INSTALLED")) return "INSTALLED";
        if (normalized.startsWith("FAILED")) return "FAILED";
        if (normalized.startsWith("UNSUPPORTED")) return "UNSUPPORTED";
        if (normalized.startsWith("SKIPPED")) return "SKIPPED";
        if (normalized.startsWith("PENDING")) return "PENDING";
        return "UNKNOWN";
    }

    private List<String> copyFeatureIds(Iterable<String> values) {
        if (values == null) return Collections.emptyList();
        ArrayList<String> copy = new ArrayList<>();
        for (String value : values) {
            if (copy.size() >= policy.featureTotalLimit) break;
            String feature = DiagnosticsValues.id(value);
            if (!feature.isEmpty() && !copy.contains(feature)) copy.add(feature);
        }
        return Collections.unmodifiableList(copy);
    }

    interface StorageFactory {
        ResidencyDiagnosticsStorage open();
    }

    public static final class EnqueueResult {
        public final int accepted;
        public final int dropped;

        EnqueueResult(int accepted, int dropped) {
            this.accepted = Math.max(0, accepted);
            this.dropped = Math.max(0, dropped);
        }
    }

    private static final class PendingBatch {
        final DiagnosticsWriteBatch batch;
        final long generation;
        final int size;

        PendingBatch(DiagnosticsWriteBatch batch, long generation) {
            this.batch = batch;
            this.generation = generation;
            size = batch.size();
        }
    }
}
