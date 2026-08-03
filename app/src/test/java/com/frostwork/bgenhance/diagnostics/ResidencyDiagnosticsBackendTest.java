package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteFullException;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

public final class ResidencyDiagnosticsBackendTest {
    private final List<ResidencyDiagnosticsBackend> backends = new ArrayList<>();

    @After
    public void closeBackends() throws Exception {
        for (ResidencyDiagnosticsBackend backend : backends) {
            backend.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void hookFacingEnqueueDoesNotWriteOnCallingThread() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        String caller = Thread.currentThread().getName();

        assertTrue(backend.recordEvent(event("token-1", "com.example.target", 10123)));
        assertEquals(0, storage.written);

        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(1, storage.written);
        assertNotEquals(caller, storage.writeThread);
        assertEquals(1L, backend.health().committedWrites);
    }

    @Test
    public void startupAndSuccessfulWriteEachRunMaintenanceOnWorker() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));

        assertTrue(backend.recordEvent(event("token", "pkg", 1)));
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(Arrays.asList("maintenance_regular", "write", "maintenance_regular"),
                storage.operationSnapshot());
        assertNotEquals(Thread.currentThread().getName(), storage.maintenanceThread);
    }

    @Test
    public void legacyPreferencesAreReadAndMigratedOnBackendWorker() throws Exception {
        FakeStorage storage = new FakeStorage();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        String callerThread = Thread.currentThread().getName();
        ResidencyDiagnosticsBackend backend = new ResidencyDiagnosticsBackend(
                policy(8, 4, 60_000L), storage, executor, "boot", () -> {
            storage.migrationReadThread = Thread.currentThread().getName();
            return DiagnosticsWriteBatch.builder()
                    .addLegacyFeatureDelta(new DiagnosticsWriteBatch.LegacyFeatureDelta(
                            "legacy", 1L, 0L, 0L, 1L,
                            "", "", "", "", "passed"))
                    .build();
        });
        backends.add(backend);

        backend.currentEpochAsync().get(5, TimeUnit.SECONDS);

        assertNotEquals(callerThread, storage.migrationReadThread);
        assertEquals(storage.migrationReadThread, storage.migrationWriteThread);
        assertEquals(1, storage.migrationCalls);
        assertEquals(1, storage.migratedBatch.size());
    }

    @Test
    public void periodicMaintenanceRunsWithoutWritesOrQueries() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.maintenanceLatch = new CountDownLatch(2);
        ResidencyDiagnosticsBackend backend = backend(
                storage, policy(8, 4, 60_000L, 20L));

        assertTrue(storage.maintenanceLatch.await(5, TimeUnit.SECONDS));
        assertTrue(storage.regularMaintenanceCalls >= 2);
        assertEquals(0, storage.written);
        assertFalse(backend.health().closed);
    }

    @Test
    public void fullWriteCleansUpAndRetriesExactlyOnce() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.fullWriteFailures = 1;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));

        assertTrue(backend.recordEvent(event("token", "pkg", 1)));
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(2, storage.writeAttempts);
        assertEquals(1, storage.pressureMaintenanceCalls);
        assertEquals(1, storage.written);
        assertEquals(1L, backend.health().committedWrites);
        assertEquals(0L, backend.health().droppedWrites);
    }

    @Test
    public void persistentFullWriteIsDroppedAfterSingleRetry() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.alwaysFull = true;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));

        assertTrue(backend.recordEvent(event("token", "pkg", 1)));
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(2, storage.writeAttempts);
        assertEquals(1, storage.pressureMaintenanceCalls);
        assertEquals(0, storage.written);
        assertEquals(0L, backend.health().committedWrites);
        assertEquals(1L, backend.health().droppedWrites);
        assertEquals(1L, backend.health().failedBatches);
    }

    @Test
    public void storageAdmissionRejectionIsDroppedInsteadOfCountedAsCommitted()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.writeResult = ResidencyDiagnosticsStorage.WriteResult.REJECTED_CAPACITY;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));

        assertTrue(backend.recordEvent(event("capacity", "pkg", 1)));
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(1L, backend.health().acceptedWrites);
        assertEquals(0L, backend.health().committedWrites);
        assertEquals(1L, backend.health().droppedWrites);
        assertEquals(1L, backend.health().failedBatches);
        assertEquals(0, storage.written);
    }

    @Test
    public void currentEpochInitializationFailureCompletesExceptionally() {
        FakeStorage storage = new FakeStorage();
        storage.failEpochReads = true;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> backend.currentEpochAsync().get(5, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("epoch read failed", failure.getCause().getMessage());
        assertEquals(1L, backend.health().failedBatches);
    }

    @Test
    public void queueOverflowDropsDiagnosticsWithoutBlockingOrThrowing() {
        ScheduledThreadPoolExecutor stopped = new ScheduledThreadPoolExecutor(1);
        stopped.shutdownNow();
        ResidencyDiagnosticsBackend backend = new ResidencyDiagnosticsBackend(
                policy(2, 1, 60_000L), new FakeStorage(), stopped, "boot");
        backends.add(backend);

        assertTrue(backend.recordEvent(event("one", "a", 1)));
        assertTrue(backend.recordEvent(event("two", "b", 2)));
        assertFalse(backend.recordEvent(event("three", "c", 3)));
        assertEquals(2L, backend.health().acceptedWrites);
        assertTrue(backend.health().droppedWrites >= 1L);
        assertEquals(2, backend.health().queuedWrites);
    }

    @Test
    public void wireBatchIsAcceptedOrRejectedAsOneQueueUnit() {
        ScheduledThreadPoolExecutor stopped = new ScheduledThreadPoolExecutor(1);
        stopped.shutdownNow();
        ResidencyDiagnosticsBackend backend = new ResidencyDiagnosticsBackend(
                policy(3, 1, 60_000L), new FakeStorage(), stopped, "boot");
        backends.add(backend);
        DiagnosticsWriteBatch twoItems = DiagnosticsWriteBatch.builder()
                .addEvent(event("one", "a", 1))
                .addEvent(event("two", "b", 2))
                .build();

        ResidencyDiagnosticsBackend.EnqueueResult accepted = backend.enqueue(twoItems);
        ResidencyDiagnosticsBackend.EnqueueResult rejected = backend.enqueue(twoItems);

        assertEquals(2, accepted.accepted);
        assertEquals(0, accepted.dropped);
        assertEquals(0, rejected.accepted);
        assertEquals(2, rejected.dropped);
        assertEquals(2, backend.health().queuedWrites);
    }

    @Test
    public void workerPreservesVersionedIdentityThroughPersistence() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 3L;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        assertEquals(3L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        DiagnosticsWriteBatch batch = DiagnosticsWriteBatch.builder()
                .wireIdentity(DiagnosticsWireParser.WIRE_VERSION,
                        "boot", "system_server", "sender-a", 3L, 9L,
                        testPayloadHash("wire"))
                .addEvent(event("wire", "pkg", 1))
                .build();

        assertEquals(1, backend.enqueue(batch).accepted);
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(batch.batchIdentity, storage.lastBatchIdentity);
    }

    @Test
    public void durableCompletionWaitsForWorkerCommitAndRejectsPendingDuplicate()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        assertEquals(0L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        storage.blockWrites();
        DiagnosticsWriteBatch batch = versionedEventBatch(0L, 0L, "durable");
        String callerThread = Thread.currentThread().getName();

        CompletableFuture<ResidencyDiagnosticsBackend.DurableWriteResult> first =
                backend.enqueueDurable(batch);
        assertTrue(storage.writeStarted.await(5, TimeUnit.SECONDS));
        assertFalse(first.isDone());
        assertNotEquals(callerThread, storage.writeThread);

        ResidencyDiagnosticsBackend.DurableWriteResult duplicate =
                backend.enqueueDurable(batch).get(5, TimeUnit.SECONDS);
        assertFalse(duplicate.persisted);
        assertEquals("batch_pending", duplicate.errorCode);
        assertEquals(1, storage.writeAttempts);

        storage.releaseWrites.countDown();
        ResidencyDiagnosticsBackend.DurableWriteResult committed =
                first.get(5, TimeUnit.SECONDS);
        assertTrue(committed.persisted);
        assertFalse(committed.replayed);
        assertEquals(batch.size(), committed.itemCount);
        assertEquals(1L, backend.health().committedWrites);
    }

    @Test
    public void durableCompletionOnlyAcceptsCommittedOrReplayedResults()
            throws Exception {
        for (ResidencyDiagnosticsStorage.WriteResult writeResult
                : ResidencyDiagnosticsStorage.WriteResult.values()) {
            FakeStorage storage = new FakeStorage();
            storage.writeResult = writeResult;
            ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
            backend.currentEpochAsync().get(5, TimeUnit.SECONDS);

            ResidencyDiagnosticsBackend.DurableWriteResult result = backend.enqueueDurable(
                    versionedEventBatch(0L, 0L, writeResult.name()))
                    .get(5, TimeUnit.SECONDS);

            boolean expected = writeResult == ResidencyDiagnosticsStorage.WriteResult.COMMITTED
                    || writeResult == ResidencyDiagnosticsStorage.WriteResult.REPLAYED;
            assertEquals(writeResult.name(), expected, result.persisted);
            assertEquals(writeResult == ResidencyDiagnosticsStorage.WriteResult.REPLAYED,
                    result.replayed);
            if (!expected) assertFalse(result.errorCode.isEmpty());
        }
    }

    @Test
    public void durableStorageFailureReturnsRetryableRejection() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.failWrites = true;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        backend.currentEpochAsync().get(5, TimeUnit.SECONDS);

        ResidencyDiagnosticsBackend.DurableWriteResult result = backend.enqueueDurable(
                versionedEventBatch(0L, 0L, "failure")).get(5, TimeUnit.SECONDS);

        assertFalse(result.persisted);
        assertEquals("storage_unavailable", result.errorCode);
        assertEquals(0L, backend.health().committedWrites);

        storage.failWrites = false;
        ResidencyDiagnosticsBackend.DurableWriteResult retry = backend.enqueueDurable(
                versionedEventBatch(0L, 0L, "failure")).get(5, TimeUnit.SECONDS);
        assertTrue(retry.persisted);
        assertEquals(1, storage.written);
    }

    @Test
    public void durableSchedulingFailureCompletesAndReleasesPendingIdentity()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        RejectOnceExecutor executor = new RejectOnceExecutor();
        ResidencyDiagnosticsBackend backend = new ResidencyDiagnosticsBackend(
                policy(8, 4, 60_000L), storage, executor, "boot");
        backends.add(backend);
        backend.currentEpochAsync().get(5, TimeUnit.SECONDS);
        DiagnosticsWriteBatch batch = versionedEventBatch(0L, 0L, "schedule");
        executor.rejectNextSchedule();

        ResidencyDiagnosticsBackend.DurableWriteResult rejected =
                backend.enqueueDurable(batch).get(5, TimeUnit.SECONDS);

        assertFalse(rejected.persisted);
        assertEquals("backend_unavailable", rejected.errorCode);
        assertEquals(0, backend.health().queuedWrites);
        assertEquals(0, storage.writeAttempts);

        ResidencyDiagnosticsBackend.DurableWriteResult retry =
                backend.enqueueDurable(batch).get(5, TimeUnit.SECONDS);
        assertTrue(retry.persisted);
        assertEquals(1, storage.writeAttempts);
    }

    @Test
    public void closeWaitsForInFlightDurableCommit() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        backend.currentEpochAsync().get(5, TimeUnit.SECONDS);
        storage.blockWrites();

        CompletableFuture<ResidencyDiagnosticsBackend.DurableWriteResult> durable =
                backend.enqueueDurable(versionedEventBatch(0L, 0L, "close-in-flight"));
        assertTrue(storage.writeStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> close = backend.closeAsync();

        assertFalse(durable.isDone());
        assertFalse(close.isDone());
        storage.releaseWrites.countDown();
        assertTrue(durable.get(5, TimeUnit.SECONDS).persisted);
        close.get(5, TimeUnit.SECONDS);
        assertTrue(storage.closed);
    }

    @Test
    public void rejectedCloseSchedulingCompletesQueuedDurableFuture()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        RejectOnceExecutor executor = new RejectOnceExecutor();
        ResidencyDiagnosticsBackend backend = new ResidencyDiagnosticsBackend(
                policy(8, 4, 60_000L), storage, executor, "boot");
        backends.add(backend);
        backend.currentEpochAsync().get(5, TimeUnit.SECONDS);
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerBlocked.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerBlocked.await(5, TimeUnit.SECONDS));
        CompletableFuture<ResidencyDiagnosticsBackend.DurableWriteResult> durable =
                backend.enqueueDurable(versionedEventBatch(0L, 0L, "close-rejected"));
        executor.rejectNextExecute();

        backend.closeAsync().get(5, TimeUnit.SECONDS);
        ResidencyDiagnosticsBackend.DurableWriteResult rejected =
                durable.get(5, TimeUnit.SECONDS);

        releaseWorker.countDown();
        assertFalse(rejected.persisted);
        assertEquals("backend_closed", rejected.errorCode);
        assertEquals(0, backend.health().queuedWrites);
    }

    @Test
    public void clearClosesWireAdmissionBeforeWorkerBarrierAndPublishesNewEpoch()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 7L;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        assertEquals(7L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        assertEquals(7L, backend.admissionEpoch());

        storage.blockQueries();
        CompletableFuture<DiagnosticsQuery.EventPage> blocker =
                backend.queryEventsAsync(null);
        assertTrue(storage.queryStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<DiagnosticsQuery.ClearResult> clearing = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY);

        assertEquals(-1L, backend.admissionEpoch());
        ResidencyDiagnosticsBackend.EnqueueResult stale = backend.enqueue(
                versionedEventBatch(7L, 0L, "stale"));
        assertEquals(0, stale.accepted);
        assertEquals(1, stale.dropped);

        storage.releaseQueries.countDown();
        blocker.get(5, TimeUnit.SECONDS);
        DiagnosticsQuery.ClearResult clear = clearing.get(5, TimeUnit.SECONDS);
        assertTrue(clear.success);
        assertEquals(8L, clear.epoch);
        assertEquals(8L, backend.admissionEpoch());

        ResidencyDiagnosticsBackend.EnqueueResult current = backend.enqueue(
                versionedEventBatch(8L, 0L, "current"));
        assertEquals(1, current.accepted);
        backend.flushAsync().get(5, TimeUnit.SECONDS);
        assertEquals(1, storage.events.size());
        assertEquals("current", storage.events.get(0).eventToken);
    }

    @Test
    public void epochReadQueuedBeforeClearCannotReopenOldWireAdmission()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 4L;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        assertEquals(4L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());

        storage.blockQueries();
        storage.blockClears();
        CompletableFuture<DiagnosticsQuery.EventPage> blocker =
                backend.queryEventsAsync(null);
        assertTrue(storage.queryStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Long> staleRead = backend.currentEpochAsync();
        CompletableFuture<DiagnosticsQuery.ClearResult> clearing = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY);

        storage.releaseQueries.countDown();
        blocker.get(5, TimeUnit.SECONDS);
        assertEquals(4L, staleRead.get(5, TimeUnit.SECONDS).longValue());
        assertTrue(storage.clearStarted.await(5, TimeUnit.SECONDS));
        assertEquals(-1L, backend.admissionEpoch());

        storage.releaseClears.countDown();
        assertEquals(5L, clearing.get(5, TimeUnit.SECONDS).epoch);
        assertEquals(5L, backend.admissionEpoch());
    }

    @Test
    public void failedClearRestoresLastPersistedWireEpoch() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 9L;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        assertEquals(9L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        storage.failClears = true;

        DiagnosticsQuery.ClearResult failed = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY).get(5, TimeUnit.SECONDS);

        assertFalse(failed.success);
        assertEquals(9L, backend.admissionEpoch());
        assertEquals(1, backend.enqueue(versionedEventBatch(9L, 0L, "retained")).accepted);
        backend.flushAsync().get(5, TimeUnit.SECONDS);
        assertEquals(1, storage.events.size());
    }

    @Test
    public void uncertainCommittedClearKeepsAdmissionClosedUntilEpochCanBeRead()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 11L;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        assertEquals(11L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        storage.clearCommitsThenFails = true;
        storage.failEpochReadsAfterClear = true;

        DiagnosticsQuery.ClearResult failed = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY).get(5, TimeUnit.SECONDS);

        assertFalse(failed.success);
        assertEquals(12L, storage.epoch);
        assertEquals(-1L, backend.admissionEpoch());
        assertEquals(0, backend.enqueue(versionedEventBatch(11L, 0L, "old")).accepted);
        assertEquals(0, backend.enqueue(versionedEventBatch(12L, 0L, "new")).accepted);

        storage.failEpochReads = false;
        assertEquals(12L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        assertEquals(12L, backend.admissionEpoch());
        assertEquals(1, backend.enqueue(versionedEventBatch(12L, 0L, "recovered")).accepted);
    }

    @Test
    public void rejectedSecondClearRollsBackGenerationAndLetsFirstPublish()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 2L;
        RejectOnceExecutor executor = new RejectOnceExecutor();
        ResidencyDiagnosticsBackend backend = new ResidencyDiagnosticsBackend(
                policy(8, 4, 60_000L), storage, executor, "boot");
        backends.add(backend);
        assertEquals(2L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());

        storage.blockQueries();
        CompletableFuture<DiagnosticsQuery.EventPage> blocker =
                backend.queryEventsAsync(null);
        assertTrue(storage.queryStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<DiagnosticsQuery.ClearResult> first = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY);
        executor.rejectNextExecute();
        DiagnosticsQuery.ClearResult rejected = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY).get(5, TimeUnit.SECONDS);

        assertFalse(rejected.success);
        assertEquals(-1L, backend.admissionEpoch());
        storage.releaseQueries.countDown();
        blocker.get(5, TimeUnit.SECONDS);
        DiagnosticsQuery.ClearResult completed = first.get(5, TimeUnit.SECONDS);
        assertTrue(completed.success);
        assertEquals(3L, completed.epoch);
        assertEquals(3L, backend.admissionEpoch());
        assertEquals(1, backend.enqueue(versionedEventBatch(3L, 0L, "after")).accepted);
    }

    @Test
    public void versionedBatchFromDifferentBootIsRejectedBeforeQueueing() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        NormalizedDiagnosticsEvent stale = event("stale", "pkg", 1).copyBuilder()
                .bootId("stale-boot")
                .build();
        DiagnosticsWriteBatch batch = DiagnosticsWriteBatch.builder()
                .wireIdentity(DiagnosticsWireParser.WIRE_VERSION,
                        "stale-boot", "system_server", "sender-a", 0L, 0L,
                        testPayloadHash("stale"))
                .addEvent(stale)
                .build();

        ResidencyDiagnosticsBackend.EnqueueResult result = backend.enqueue(batch);
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(0, result.accepted);
        assertEquals(1, result.dropped);
        assertEquals(0, storage.written);
    }

    @Test
    public void unversionedPayloadFromDifferentBootIsRejectedBeforeQueueing() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        DiagnosticsWriteBatch batch = DiagnosticsWriteBatch.builder()
                .addEvent(event("stale-event", "pkg", 1).copyBuilder()
                        .bootId("stale-boot")
                        .build())
                .build();

        ResidencyDiagnosticsBackend.EnqueueResult result = backend.enqueue(batch);
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertEquals(0, result.accepted);
        assertEquals(1, result.dropped);
        assertEquals(0, storage.written);
    }

    @Test
    public void storageFailuresCompleteQueriesAndFlushFailOpen() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.failWrites = true;
        storage.failQueries = true;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 4, 60_000L));
        backend.recordEvent(event("token", "pkg", 1));

        backend.flushAsync().get(5, TimeUnit.SECONDS);
        DiagnosticsQuery.EventPage page = backend.queryEventsAsync(
                DiagnosticsQuery.EventFilter.builder().build()).get(5, TimeUnit.SECONDS);
        DiagnosticsQuery.HookStatePage hookStates = backend.queryHookStatesAsync(10)
                .get(5, TimeUnit.SECONDS);

        assertTrue(page.items.isEmpty());
        assertEquals("database_unavailable", page.errorCode);
        assertTrue(hookStates.items.isEmpty());
        assertEquals("database_unavailable", hookStates.errorCode);
        assertTrue(backend.health().failedBatches >= 3L);
        assertTrue(backend.health().droppedWrites >= 1L);
    }

    @Test
    public void clearIsOrderedAfterAcceptedWrites() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));
        backend.recordEvent(event("before", "pkg.before", 1));

        DiagnosticsQuery.ClearResult result = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY).get(5, TimeUnit.SECONDS);
        backend.recordEvent(event("after", "pkg.after", 2));
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        assertTrue(result.success);
        assertEquals(1, storage.clearCalls);
        assertEquals(1, storage.events.size());
        assertEquals("pkg.after", storage.events.get(0).packageName);
    }

    @Test
    public void defaultExportRemovesAllIdentityAndFreeText() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.events.add(event("private-token", "com.private.target", 10123)
                .copyBuilder()
                .scene("private scene text")
                .failOpenReason("private exception message")
                .build());
        storage.rawHooks.add(new DiagnosticsQuery.RawHookSummary(
                "system_server", FeatureIds.HANS, HookTargetIds.HANS_FREEZE_DISPATCH,
                1L, 1L, 1L, 1L, "private raw error"));
        storage.hookStates.add(DiagnosticsHookState.builder()
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                .installStatus("FAILED_FAIL_OPEN:PrivateException")
                .bootId("boot")
                .lastError("private hook error")
                .updatedAt(1L)
                .build());
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> home = new LinkedHashMap<>();
        home.put(FeatureIds.HANS, new DiagnosticsQuery.FeatureStats(
                FeatureIds.HANS, 1L, 1L, 0L, 1L,
                "com.private.target", "system_server", "private scene",
                "private reason", "blocked"));
        storage.homeStats = new DiagnosticsQuery.HomeStatsSnapshot(home, "");
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));

        DiagnosticsQuery.ExportSnapshot snapshot = backend.exportAsync(
                DiagnosticsQuery.ExportOptions.redactedDefaults(),
                Collections.singletonList(FeatureIds.HANS)).get(5, TimeUnit.SECONDS);

        assertEquals("", snapshot.recentEvents.get(0).packageName);
        assertEquals(-1, snapshot.recentEvents.get(0).uid);
        assertEquals("", snapshot.recentEvents.get(0).bootId);
        assertEquals("", snapshot.recentEvents.get(0).processId);
        assertEquals("", snapshot.recentEvents.get(0).scene);
        assertEquals("", snapshot.recentEvents.get(0).failOpenReason);
        assertEquals("", snapshot.recentEvents.get(0).eventToken);
        DiagnosticsQuery.FeatureStats feature = snapshot.homeStats.features.get(FeatureIds.HANS);
        assertEquals("", feature.lastPackage);
        assertEquals("", feature.lastProcess);
        assertEquals("", feature.lastScene);
        assertEquals("", feature.lastReason);
        assertEquals("", snapshot.rawHooks.get(0).processId);
        assertEquals("", snapshot.rawHooks.get(0).lastError);
        assertEquals("", snapshot.hookStates.get(0).processId);
        assertEquals("", snapshot.hookStates.get(0).bootId);
        assertEquals("", snapshot.hookStates.get(0).lastError);
        assertEquals("FAILED", snapshot.hookStates.get(0).installStatus);
        assertFalse(snapshot.packageNamesIncluded);
        assertFalse(snapshot.uidsIncluded);
        assertFalse(snapshot.sensitiveDetailsIncluded);
    }

    @Test
    public void defaultExportReplacesUnknownProtocolValues() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.events.add(event("private-token", "pkg", 1).copyBuilder()
                .featureId("private_feature")
                .hookTargetId("private_hook")
                .behavior("PRIVATE_BEHAVIOR")
                .source("PRIVATE_SOURCE")
                .mode("PRIVATE_MODE")
                .decision("PRIVATE_DECISION")
                .result("private_result")
                .build());
        storage.rawHooks.add(new DiagnosticsQuery.RawHookSummary(
                "system_server", "private_feature", "private_hook",
                1L, 1L, 1L, 1L, ""));
        storage.hookStates.add(DiagnosticsHookState.builder()
                .processId("system_server")
                .featureId("private_feature")
                .hookTargetId("private_hook")
                .installStatus("INSTALLED:1")
                .bootId("boot")
                .updatedAt(1L)
                .build());
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> home = new LinkedHashMap<>();
        home.put("private_feature", new DiagnosticsQuery.FeatureStats(
                "private_feature", 1L, 0L, 0L, 1L,
                "", "", "", "", "private_result"));
        storage.homeStats = new DiagnosticsQuery.HomeStatsSnapshot(home, "");
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));

        DiagnosticsQuery.ExportSnapshot snapshot = backend.exportAsync(
                DiagnosticsQuery.ExportOptions.redactedDefaults(),
                Collections.singletonList("private_feature")).get(5, TimeUnit.SECONDS);

        NormalizedDiagnosticsEvent event = snapshot.recentEvents.get(0);
        assertEquals("unknown", event.featureId);
        assertEquals("unknown", event.hookTargetId);
        assertEquals("unknown", event.behavior);
        assertEquals("unknown", event.source);
        assertEquals("unknown", event.mode);
        assertEquals("unknown", event.decision);
        assertEquals("unknown", event.result);
        assertEquals("unknown", snapshot.rawHooks.get(0).featureId);
        assertEquals("unknown", snapshot.rawHooks.get(0).hookTargetId);
        assertEquals("unknown", snapshot.hookStates.get(0).featureId);
        assertEquals("unknown", snapshot.hookStates.get(0).hookTargetId);
        assertEquals("unknown", snapshot.homeStats.features.get("unknown").lastResult);
    }

    @Test
    public void defaultExportMergesUnknownFeatureStatsWithoutLosingTotals() throws Exception {
        FakeStorage storage = new FakeStorage();
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> home = new LinkedHashMap<>();
        home.put("private_feature_one", new DiagnosticsQuery.FeatureStats(
                "private_feature_one", 3L, 1L, 1L, 100L,
                "pkg.one", "process.one", "scene one", "reason one", "blocked"));
        home.put("private_feature_two", new DiagnosticsQuery.FeatureStats(
                "private_feature_two", 5L, 2L, 0L, 200L,
                "pkg.two", "process.two", "scene two", "reason two", "passed"));
        storage.homeStats = new DiagnosticsQuery.HomeStatsSnapshot(home, "");
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));

        DiagnosticsQuery.ExportSnapshot snapshot = backend.exportAsync(
                DiagnosticsQuery.ExportOptions.redactedDefaults(),
                home.keySet()).get(5, TimeUnit.SECONDS);

        assertEquals(1, snapshot.homeStats.features.size());
        DiagnosticsQuery.FeatureStats unknown = snapshot.homeStats.features.get("unknown");
        assertEquals(8L, unknown.observed);
        assertEquals(3L, unknown.blocked);
        assertEquals(1L, unknown.failOpen);
        assertEquals(200L, unknown.lastAt);
        assertEquals("passed", unknown.lastResult);
        assertEquals("", unknown.lastPackage);
        assertEquals("", unknown.lastProcess);
        assertEquals(8L, snapshot.homeStats.observed);
        assertEquals(3L, snapshot.homeStats.blocked);
        assertEquals(1L, snapshot.homeStats.failOpen);
    }

    @Test
    public void defaultExportSaturatesCollidingUnknownFeatureTotals() throws Exception {
        FakeStorage storage = new FakeStorage();
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> home = new LinkedHashMap<>();
        home.put("private_feature_one", new DiagnosticsQuery.FeatureStats(
                "private_feature_one", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                1L, "", "", "", "", "passed"));
        home.put("private_feature_two", new DiagnosticsQuery.FeatureStats(
                "private_feature_two", 1L, 1L, 1L,
                2L, "", "", "", "", "blocked"));
        storage.homeStats = new DiagnosticsQuery.HomeStatsSnapshot(home, "");
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));

        DiagnosticsQuery.ExportSnapshot snapshot = backend.exportAsync(
                DiagnosticsQuery.ExportOptions.redactedDefaults(),
                home.keySet()).get(5, TimeUnit.SECONDS);

        DiagnosticsQuery.FeatureStats unknown = snapshot.homeStats.features.get("unknown");
        assertEquals(Long.MAX_VALUE, unknown.observed);
        assertEquals(Long.MAX_VALUE, unknown.blocked);
        assertEquals(Long.MAX_VALUE, unknown.failOpen);
    }

    @Test
    public void fullExportKeepsUnknownFeatureStatsSeparate() throws Exception {
        FakeStorage storage = new FakeStorage();
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> home = new LinkedHashMap<>();
        home.put("private_feature_one", DiagnosticsQuery.FeatureStats.empty(
                "private_feature_one"));
        home.put("private_feature_two", DiagnosticsQuery.FeatureStats.empty(
                "private_feature_two"));
        storage.homeStats = new DiagnosticsQuery.HomeStatsSnapshot(home, "");
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));

        DiagnosticsQuery.ExportSnapshot snapshot = backend.exportAsync(
                DiagnosticsQuery.ExportOptions.builder()
                        .includePackageNames(true)
                        .includeUids(true)
                        .includeSensitiveDetails(true)
                        .build(),
                home.keySet()).get(5, TimeUnit.SECONDS);

        assertEquals(2, snapshot.homeStats.features.size());
        assertTrue(snapshot.homeStats.features.containsKey("private_feature_one"));
        assertTrue(snapshot.homeStats.features.containsKey("private_feature_two"));
    }

    @Test
    public void explicitFullExportPreservesRequestedIdentity() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.events.add(event("private-token", "com.private.target", 10123)
                .copyBuilder()
                .scene("private scene text")
                .failOpenReason("private error")
                .build());
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));

        DiagnosticsQuery.ExportSnapshot snapshot = backend.exportAsync(
                DiagnosticsQuery.ExportOptions.builder()
                        .includePackageNames(true)
                        .includeUids(true)
                        .includeSensitiveDetails(true)
                        .build(),
                Collections.singletonList("freeze")).get(5, TimeUnit.SECONDS);

        NormalizedDiagnosticsEvent exported = snapshot.recentEvents.get(0);
        assertEquals("com.private.target", exported.packageName);
        assertEquals(10123, exported.uid);
        assertEquals("boot", exported.bootId);
        assertEquals("system_server", exported.processId);
        assertEquals("private scene text", exported.scene);
        assertEquals("private error", exported.failOpenReason);
        assertEquals("", exported.eventToken);
        assertTrue(snapshot.sensitiveDetailsIncluded);
    }

    @Test
    public void concurrentQueriesAreAdmissionBoundedAndRemainSerialized() throws Exception {
        FakeStorage storage = new FakeStorage();
        ResidencyDiagnosticsBackend backend = backend(storage, policy(2, 1, 60_000L));
        backend.flushAsync().get(5, TimeUnit.SECONDS);
        storage.blockQueries();

        CompletableFuture<DiagnosticsQuery.EventPage> first = backend.queryEventsAsync(null);
        assertTrue(storage.queryStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<DiagnosticsQuery.EventPage> second = backend.queryEventsAsync(null);
        DiagnosticsQuery.EventPage rejected = backend.queryEventsAsync(null)
                .get(5, TimeUnit.SECONDS);

        assertEquals("database_unavailable", rejected.errorCode);
        storage.releaseQueries.countDown();
        assertEquals("", first.get(5, TimeUnit.SECONDS).errorCode);
        assertEquals("", second.get(5, TimeUnit.SECONDS).errorCode);
        assertEquals(2, storage.queryCalls);
    }

    @Test
    public void closeIsBarrierForAcceptedQueriesAndIsIdempotent() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.epoch = 5L;
        ResidencyDiagnosticsBackend backend = backend(storage, policy(8, 2, 60_000L));
        assertEquals(5L, backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue());
        backend.flushAsync().get(5, TimeUnit.SECONDS);
        storage.blockQueries();
        CompletableFuture<DiagnosticsQuery.EventPage> accepted =
                backend.queryEventsAsync(null);
        assertTrue(storage.queryStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> close = backend.closeAsync();
        assertEquals(-1L, backend.admissionEpoch());
        DiagnosticsQuery.EventPage afterBarrier = backend.queryEventsAsync(null)
                .get(5, TimeUnit.SECONDS);

        assertFalse(close.isDone());
        assertEquals("database_unavailable", afterBarrier.errorCode);
        storage.releaseQueries.countDown();
        assertEquals("", accepted.get(5, TimeUnit.SECONDS).errorCode);
        close.get(5, TimeUnit.SECONDS);
        assertTrue(storage.closed);
        assertSame(close, backend.closeAsync());
    }

    private ResidencyDiagnosticsBackend backend(
            FakeStorage storage, DiagnosticsRetentionPolicy policy) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        ResidencyDiagnosticsBackend backend =
                new ResidencyDiagnosticsBackend(policy, storage, executor, "boot");
        backends.add(backend);
        return backend;
    }

    private static DiagnosticsRetentionPolicy policy(
            int queueCapacity, int batchSize, long delayMillis) {
        return policy(queueCapacity, batchSize, delayMillis, 60_000L);
    }

    private static DiagnosticsRetentionPolicy policy(
            int queueCapacity, int batchSize, long delayMillis, long maintenanceMillis) {
        return DiagnosticsRetentionPolicy.builder()
                .queueCapacity(queueCapacity)
                .batchSize(batchSize)
                .flushDelayMillis(delayMillis)
                .maintenanceIntervalMillis(maintenanceMillis)
                .maxDatabaseBytes(1_048_576L)
                .build();
    }

    private static NormalizedDiagnosticsEvent event(
            String token, String packageName, int uid) {
        return NormalizedDiagnosticsEvent.builder()
                .timestampMillis(1_700_000_000_000L)
                .bootId("boot")
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .behavior("FREEZE")
                .source("FAST")
                .packageName(packageName)
                .uid(uid)
                .decision("BLOCK_FREEZE")
                .result("blocked")
                .eventToken(token)
                .build();
    }

    private static DiagnosticsWriteBatch versionedEventBatch(
            long epoch, long sequence, String token) {
        return DiagnosticsWriteBatch.builder()
                .wireIdentity(DiagnosticsWireParser.WIRE_VERSION,
                        "boot", "system_server", "sender-a", epoch, sequence,
                        testPayloadHash(token))
                .countingMode(DiagnosticsWriteBatch.CountingMode.TYPED)
                .addEvent(event(token, "pkg", 1))
                .build();
    }

    private static String testPayloadHash(String seed) {
        return String.format(java.util.Locale.ROOT, "%08x", seed.hashCode()).repeat(8);
    }

    private static final class FakeStorage implements ResidencyDiagnosticsStorage {
        final List<NormalizedDiagnosticsEvent> events = new ArrayList<>();
        final List<DiagnosticsQuery.RawHookSummary> rawHooks = new ArrayList<>();
        final List<DiagnosticsHookState> hookStates = new ArrayList<>();
        volatile int written;
        volatile int writeAttempts;
        volatile int clearCalls;
        volatile int migrationCalls;
        volatile int regularMaintenanceCalls;
        volatile int pressureMaintenanceCalls;
        volatile int queryCalls;
        volatile String writeThread = "";
        volatile String maintenanceThread = "";
        volatile String migrationReadThread = "";
        volatile String migrationWriteThread = "";
        volatile boolean failWrites;
        volatile boolean failQueries;
        volatile boolean failEpochReads;
        volatile boolean failClears;
        volatile boolean clearCommitsThenFails;
        volatile boolean failEpochReadsAfterClear;
        volatile long epoch;
        volatile boolean alwaysFull;
        volatile boolean closed;
        volatile int fullWriteFailures;
        volatile CountDownLatch maintenanceLatch;
        volatile CountDownLatch writeStarted;
        volatile CountDownLatch releaseWrites;
        volatile CountDownLatch queryStarted;
        volatile CountDownLatch releaseQueries;
        volatile CountDownLatch clearStarted;
        volatile CountDownLatch releaseClears;
        volatile DiagnosticsWriteBatch.BatchIdentity lastBatchIdentity;
        volatile DiagnosticsWriteBatch migratedBatch;
        volatile ResidencyDiagnosticsStorage.WriteResult writeResult =
                ResidencyDiagnosticsStorage.WriteResult.COMMITTED;
        final List<String> operations = new ArrayList<>();
        volatile DiagnosticsQuery.HomeStatsSnapshot homeStats =
                DiagnosticsQuery.HomeStatsSnapshot.empty("");

        @Override
        public synchronized WriteResult writeBatch(DiagnosticsWriteBatch batch) {
            writeAttempts++;
            writeThread = Thread.currentThread().getName();
            CountDownLatch started = writeStarted;
            CountDownLatch release = releaseWrites;
            if (started != null && release != null) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("write release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("write interrupted", interrupted);
                }
            }
            if (alwaysFull || fullWriteFailures > 0) {
                if (fullWriteFailures > 0) fullWriteFailures--;
                operations.add("write_full");
                throw new SQLiteFullException("full");
            }
            if (failWrites) throw new IllegalStateException("write failed");
            if (writeResult != WriteResult.COMMITTED) return writeResult;
            lastBatchIdentity = batch.batchIdentity;
            written += batch.size();
            events.addAll(batch.events);
            operations.add("write");
            return WriteResult.COMMITTED;
        }

        @Override
        public synchronized boolean migrateLegacyOnce(DiagnosticsWriteBatch batch) {
            migrationCalls++;
            migrationWriteThread = Thread.currentThread().getName();
            migratedBatch = batch;
            return migrationCalls == 1;
        }

        @Override
        public synchronized void performMaintenance(long nowMillis, boolean storagePressure) {
            maintenanceThread = Thread.currentThread().getName();
            if (storagePressure) {
                pressureMaintenanceCalls++;
                operations.add("maintenance_pressure");
            } else {
                regularMaintenanceCalls++;
                operations.add("maintenance_regular");
            }
            CountDownLatch latch = maintenanceLatch;
            if (latch != null) latch.countDown();
        }

        @Override
        public DiagnosticsQuery.EventPage queryEvents(
                DiagnosticsQuery.EventFilter filter) {
            if (failQueries) throw new IllegalStateException("query failed");
            CountDownLatch started = queryStarted;
            CountDownLatch release = releaseQueries;
            if (started != null && release != null) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("query release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("query interrupted", interrupted);
                }
            }
            synchronized (this) {
                queryCalls++;
                int limit = filter == null ? 50 : filter.limit;
                List<NormalizedDiagnosticsEvent> copy = new ArrayList<>(
                        events.subList(0, Math.min(limit, events.size())));
                return new DiagnosticsQuery.EventPage(copy, null, "");
            }
        }

        @Override
        public DiagnosticsQuery.AggregatePage queryAggregates(
                DiagnosticsQuery.AggregateQuery query) {
            if (failQueries) throw new IllegalStateException("query failed");
            return DiagnosticsQuery.AggregatePage.empty("");
        }

        @Override
        public List<DiagnosticsQuery.RawHookSummary> queryRawHooks(
                long fromInclusive, long toExclusive, int limit) {
            if (failQueries) throw new IllegalStateException("query failed");
            return new ArrayList<>(rawHooks.subList(0, Math.min(limit, rawHooks.size())));
        }

        @Override
        public DiagnosticsQuery.HomeStatsSnapshot queryHomeStats(
                Iterable<String> featureIds) {
            if (failQueries) throw new IllegalStateException("query failed");
            return homeStats;
        }

        @Override
        public List<DiagnosticsHookState> queryHookStates(int limit) {
            if (failQueries) throw new IllegalStateException("query failed");
            return new ArrayList<>(hookStates.subList(0, Math.min(limit, hookStates.size())));
        }

        @Override
        public synchronized DiagnosticsQuery.ClearResult clear(
                DiagnosticsQuery.ClearScope scope) {
            clearCalls++;
            if (failClears) throw new IllegalStateException("clear failed");
            CountDownLatch started = clearStarted;
            CountDownLatch release = releaseClears;
            if (started != null && release != null) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("clear release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("clear interrupted", interrupted);
                }
            }
            if (clearCommitsThenFails) {
                epoch++;
                if (failEpochReadsAfterClear) failEpochReads = true;
                throw new IllegalStateException("clear result unavailable");
            }
            int deleted = events.size();
            events.clear();
            epoch++;
            return new DiagnosticsQuery.ClearResult(true, deleted, epoch, "");
        }

        @Override
        public long currentEpoch() {
            if (failEpochReads) throw new IllegalStateException("epoch read failed");
            return epoch;
        }

        synchronized List<String> operationSnapshot() {
            return new ArrayList<>(operations);
        }

        void blockQueries() {
            queryStarted = new CountDownLatch(1);
            releaseQueries = new CountDownLatch(1);
        }

        void blockWrites() {
            writeStarted = new CountDownLatch(1);
            releaseWrites = new CountDownLatch(1);
        }

        void blockClears() {
            clearStarted = new CountDownLatch(1);
            releaseClears = new CountDownLatch(1);
        }

        @Override public int schemaVersion() { return ResidencyDiagnosticsDb.SCHEMA_VERSION; }
        @Override public void close() { closed = true; }
    }

    private static final class RejectOnceExecutor extends ScheduledThreadPoolExecutor {
        private final AtomicBoolean rejectNext = new AtomicBoolean();
        private final AtomicBoolean rejectNextSchedule = new AtomicBoolean();

        RejectOnceExecutor() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        void rejectNextExecute() {
            rejectNext.set(true);
        }

        void rejectNextSchedule() {
            rejectNextSchedule.set(true);
        }

        @Override
        public void execute(Runnable command) {
            if (rejectNext.compareAndSet(true, false)) {
                throw new RejectedExecutionException("rejected for test");
            }
            super.execute(command);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            if (rejectNextSchedule.compareAndSet(true, false)) {
                throw new RejectedExecutionException("schedule rejected for test");
            }
            return super.schedule(command, delay, unit);
        }
    }
}
