package com.frostwork.bgenhance.diagnostics;

import android.content.Context;
import android.database.sqlite.SQLiteFullException;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static final int EXPORT_FORMAT_VERSION = 2;
    private static final String UNKNOWN_EXPORTED_VALUE = "unknown";
    private static final int MAX_PENDING_COMMANDS = 64;
    private static final Set<String> EXPECTED_CURRENT_BOOT_STATE_IDENTITIES =
            expectedCurrentBootStateIdentities();

    private final DiagnosticsRetentionPolicy policy;
    private final StorageFactory storageFactory;
    private final ScheduledExecutorService executor;
    private final String currentBootId;
    private final LegacyMigrationSource legacyMigrationSource;
    private final ArrayDeque<PendingBatch> queue = new ArrayDeque<>();
    private final Map<DiagnosticsWriteBatch.BatchIdentity, PendingBatch> durablePending =
            new LinkedHashMap<>();
    private final Object stateLock = new Object();
    private final int commandCapacity;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong writeGeneration = new AtomicLong();
    private final AtomicLong appliedClearGeneration = new AtomicLong();
    private final AtomicLong admissionEpoch = new AtomicLong(-1L);
    private final AtomicLong admissionEpochCutoffElapsedNanos = new AtomicLong(-1L);
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
        return open(context, retentionPolicy, DiagnosticsBootId.readCurrent());
    }

    public static ResidencyDiagnosticsBackend open(
            Context context,
            DiagnosticsRetentionPolicy retentionPolicy,
            String currentBootId) {
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
        return new ResidencyDiagnosticsBackend(policy, () -> {
            if (applicationContext == null) {
                throw new IllegalStateException("diagnostics context unavailable");
            }
            return new ResidencyDiagnosticsDb(
                    applicationContext, ResidencyDiagnosticsDb.DATABASE_NAME,
                    policy, currentBootId);
        }, worker, currentBootId,
                applicationContext == null
                        ? null : () -> LegacyDiagnosticsMigration.read(applicationContext));
    }

    ResidencyDiagnosticsBackend(
            DiagnosticsRetentionPolicy policy,
            ResidencyDiagnosticsStorage storage,
            ScheduledExecutorService executor,
            String currentBootId) {
        this(policy, () -> storage, executor, currentBootId);
    }

    ResidencyDiagnosticsBackend(
            DiagnosticsRetentionPolicy policy,
            ResidencyDiagnosticsStorage storage,
            ScheduledExecutorService executor,
            String currentBootId,
            LegacyMigrationSource legacyMigrationSource) {
        this(policy, () -> storage, executor, currentBootId, legacyMigrationSource);
    }

    private ResidencyDiagnosticsBackend(
            DiagnosticsRetentionPolicy policy,
            StorageFactory storageFactory,
            ScheduledExecutorService executor,
            String currentBootId) {
        this(policy, storageFactory, executor, currentBootId, null);
    }

    private ResidencyDiagnosticsBackend(
            DiagnosticsRetentionPolicy policy,
            StorageFactory storageFactory,
            ScheduledExecutorService executor,
            String currentBootId,
            LegacyMigrationSource legacyMigrationSource) {
        this.policy = policy == null ? DiagnosticsRetentionPolicy.defaults() : policy;
        this.storageFactory = storageFactory;
        this.executor = executor;
        this.currentBootId = DiagnosticsValues.isStrictId(currentBootId, false)
                ? currentBootId : "";
        this.legacyMigrationSource = legacyMigrationSource;
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
            DiagnosticsWriteBatch.BatchIdentity identity = batch.batchIdentity;
            long currentAdmissionEpoch = admissionEpoch.get();
            boolean epochAccepted = identity == null
                    || (currentAdmissionEpoch >= 0L
                    && identity.epoch == currentAdmissionEpoch);
            accepted = epochAccepted && !closed.get()
                    && total <= policy.queueCapacity - queuedWrites;
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

    /**
     * Enqueues a versioned Provider batch and completes only after SQLite commits or replays it.
     */
    public CompletableFuture<DurableWriteResult> enqueueDurable(
            DiagnosticsWriteBatch batch) {
        int total = batch == null ? 0 : batch.size();
        if (batch == null || batch.isEmpty() || batch.batchIdentity == null
                || total > DiagnosticsRetentionPolicy.MAX_ACCEPTED_BATCH_ITEMS
                || !isValidBatch(batch)) {
            if (total > 0) droppedWrites.addAndGet(total);
            return CompletableFuture.completedFuture(
                    DurableWriteResult.rejected(total, "invalid_batch"));
        }

        CompletableFuture<DurableWriteResult> completion = new CompletableFuture<>();
        String rejection = "";
        synchronized (stateLock) {
            DiagnosticsWriteBatch.BatchIdentity identity = batch.batchIdentity;
            long currentAdmissionEpoch = admissionEpoch.get();
            if (closed.get()) {
                rejection = "backend_closed";
            } else if (currentAdmissionEpoch < 0L
                    || identity.epoch != currentAdmissionEpoch) {
                rejection = "epoch_mismatch";
            } else if (durablePending.containsKey(identity)) {
                rejection = "batch_pending";
            } else if (total > policy.queueCapacity - queuedWrites) {
                rejection = "backend_busy";
            } else {
                PendingBatch pending = new PendingBatch(
                        batch, writeGeneration.get(), completion);
                queue.addLast(pending);
                durablePending.put(identity, pending);
                queuedWrites += total;
            }
        }
        if (!rejection.isEmpty()) {
            if (!"batch_pending".equals(rejection)) droppedWrites.addAndGet(total);
            completion.complete(DurableWriteResult.rejected(total, rejection));
            return completion;
        }
        acceptedWrites.addAndGet(total);
        if (!requestDrain(0L)) {
            rejectQueuedDurableBatches("backend_unavailable");
        }
        return completion;
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

    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregateSnapshotAsync(
            DiagnosticsQuery.AggregateQuery query, int maxRows) {
        return submitQuery(
                () -> storage().queryAggregateSnapshot(query, maxRows),
                () -> DiagnosticsQuery.AggregatePage.empty("database_unavailable"));
    }

    public CompletableFuture<DiagnosticsQuery.HomeStatsSnapshot> queryHomeStatsAsync(
            Iterable<String> featureIds) {
        List<String> copied = copyFeatureIds(featureIds);
        return submitQuery(
                () -> storage().queryHomeStats(copied),
                () -> DiagnosticsQuery.HomeStatsSnapshot.empty("database_unavailable"));
    }

    public CompletableFuture<DiagnosticsQuery.HookStatePage> queryHookStatesAsync(int limit) {
        return submitQuery(
                () -> new DiagnosticsQuery.HookStatePage(
                        storage().queryHookStates(limit), ""),
                () -> DiagnosticsQuery.HookStatePage.empty("database_unavailable"));
    }

    public CompletableFuture<DiagnosticsQuery.HomeDiagnosticsSnapshot>
            queryHomeDiagnosticsSnapshotAsync(
                    DiagnosticsQuery.AggregateQuery statsQuery,
                    int maxStatsRows,
                    int hookStateLimit) {
        int boundedStatsRows = Math.max(1, Math.min(
                DiagnosticsRetentionPolicy.MAX_AGGREGATE_SNAPSHOT_ROWS, maxStatsRows));
        int boundedHookStates = Math.max(1, Math.min(512, hookStateLimit));
        return submitQuery(
                () -> storage().queryHomeDiagnosticsSnapshot(
                        statsQuery, boundedStatsRows, boundedHookStates),
                () -> DiagnosticsQuery.HomeDiagnosticsSnapshot.empty(
                        "database_unavailable"));
    }

    public CompletableFuture<Long> currentEpochAsync() {
        CompletableFuture<Long> result = new CompletableFuture<>();
        long requestedGeneration;
        boolean submitted;
        synchronized (stateLock) {
            requestedGeneration = writeGeneration.get();
            submitted = submitCommandLocked(() -> {
                try {
                    drainUntil(appliedClearGeneration.get());
                    long epoch = storage().currentEpoch();
                    long epochCutoffElapsedNanos =
                            storage().currentEpochCutoffElapsedNanos();
                    if (epoch < 0L || epochCutoffElapsedNanos < 0L) {
                        throw new IllegalStateException("diagnostics epoch unavailable");
                    }
                    synchronized (stateLock) {
                        if (!closed.get()
                                && writeGeneration.get() == requestedGeneration
                                && appliedClearGeneration.get() >= requestedGeneration) {
                            admissionEpochCutoffElapsedNanos.set(epochCutoffElapsedNanos);
                            admissionEpoch.set(epoch);
                        }
                    }
                    result.complete(epoch);
                } catch (Throwable error) {
                    failedBatches.incrementAndGet();
                    result.completeExceptionally(error);
                }
            }, () -> result.completeExceptionally(
                    new IllegalStateException("diagnostics epoch unavailable")));
        }
        if (!submitted) {
            result.completeExceptionally(
                    new IllegalStateException("diagnostics epoch unavailable"));
        }
        return result;
    }

    /** Current wire epoch accepted by the non-blocking Provider boundary. */
    public long admissionEpoch() {
        return admissionEpoch.get();
    }

    public long admissionEpochCutoffElapsedNanos() {
        return admissionEpochCutoffElapsedNanos.get();
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
        long previousGeneration;
        long previousAdmissionEpoch;
        long previousAdmissionEpochCutoffElapsedNanos;
        boolean submitted;
        synchronized (stateLock) {
            if (closed.get() || pendingCommands >= commandCapacity) {
                submitted = false;
                clearGeneration = -1L;
                previousGeneration = writeGeneration.get();
                previousAdmissionEpoch = admissionEpoch.get();
                previousAdmissionEpochCutoffElapsedNanos =
                        admissionEpochCutoffElapsedNanos.get();
            } else {
                previousGeneration = writeGeneration.get();
                previousAdmissionEpoch = admissionEpoch.getAndSet(-1L);
                previousAdmissionEpochCutoffElapsedNanos =
                        admissionEpochCutoffElapsedNanos.get();
                clearGeneration = previousGeneration + 1L;
                writeGeneration.set(clearGeneration);
                submitted = submitCommandLocked(() -> {
                    try {
                        drainUntil(clearGeneration - 1L);
                        DiagnosticsQuery.ClearResult clearResult = clearWithRecovery(scope);
                        appliedClearGeneration.accumulateAndGet(clearGeneration, Math::max);
                        DiagnosticsQuery.ClearResult completed = clearResult == null
                                ? DiagnosticsQuery.ClearResult.failed("database_unavailable")
                                : clearResult;
                        publishAdmissionAfterClear(clearGeneration, completed);
                        result.complete(completed);
                    } catch (Throwable error) {
                        failedBatches.incrementAndGet();
                        appliedClearGeneration.accumulateAndGet(clearGeneration, Math::max);
                        restoreAdmissionAfterFailedClear(clearGeneration);
                        result.complete(DiagnosticsQuery.ClearResult.failed(
                                DiagnosticsValues.throwableCode(error)));
                    } finally {
                        requestDrain(0L);
                    }
                }, () -> {
                    appliedClearGeneration.accumulateAndGet(clearGeneration, Math::max);
                    restoreAdmissionAfterFailedClear(clearGeneration);
                    result.complete(DiagnosticsQuery.ClearResult.failed("backend_closed"));
                });
                if (!submitted) {
                    writeGeneration.set(previousGeneration);
                    admissionEpochCutoffElapsedNanos.set(
                            previousAdmissionEpochCutoffElapsedNanos);
                    admissionEpoch.set(previousAdmissionEpoch);
                }
            }
        }
        if (!submitted) {
            result.complete(DiagnosticsQuery.ClearResult.failed(
                    closed.get() ? "backend_closed" : "backend_busy"));
        }
        return result;
    }

    private void publishAdmissionAfterClear(
            long clearGeneration, DiagnosticsQuery.ClearResult result) {
        if (result != null && result.success && result.epoch >= 0L
                && result.epochCutoffElapsedNanos >= 0L) {
            synchronized (stateLock) {
                if (!closed.get() && writeGeneration.get() == clearGeneration) {
                    admissionEpochCutoffElapsedNanos.set(
                            result.epochCutoffElapsedNanos);
                    admissionEpoch.set(result.epoch);
                }
            }
            return;
        }
        restoreAdmissionAfterFailedClear(clearGeneration);
    }

    private void restoreAdmissionAfterFailedClear(long clearGeneration) {
        long restored = -1L;
        long restoredCutoffElapsedNanos = -1L;
        try {
            long storedEpoch = storage().currentEpoch();
            long storedCutoffElapsedNanos = storage().currentEpochCutoffElapsedNanos();
            if (storedEpoch >= 0L && storedCutoffElapsedNanos >= 0L) {
                restored = storedEpoch;
                restoredCutoffElapsedNanos = storedCutoffElapsedNanos;
            }
        } catch (Throwable ignored) {
            // An uncertain clear must keep wire admission closed.
        }
        synchronized (stateLock) {
            if (!closed.get() && writeGeneration.get() == clearGeneration) {
                admissionEpochCutoffElapsedNanos.set(restoredCutoffElapsedNanos);
                admissionEpoch.set(restored);
            }
        }
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
        boolean schedulingFailed = false;
        synchronized (stateLock) {
            if (!closed.compareAndSet(false, true)) return closeCompletion;
            admissionEpoch.set(-1L);
            admissionEpochCutoffElapsedNanos.set(-1L);
            ScheduledFuture<?> periodic = maintenanceFuture;
            if (periodic != null) periodic.cancel(false);
            try {
                executor.execute(() -> {
                    try {
                        drainUntil(Long.MAX_VALUE);
                        closeStorage();
                    } catch (Throwable ignored) {
                        failedBatches.incrementAndGet();
                        rejectQueuedDurableBatches("backend_closed");
                    } finally {
                        executor.shutdownNow();
                        closeCompletion.complete(null);
                    }
                });
            } catch (Throwable error) {
                failedBatches.incrementAndGet();
                closeStorage();
                executor.shutdownNow();
                schedulingFailed = true;
            }
        }
        if (schedulingFailed) {
            rejectQueuedDurableBatches("backend_closed");
            closeCompletion.complete(null);
        }
        return closeCompletion;
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
            if (legacyMigrationSource != null) {
                executor.execute(this::performLegacyMigrationSafely);
            }
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

    private void performLegacyMigrationSafely() {
        if (closed.get()) return;
        ResidencyDiagnosticsStorage current = storageOrNull();
        if (current == null) {
            failedBatches.incrementAndGet();
            return;
        }
        try {
            DiagnosticsWriteBatch batch = legacyMigrationSource.read();
            current.migrateLegacyOnce(batch == null
                    ? DiagnosticsWriteBatch.builder()
                            .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY)
                            .build()
                    : batch);
        } catch (SQLiteFullException full) {
            try {
                current.performMaintenance(System.currentTimeMillis(), true);
                DiagnosticsWriteBatch batch = legacyMigrationSource.read();
                current.migrateLegacyOnce(batch == null
                        ? DiagnosticsWriteBatch.builder()
                                .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY)
                                .build()
                        : batch);
            } catch (Throwable recoveryFailure) {
                failedBatches.incrementAndGet();
            }
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

    private boolean requestDrain(long delayMillis) {
        // Once close owns the barrier it will drain every batch accepted before close.
        if (closed.get() || !drainScheduled.compareAndSet(false, true)) return true;
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
            return true;
        } catch (Throwable error) {
            drainScheduled.set(false);
            failedBatches.incrementAndGet();
            return false;
        }
    }

    private void rejectQueuedDurableBatches(String errorCode) {
        ArrayList<PendingBatch> rejected = new ArrayList<>();
        int rejectedItems = 0;
        synchronized (stateLock) {
            Iterator<PendingBatch> iterator = queue.iterator();
            while (iterator.hasNext()) {
                PendingBatch pending = iterator.next();
                if (pending.durableCompletion == null) continue;
                iterator.remove();
                queuedWrites -= pending.size;
                durablePending.remove(pending.batch.batchIdentity, pending);
                rejected.add(pending);
                rejectedItems += pending.size;
            }
        }
        if (rejectedItems > 0) droppedWrites.addAndGet(rejectedItems);
        for (PendingBatch pending : rejected) {
            pending.durableCompletion.complete(
                    DurableWriteResult.rejected(pending.size, errorCode));
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
        ResidencyDiagnosticsStorage.WriteResult writeResult = current == null
                ? null : writeWithRecovery(current, pending.batch);
        if (writeResult == ResidencyDiagnosticsStorage.WriteResult.COMMITTED) {
            committedWrites.addAndGet(drained);
            completeDurable(pending, DurableWriteResult.committed(drained));
            performRegularMaintenanceSafely(current);
        } else if (writeResult == ResidencyDiagnosticsStorage.WriteResult.REPLAYED) {
            completeDurable(pending, DurableWriteResult.replayed(drained));
        } else if (writeResult != null && writeResult.isRejected()) {
            recordFailedWrite(drained);
            completeDurable(pending, DurableWriteResult.rejected(
                    drained, writeResult.name().toLowerCase(java.util.Locale.ROOT)));
        } else if (writeResult == null) {
            recordFailedWrite(drained);
            completeDurable(pending, DurableWriteResult.rejected(
                    drained, "storage_unavailable"));
        }
        return drained;
    }

    private void completeDurable(PendingBatch pending, DurableWriteResult result) {
        if (pending == null || pending.durableCompletion == null) return;
        synchronized (stateLock) {
            durablePending.remove(pending.batch.batchIdentity, pending);
        }
        pending.durableCompletion.complete(result);
    }

    private ResidencyDiagnosticsStorage.WriteResult writeWithRecovery(
            ResidencyDiagnosticsStorage current, DiagnosticsWriteBatch batch) {
        try {
            return current.writeBatch(batch);
        } catch (SQLiteFullException full) {
            try {
                current.performMaintenance(System.currentTimeMillis(), true);
                return current.writeBatch(batch);
            } catch (Throwable retryFailure) {
                return null;
            }
        } catch (Throwable error) {
            return null;
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
        if (identity != null && (!DiagnosticsValues.isSha256(identity.payloadHash, false)
                || currentBootId.isEmpty()
                || !currentBootId.equals(identity.bootId))) return false;
        for (RawHookHit hit : batch.rawHits) {
            if (hit == null || !hit.isStorable()
                    || !matchesCurrentBoot(hit.bootId)
                    || (identity != null && (!identity.processId.equals(hit.processId)
                    || !identity.bootId.equals(hit.bootId)))) return false;
        }
        for (NormalizedDiagnosticsEvent event : batch.events) {
            if (event == null || !event.isStorable()
                    || !matchesCurrentBoot(event.bootId)
                    || (identity != null && (!identity.processId.equals(event.processId)
                    || !identity.bootId.equals(event.bootId)))) return false;
        }
        for (DiagnosticsWriteBatch.LegacyFeatureDelta delta : batch.legacyFeatureDeltas) {
            if (delta == null || !delta.isStorable()) return false;
        }
        for (DiagnosticsHookState state : batch.hookStates) {
            if (state == null || !state.isStorable()
                    || !matchesCurrentBoot(state.bootId)
                    || (identity != null && !state.matchesBatchIdentity(identity))) return false;
        }
        return true;
    }

    private boolean matchesCurrentBoot(String payloadBootId) {
        return payloadBootId == null || payloadBootId.isEmpty()
                || (!currentBootId.isEmpty() && currentBootId.equals(payloadBootId));
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
        EventExportResult eventResult = readEventsForExport(options.recentEventLimit);
        AggregateExportResult aggregateResult =
                readAggregatesForExport(options.aggregateLimit);

        int rawProbeLimit = Math.min(
                policy.rawAggregateRowLimit, safeIncrement(options.rawHookLimit));
        List<DiagnosticsQuery.RawHookSummary> rawProbe = storage().queryRawHooks(
                0L, Long.MAX_VALUE, rawProbeLimit);
        boolean rawHasMore = rawProbe.size() > options.rawHookLimit;
        List<DiagnosticsQuery.RawHookSummary> rawHooks = copyAtMost(
                rawProbe, options.rawHookLimit);

        int hookProbeLimit = Math.min(
                policy.hookStateLimit, safeIncrement(options.hookStateLimit));
        List<DiagnosticsHookState> hookProbe = storage().queryHookStates(hookProbeLimit);
        boolean hookHasMore = hookProbe.size() > options.hookStateLimit;
        List<DiagnosticsHookState> hookStates = copyAtMost(
                hookProbe, options.hookStateLimit);

        String homeError = aggregateResult.errorCode;
        DiagnosticsQuery.HomeStatsSnapshot homeStats = homeStatsFromAggregates(
                aggregateResult.items, featureIds, homeError);

        DiagnosticsQuery.ExportCollectionStatus eventStatus =
                new DiagnosticsQuery.ExportCollectionStatus(
                        options.recentEventLimit, eventResult.items.size(),
                        eventResult.pagesRead, eventResult.hasMore, eventResult.errorCode);
        DiagnosticsQuery.ExportCollectionStatus aggregateStatus =
                new DiagnosticsQuery.ExportCollectionStatus(
                        options.aggregateLimit, aggregateResult.items.size(),
                        aggregateResult.pagesRead, aggregateResult.hasMore,
                        aggregateResult.historyTruncated,
                        aggregateResult.errorCode);
        DiagnosticsQuery.ExportCollectionStatus rawStatus =
                new DiagnosticsQuery.ExportCollectionStatus(
                        options.rawHookLimit, rawHooks.size(), rawProbe.isEmpty() ? 0 : 1,
                        rawHasMore, "");
        DiagnosticsQuery.ExportCollectionStatus hookStatus =
                new DiagnosticsQuery.ExportCollectionStatus(
                        options.hookStateLimit, hookStates.size(), hookProbe.isEmpty() ? 0 : 1,
                        hookHasMore, "");
        DiagnosticsQuery.ExportBootStatus bootStatus = bootStatus(
                hookStates, hookStatus, options.includeSensitiveDetails);
        DiagnosticsQuery.ExportStatus exportStatus = new DiagnosticsQuery.ExportStatus(
                eventStatus, aggregateStatus, rawStatus, hookStatus,
                homeStats.features.size(), bootStatus, health());
        String exportError = firstError(
                eventResult.errorCode, aggregateResult.errorCode, homeError);
        return redactExport(new DiagnosticsQuery.ExportSnapshot(
                EXPORT_FORMAT_VERSION,
                storage().schemaVersion(),
                System.currentTimeMillis(),
                policy.retentionMillis,
                options.includePackageNames,
                options.includeUids,
                options.includeSensitiveDetails,
                eventResult.items,
                aggregateResult.items,
                rawHooks,
                hookStates,
                homeStats,
                exportStatus,
                exportError), options);
    }

    private EventExportResult readEventsForExport(int requestedLimit) {
        ArrayList<NormalizedDiagnosticsEvent> items = new ArrayList<>();
        DiagnosticsQuery.EventCursor cursor = null;
        int pagesRead = 0;
        boolean hasMore = false;
        String errorCode = "";
        while (items.size() < requestedLimit) {
            DiagnosticsQuery.EventPage page = storage().queryEvents(
                    DiagnosticsQuery.EventFilter.builder()
                            .limit(requestedLimit - items.size())
                            .before(cursor)
                            .build());
            pagesRead++;
            errorCode = page.errorCode;
            int before = items.size();
            for (NormalizedDiagnosticsEvent item : page.items) {
                if (items.size() >= requestedLimit) break;
                items.add(item);
            }
            hasMore = page.nextCursor != null;
            if (!errorCode.isEmpty() || !hasMore) break;
            if (items.size() == before) {
                errorCode = "invalid_event_pagination";
                break;
            }
            cursor = page.nextCursor;
        }
        return new EventExportResult(items, pagesRead, hasMore, errorCode);
    }

    private AggregateExportResult readAggregatesForExport(int requestedLimit) {
        DiagnosticsQuery.AggregateQuery query = DiagnosticsQuery.AggregateQuery.builder()
                .dimension(DiagnosticsQuery.AggregateDimension.APPLICATION)
                .dimension(DiagnosticsQuery.AggregateDimension.FEATURE)
                .dimension(DiagnosticsQuery.AggregateDimension.HOOK_TARGET)
                .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                .dimension(DiagnosticsQuery.AggregateDimension.SOURCE)
                .dimension(DiagnosticsQuery.AggregateDimension.DECISION)
                .dimension(DiagnosticsQuery.AggregateDimension.RESULT)
                .build();
        DiagnosticsQuery.AggregatePage page =
                storage().queryAggregateSnapshot(query, requestedLimit);
        return new AggregateExportResult(
                page.items,
                page.items.isEmpty() ? 0 : 1,
                page.hasMore,
                page.historyTruncated,
                page.errorCode);
    }

    private DiagnosticsQuery.ExportBootStatus bootStatus(
            List<DiagnosticsHookState> hookStates,
            DiagnosticsQuery.ExportCollectionStatus hookStatus,
            boolean includeSensitiveDetails) {
        boolean identityAvailable = !currentBootId.isEmpty();
        int currentRows = 0;
        int otherRows = 0;
        boolean systemServerReported = false;
        boolean athenaReported = false;
        LinkedHashSet<String> currentIdentities = new LinkedHashSet<>();
        for (DiagnosticsHookState state : hookStates) {
            if (identityAvailable && currentBootId.equals(state.bootId)) {
                currentRows++;
                currentIdentities.add(hookStateIdentity(
                        state.processId, state.hookTargetId));
                if (DiagnosticsTargetIds.RUNTIME_PROCESS.equals(state.hookTargetId)) {
                    systemServerReported |= BridgeContract.PROCESS_SYSTEM_SERVER.equals(
                            state.processId);
                    athenaReported |= BridgeContract.PROCESS_ATHENA.equals(state.processId);
                }
            } else {
                otherRows++;
            }
        }
        int expectedRows = EXPECTED_CURRENT_BOOT_STATE_IDENTITIES.size();
        boolean coverageComplete = !hookStatus.truncated
                && hookStatus.errorCode.isEmpty()
                && currentRows >= expectedRows
                && systemServerReported
                && athenaReported
                && currentIdentities.containsAll(
                        EXPECTED_CURRENT_BOOT_STATE_IDENTITIES);
        return new DiagnosticsQuery.ExportBootStatus(
                identityAvailable,
                includeSensitiveDetails,
                currentBootId,
                currentRows,
                otherRows,
                expectedRows,
                systemServerReported,
                athenaReported,
                coverageComplete);
    }

    private static Set<String> expectedCurrentBootStateIdentities() {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (String target : HookTargetIds.all()) {
            String processId = HookTargetIds.expectedProcessId(target);
            if (processId.isEmpty()) {
                throw new IllegalStateException("Hook target process is unavailable");
            }
            identities.add(hookStateIdentity(processId, target));
        }
        for (String processId : new String[] {
                BridgeContract.PROCESS_SYSTEM_SERVER,
                BridgeContract.PROCESS_ATHENA }) {
            identities.add(hookStateIdentity(
                    processId, DiagnosticsTargetIds.RUNTIME_PROCESS));
            identities.add(hookStateIdentity(
                    processId, DiagnosticsTargetIds.RUNTIME_CONFIG));
        }
        return Collections.unmodifiableSet(identities);
    }

    private static String hookStateIdentity(String processId, String hookTargetId) {
        return DiagnosticsValues.id(processId) + '\n'
                + DiagnosticsValues.id(hookTargetId);
    }

    private static int safeIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static DiagnosticsQuery.HomeStatsSnapshot homeStatsFromAggregates(
            List<DiagnosticsQuery.AggregateRow> rows,
            Iterable<String> requestedFeatureIds,
            String errorCode) {
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> totals = new LinkedHashMap<>();
        if (rows != null) {
            for (DiagnosticsQuery.AggregateRow row : rows) {
                if (row == null || row.featureId.isEmpty()) continue;
                DiagnosticsQuery.FeatureStats current = totals.get(row.featureId);
                boolean latest = current == null || row.lastAt >= current.lastAt;
                totals.put(row.featureId, new DiagnosticsQuery.FeatureStats(
                        row.featureId,
                        saturatedAdd(current == null ? 0L : current.observed, row.eventCount),
                        saturatedAdd(current == null ? 0L : current.blocked, row.blockedCount),
                        saturatedAdd(current == null ? 0L : current.failOpen, row.failOpenCount),
                        Math.max(current == null ? 0L : current.lastAt, row.lastAt),
                        latest ? row.packageName : current.lastPackage,
                        "",
                        "",
                        "",
                        latest ? row.result : current.lastResult));
            }
        }
        if (requestedFeatureIds != null) {
            for (String requested : requestedFeatureIds) {
                String featureId = DiagnosticsValues.id(requested);
                if (!featureId.isEmpty()) {
                    totals.putIfAbsent(featureId, DiagnosticsQuery.FeatureStats.empty(featureId));
                }
            }
        }
        return new DiagnosticsQuery.HomeStatsSnapshot(totals, errorCode);
    }

    private static <T> List<T> copyAtMost(List<T> source, int limit) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        int size = Math.min(Math.max(0, limit), source.size());
        return Collections.unmodifiableList(new ArrayList<>(source.subList(0, size)));
    }

    private static String firstError(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String normalized = DiagnosticsValues.id(value);
            if (!normalized.isEmpty()) return normalized;
        }
        return "";
    }

    private static final class EventExportResult {
        final List<NormalizedDiagnosticsEvent> items;
        final int pagesRead;
        final boolean hasMore;
        final String errorCode;

        EventExportResult(
                List<NormalizedDiagnosticsEvent> items,
                int pagesRead,
                boolean hasMore,
                String errorCode) {
            this.items = items;
            this.pagesRead = pagesRead;
            this.hasMore = hasMore;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }
    }

    private static final class AggregateExportResult {
        final List<DiagnosticsQuery.AggregateRow> items;
        final int pagesRead;
        final boolean hasMore;
        final boolean historyTruncated;
        final String errorCode;

        AggregateExportResult(
                List<DiagnosticsQuery.AggregateRow> items,
                int pagesRead,
                boolean hasMore,
                boolean historyTruncated,
                String errorCode) {
            this.items = items;
            this.pagesRead = pagesRead;
            this.hasMore = hasMore;
            this.historyTruncated = historyTruncated;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }
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
                    .featureId(exportFeatureId(event.featureId, options))
                    .hookTargetId(exportHookTargetId(event.hookTargetId, options))
                    .behavior(exportBehavior(event.behavior, options))
                    .source(exportSource(event.source, options))
                    .mode(exportMode(event.mode, options))
                    .decision(exportDecision(event.decision, options))
                    .result(exportResult(event.result, options))
                    .eventToken("")
                    .build());
        }
        ArrayList<DiagnosticsQuery.AggregateRow> aggregates = new ArrayList<>();
        for (DiagnosticsQuery.AggregateRow row : source.aggregates) {
            aggregates.add(new DiagnosticsQuery.AggregateRow(
                    exportPackage(row.packageName, options),
                    options.includeUids ? row.uid : -1,
                    exportFeatureId(row.featureId, options),
                    exportHookTargetId(row.hookTargetId, options),
                    exportBehavior(row.behavior, options),
                    exportSource(row.source, options),
                    exportDecision(row.decision, options),
                    exportResult(row.result, options), row.rawHitCount, row.eventCount,
                    row.blockedCount, row.failOpenCount, row.identityResolvedCount,
                    row.latencyMicrosTotal, row.latencyMicrosMax, row.firstAt, row.lastAt));
        }
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> featureStats = new LinkedHashMap<>();
        for (Map.Entry<String, DiagnosticsQuery.FeatureStats> entry
                : source.homeStats.features.entrySet()) {
            DiagnosticsQuery.FeatureStats value = entry.getValue();
            String featureId = exportFeatureId(value.featureId, options);
            mergeExportedFeatureStats(featureStats, new DiagnosticsQuery.FeatureStats(
                    featureId, value.observed, value.blocked, value.failOpen,
                    value.lastAt,
                    exportPackage(value.lastPackage, options),
                    options.includeSensitiveDetails ? value.lastProcess : "",
                    options.includeSensitiveDetails ? value.lastScene : "",
                    options.includeSensitiveDetails ? value.lastReason : "",
                    exportResult(value.lastResult, options)));
        }
        ArrayList<DiagnosticsQuery.RawHookSummary> rawHooks = new ArrayList<>();
        for (DiagnosticsQuery.RawHookSummary value : source.rawHooks) {
            rawHooks.add(new DiagnosticsQuery.RawHookSummary(
                    options.includeSensitiveDetails ? value.processId : "",
                    exportFeatureId(value.featureId, options),
                    exportHookTargetId(value.hookTargetId, options),
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
                    .featureId(exportFeatureId(value.featureId, options))
                    .hookTargetId(exportHookTargetId(value.hookTargetId, options))
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
        DiagnosticsQuery.ExportStatus exportStatus;
        if (options.includeSensitiveDetails) {
            exportStatus = source.exportStatus;
        } else {
            exportStatus = new DiagnosticsQuery.ExportStatus(
                    source.exportStatus.recentEvents,
                    source.exportStatus.aggregates,
                    source.exportStatus.rawHooks,
                    source.exportStatus.hookStates,
                    featureStats.size(),
                    source.exportStatus.currentBoot.redacted(),
                    source.exportStatus.backendHealth);
        }
        return new DiagnosticsQuery.ExportSnapshot(
                source.formatVersion, source.databaseSchemaVersion, source.generatedAt,
                source.retentionMillis, options.includePackageNames, options.includeUids,
                options.includeSensitiveDetails,
                events, aggregates, rawHooks, hookStates,
                new DiagnosticsQuery.HomeStatsSnapshot(featureStats, source.homeStats.errorCode),
                exportStatus,
                source.errorCode);
    }

    private static void mergeExportedFeatureStats(
            Map<String, DiagnosticsQuery.FeatureStats> destination,
            DiagnosticsQuery.FeatureStats incoming) {
        DiagnosticsQuery.FeatureStats existing = destination.get(incoming.featureId);
        if (existing == null) {
            destination.put(incoming.featureId, incoming);
            return;
        }
        DiagnosticsQuery.FeatureStats latest = incoming.lastAt >= existing.lastAt
                ? incoming : existing;
        destination.put(incoming.featureId, new DiagnosticsQuery.FeatureStats(
                incoming.featureId,
                saturatedAdd(existing.observed, incoming.observed),
                saturatedAdd(existing.blocked, incoming.blocked),
                saturatedAdd(existing.failOpen, incoming.failOpen),
                Math.max(existing.lastAt, incoming.lastAt),
                latest.lastPackage,
                latest.lastProcess,
                latest.lastScene,
                latest.lastReason,
                latest.lastResult));
    }

    private static long saturatedAdd(long first, long second) {
        if (Long.MAX_VALUE - first < second) return Long.MAX_VALUE;
        return first + second;
    }

    private static String exportPackage(
            String packageName, DiagnosticsQuery.ExportOptions options) {
        if (!options.includePackageNames || packageName == null) return "";
        return packageName;
    }

    private static String stableInstallStatus(String value) {
        String normalized = DiagnosticsValues.id(value);
        if ("ENTRY_LOADED".equals(normalized)
                || "FEATURES_INSTALLED".equals(normalized)
                || "BOOTSTRAP_FAILED".equals(normalized)
                || "CONFIG_READY".equals(normalized)
                || "CONFIG_ERROR".equals(normalized)) return normalized;
        if (ModuleRuntimeStatus.isFeaturesInstalled(normalized)) {
            return "FEATURES_INSTALLED";
        }
        if (normalized.startsWith("INSTALLED")) return "INSTALLED";
        if (normalized.startsWith("FAILED")) return "FAILED";
        if (normalized.startsWith("UNSUPPORTED")) return "UNSUPPORTED";
        if (normalized.startsWith("SKIPPED")) return "SKIPPED";
        if (normalized.startsWith("PENDING")) return "PENDING";
        return "UNKNOWN";
    }

    private static String exportFeatureId(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails) return value;
        return FeatureIds.all().contains(value) ? value : UNKNOWN_EXPORTED_VALUE;
    }

    private static String exportHookTargetId(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails) return value;
        if (DiagnosticsTargetIds.isKnown(value)) return value;
        return UNKNOWN_EXPORTED_VALUE;
    }

    private static String exportBehavior(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails) return value;
        if ("KILL".equals(value) || "FREEZE".equals(value) || "WAKE".equals(value)
                || "RESOURCE".equals(value) || "REFREEZE".equals(value)
                || "RECOVERY".equals(value)) return value;
        return UNKNOWN_EXPORTED_VALUE;
    }

    private static String exportSource(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails || value == null || value.isEmpty()) return value;
        if ("NETWORK".equals(value)
                || "R_TO_M".equals(value)
                || "M_TO_F".equals(value)
                || "LCD_ON".equals(value)
                || enumContains(FreezeSource.class, value)
                || enumContains(WakeSource.class, value)
                || enumContains(ResourceType.class, value)) return value;
        return UNKNOWN_EXPORTED_VALUE;
    }

    private static String exportMode(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails || value == null || value.isEmpty()) return value;
        return enumContains(ResidencyMode.class, value) ? value : UNKNOWN_EXPORTED_VALUE;
    }

    private static String exportDecision(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails) return value;
        if (enumContains(DecisionReason.class, value)
                || "BLOCK_ACTION".equals(value)
                || "THROTTLE_BLOCK".equals(value)
                || "THROTTLE_ALLOW".equals(value)
                || "DRY_RUN".equals(value)
                || "FILTER_CANDIDATE".equals(value)
                || "RECOVER".equals(value)
                || "NO_ACTION".equals(value)
                || "SAFETY_ABORT".equals(value)
                || "FAIL_OPEN".equals(value)
                || "ALLOW_SYSTEM".equals(value)) return value;
        return UNKNOWN_EXPORTED_VALUE;
    }

    private static String exportResult(
            String value, DiagnosticsQuery.ExportOptions options) {
        if (options.includeSensitiveDetails) return value;
        if (DiagnosticsResult.PASSED.equals(value)
                || DiagnosticsResult.BLOCKED.equals(value)
                || DiagnosticsResult.OBSERVED.equals(value)
                || DiagnosticsResult.CANDIDATE_FILTERED.equals(value)
                || DiagnosticsResult.FAIL_OPEN.equals(value)
                || DiagnosticsResult.CONFIG_UNAVAILABLE.equals(value)
                || DiagnosticsResult.THROTTLE_ALLOWED.equals(value)
                || DiagnosticsResult.THROTTLE_BLOCKED.equals(value)
                || DiagnosticsResult.RUNTIME_FAIL_OPEN.equals(value)
                || DiagnosticsResult.RECOVERED.equals(value)
                || DiagnosticsResult.NO_ACTION_NEEDED.equals(value)
                || DiagnosticsResult.SAFETY_ABORTED.equals(value)
                || DiagnosticsResult.APPLIED.equals(value)) return value;
        return UNKNOWN_EXPORTED_VALUE;
    }

    private static <T extends Enum<T>> boolean enumContains(
            Class<T> type, String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Enum.valueOf(type, value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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

    interface LegacyMigrationSource {
        DiagnosticsWriteBatch read();
    }

    public static final class EnqueueResult {
        public final int accepted;
        public final int dropped;

        EnqueueResult(int accepted, int dropped) {
            this.accepted = Math.max(0, accepted);
            this.dropped = Math.max(0, dropped);
        }
    }

    public static final class DurableWriteResult {
        public final boolean persisted;
        public final boolean replayed;
        public final int itemCount;
        public final String errorCode;

        private DurableWriteResult(
                boolean persisted, boolean replayed, int itemCount, String errorCode) {
            this.persisted = persisted;
            this.replayed = replayed;
            this.itemCount = Math.max(0, itemCount);
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        static DurableWriteResult committed(int itemCount) {
            return new DurableWriteResult(true, false, itemCount, "");
        }

        static DurableWriteResult replayed(int itemCount) {
            return new DurableWriteResult(true, true, itemCount, "");
        }

        static DurableWriteResult rejected(int itemCount, String errorCode) {
            return new DurableWriteResult(false, false, itemCount, errorCode);
        }
    }

    private static final class PendingBatch {
        final DiagnosticsWriteBatch batch;
        final long generation;
        final int size;
        final CompletableFuture<DurableWriteResult> durableCompletion;

        PendingBatch(DiagnosticsWriteBatch batch, long generation) {
            this(batch, generation, null);
        }

        PendingBatch(
                DiagnosticsWriteBatch batch,
                long generation,
                CompletableFuture<DurableWriteResult> durableCompletion) {
            this.batch = batch;
            this.generation = generation;
            this.durableCompletion = durableCompletion;
            size = batch.size();
        }
    }
}
