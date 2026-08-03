package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.DiagnosticsRetentionPolicy;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireEncoder;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWriteBatch;
import com.frostwork.bgenhance.diagnostics.NormalizedDiagnosticsEvent;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsDb;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DiagnosticsProviderControllerTest {
    private static final int MODULE_UID = 12_345;
    private static final int SYSTEM_UID = Process.SYSTEM_UID;
    private static final int ATHENA_UID = 23_456;
    private static final long EPOCH = 7L;

    @Test
    public void onlyModuleSystemAndResolvedAthenaUidsAreAllowed() {
        DiagnosticsProviderController controller = controller(batch -> accepted(batch.size()));

        assertTrue(controller.isAllowedCaller(MODULE_UID));
        assertTrue(controller.isAllowedCaller(SYSTEM_UID));
        assertTrue(controller.isAllowedCaller(ATHENA_UID));
        assertFalse(controller.isAllowedCaller(99_999));

        try {
            controller.handle(99_999, BridgeContract.METHOD_PING, null);
            fail("untrusted caller must be rejected");
        } catch (SecurityException expected) {
            assertEquals("diagnostics caller rejected", expected.getMessage());
        }
    }

    @Test
    public void pingReturnsAuthoritativeVersionBootAndEpoch() {
        Bundle response = controller(batch -> accepted(batch.size()))
                .handle(MODULE_UID, BridgeContract.METHOD_PING, null);

        assertTrue(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals(BridgeContract.BRIDGE_VERSION,
                response.getInt(BridgeContract.EXTRA_BRIDGE_VERSION));
        assertEquals("boot", response.getString(BridgeContract.EXTRA_BOOT_ID));
        assertEquals(EPOCH, response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH));
    }

    @Test
    public void acceptedBatchIsEnqueuedExactlyOnceAndFullyAcknowledged() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return accepted(batch.size());
        });
        DiagnosticsWriteBatch batch = batch(
                BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 4L);

        Bundle response = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch));

        assertEquals(1, calls.get());
        assertTrue(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals(batch.size(), response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(0, response.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    @Test
    public void successfulAckWaitsForPersistenceCompletion() throws Exception {
        CompletableFuture<DiagnosticsProviderController.BatchResult> persistence =
                new CompletableFuture<>();
        CountDownLatch invoked = new CountDownLatch(1);
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", () -> EPOCH,
                batch -> {
                    invoked.countDown();
                    return persistence;
                },
                new DiagnosticsWireParser(),
                1_000L);
        DiagnosticsWriteBatch batch = batch(
                BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 40L);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Bundle> response = caller.submit(() -> controller.handle(
                    SYSTEM_UID,
                    BridgeContract.METHOD_REPORT_BATCH,
                    DiagnosticsWireEncoder.encodeTyped(batch)));

            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            persistence.complete(accepted(batch.size()));
            assertTrue(response.get(5, TimeUnit.SECONDS).getBoolean(
                    BridgeContract.EXTRA_OK));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void persistenceTimeoutIsNeverReportedAsSuccess() {
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", () -> EPOCH,
                batch -> new CompletableFuture<>(),
                new DiagnosticsWireParser(),
                10L);
        DiagnosticsWriteBatch batch = batch(
                BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 41L);

        Bundle response = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch));

        assertFalse(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("backend_timeout", response.getString(BridgeContract.EXTRA_ERROR));
        assertEquals(0, response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(0, response.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    @Test
    public void epochChangeWhileWaitingCannotAcknowledgeOldBatch() throws Exception {
        CompletableFuture<DiagnosticsProviderController.BatchResult> persistence =
                new CompletableFuture<>();
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicLong epoch = new AtomicLong(EPOCH);
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", epoch::get,
                batch -> {
                    invoked.countDown();
                    return persistence;
                },
                new DiagnosticsWireParser(),
                1_000L);
        DiagnosticsWriteBatch batch = batch(
                BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 42L);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Bundle> pending = caller.submit(() -> controller.handle(
                    SYSTEM_UID,
                    BridgeContract.METHOD_REPORT_BATCH,
                    DiagnosticsWireEncoder.encodeTyped(batch)));
            assertTrue(invoked.await(5, TimeUnit.SECONDS));

            epoch.set(EPOCH + 1L);
            persistence.complete(accepted(batch.size()));
            Bundle response = pending.get(5, TimeUnit.SECONDS);

            assertFalse(response.getBoolean(BridgeContract.EXTRA_OK));
            assertEquals("epoch_mismatch", response.getString(BridgeContract.EXTRA_ERROR));
            assertEquals(EPOCH + 1L,
                    response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH));
            assertEquals(0, response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void excessPersistenceWaitersAreRejectedWithoutEnteringSink()
            throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<DiagnosticsProviderController.BatchResult>[] completions =
                new CompletableFuture[] {
                        new CompletableFuture<>(), new CompletableFuture<>()
                };
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(2);
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", () -> EPOCH,
                batch -> {
                    int index = calls.getAndIncrement();
                    if (index >= completions.length) {
                        throw new AssertionError("excess batch entered persistence sink");
                    }
                    waiting.countDown();
                    return completions[index];
                },
                new DiagnosticsWireParser(),
                5_000L);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Bundle> first = callers.submit(() -> controller.handle(
                    SYSTEM_UID,
                    BridgeContract.METHOD_REPORT_BATCH,
                    DiagnosticsWireEncoder.encodeTyped(batch(
                            BridgeContract.PROCESS_SYSTEM_SERVER,
                            "boot", EPOCH, 50L))));
            Future<Bundle> second = callers.submit(() -> controller.handle(
                    SYSTEM_UID,
                    BridgeContract.METHOD_REPORT_BATCH,
                    DiagnosticsWireEncoder.encodeTyped(batch(
                            BridgeContract.PROCESS_SYSTEM_SERVER,
                            "boot", EPOCH, 51L))));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));

            Bundle excess = controller.handle(
                    SYSTEM_UID,
                    BridgeContract.METHOD_REPORT_BATCH,
                    DiagnosticsWireEncoder.encodeTyped(batch(
                            BridgeContract.PROCESS_SYSTEM_SERVER,
                            "boot", EPOCH, 52L)));

            assertFalse(excess.getBoolean(BridgeContract.EXTRA_OK));
            assertEquals("backend_busy", excess.getString(BridgeContract.EXTRA_ERROR));
            assertEquals(2, calls.get());
            completions[0].complete(accepted(1));
            completions[1].complete(accepted(1));
            assertTrue(first.get(5, TimeUnit.SECONDS)
                    .getBoolean(BridgeContract.EXTRA_OK));
            assertTrue(second.get(5, TimeUnit.SECONDS)
                    .getBoolean(BridgeContract.EXTRA_OK));
        } finally {
            for (CompletableFuture<?> completion : completions) {
                completion.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            callers.shutdownNow();
        }
    }

    @Test
    public void timeoutThenLateCommitRetriesAsReplayWithoutDoubleCounting()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(ResidencyDiagnosticsDb.DATABASE_NAME);
        DiagnosticsRetentionPolicy policy = DiagnosticsRetentionPolicy.builder()
                .queueCapacity(16)
                .batchSize(4)
                .flushDelayMillis(0L)
                .maintenanceIntervalMillis(60_000L)
                .maxDatabaseBytes(1_048_576L)
                .build();
        ResidencyDiagnosticsBackend backend = ResidencyDiagnosticsBackend.open(
                context, policy, "boot");
        CompletableFuture<Void> releaseFirstResponse = new CompletableFuture<>();
        CountDownLatch firstCommitted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            assertEquals(0L, backend.currentEpochAsync()
                    .get(5, TimeUnit.SECONDS).longValue());
            DiagnosticsProviderController.BatchSink sink = value -> {
                CompletableFuture<ResidencyDiagnosticsBackend.DurableWriteResult>
                        persisted = backend.enqueueDurable(value);
                if (calls.getAndIncrement() == 0) {
                    return persisted.thenCompose(result -> {
                        firstCommitted.countDown();
                        return releaseFirstResponse.thenApply(ignored -> durable(result));
                    });
                }
                return persisted.thenApply(DiagnosticsProviderControllerTest::durable);
            };
            DiagnosticsProviderController controller = new DiagnosticsProviderController(
                    MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", backend::admissionEpoch,
                    sink, new DiagnosticsWireParser(), 10L);
            DiagnosticsWriteBatch batch = batch(
                    BridgeContract.PROCESS_SYSTEM_SERVER, "boot", 0L, 0L);
            Bundle payload = DiagnosticsWireEncoder.encodeTyped(batch);

            Bundle timedOut = controller.handle(
                    SYSTEM_UID, BridgeContract.METHOD_REPORT_BATCH, payload);
            assertFalse(timedOut.getBoolean(BridgeContract.EXTRA_OK));
            assertEquals("backend_timeout", timedOut.getString(BridgeContract.EXTRA_ERROR));
            assertTrue(firstCommitted.await(5, TimeUnit.SECONDS));
            releaseFirstResponse.complete(null);

            DiagnosticsProviderController retryController =
                    new DiagnosticsProviderController(
                            MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot",
                            backend::admissionEpoch, sink,
                            new DiagnosticsWireParser(), 2_000L);
            Bundle replayed = retryController.handle(
                    SYSTEM_UID, BridgeContract.METHOD_REPORT_BATCH, payload);
            assertTrue(replayed.getBoolean(BridgeContract.EXTRA_OK));
            DiagnosticsQuery.HomeStatsSnapshot stats = backend.queryHomeStatsAsync(
                    Collections.singletonList(FeatureIds.HANS))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1L, stats.observed);
            assertEquals(2, calls.get());
        } finally {
            releaseFirstResponse.complete(null);
            backend.closeAsync().get(5, TimeUnit.SECONDS);
            context.deleteDatabase(ResidencyDiagnosticsDb.DATABASE_NAME);
        }
    }

    @Test
    public void clearAfterCommitButBeforeResponseCannotAckOldEpoch()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(ResidencyDiagnosticsDb.DATABASE_NAME);
        DiagnosticsRetentionPolicy policy = DiagnosticsRetentionPolicy.builder()
                .queueCapacity(16)
                .batchSize(4)
                .flushDelayMillis(0L)
                .maintenanceIntervalMillis(60_000L)
                .maxDatabaseBytes(1_048_576L)
                .build();
        ResidencyDiagnosticsBackend backend = ResidencyDiagnosticsBackend.open(
                context, policy, "boot");
        CompletableFuture<Void> releaseResponse = new CompletableFuture<>();
        CountDownLatch committed = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            assertEquals(0L, backend.currentEpochAsync()
                    .get(5, TimeUnit.SECONDS).longValue());
            DiagnosticsProviderController controller = new DiagnosticsProviderController(
                    MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", backend::admissionEpoch,
                    value -> backend.enqueueDurable(value).thenCompose(result -> {
                        committed.countDown();
                        return releaseResponse.thenApply(ignored -> durable(result));
                    }),
                    new DiagnosticsWireParser(),
                    5_000L);
            Bundle payload = DiagnosticsWireEncoder.encodeTyped(batch(
                    BridgeContract.PROCESS_SYSTEM_SERVER, "boot", 0L, 0L));
            Future<Bundle> pending = caller.submit(() -> controller.handle(
                    SYSTEM_UID, BridgeContract.METHOD_REPORT_BATCH, payload));
            assertTrue(committed.await(5, TimeUnit.SECONDS));

            DiagnosticsQuery.ClearResult clear = backend.clearAsync(
                    DiagnosticsQuery.ClearScope.USER_ACTIVITY)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(clear.success);
            releaseResponse.complete(null);
            Bundle response = pending.get(5, TimeUnit.SECONDS);

            assertFalse(response.getBoolean(BridgeContract.EXTRA_OK));
            assertEquals("epoch_mismatch", response.getString(BridgeContract.EXTRA_ERROR));
            assertEquals(clear.epoch,
                    response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH));
            assertEquals(0, response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        } finally {
            releaseResponse.complete(null);
            caller.shutdownNow();
            backend.closeAsync().get(5, TimeUnit.SECONDS);
            context.deleteDatabase(ResidencyDiagnosticsDb.DATABASE_NAME);
        }
    }

    @Test
    public void athenaBatchIsEnqueuedExactlyOnceAndFullyAcknowledged() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            assertEquals(BridgeContract.PROCESS_ATHENA, batch.batchIdentity.processId);
            return accepted(batch.size());
        });
        DiagnosticsWriteBatch batch = batch(
                BridgeContract.PROCESS_ATHENA, "boot", EPOCH, 5L);

        Bundle response = controller.handle(
                ATHENA_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch));

        assertEquals(1, calls.get());
        assertTrue(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals(batch.size(), response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(0, response.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    @Test
    public void systemAndAthenaCannotImpersonateEachOther() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return accepted(batch.size());
        });
        DiagnosticsWriteBatch athenaBatch = batch(
                BridgeContract.PROCESS_ATHENA, "boot", EPOCH, 6L, 3);
        DiagnosticsWriteBatch systemBatch = batch(
                BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 7L, 2);

        Bundle forgedBySystem = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(athenaBatch));
        Bundle forgedByAthena = controller.handle(
                ATHENA_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(systemBatch));

        assertEquals(0, calls.get());
        assertRejectedIdentity(forgedBySystem, athenaBatch.size());
        assertRejectedIdentity(forgedByAthena, systemBatch.size());
    }

    @Test
    public void moduleUidCannotSubmitHookBatches() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return accepted(batch.size());
        });

        Bundle system = controller.handle(
                MODULE_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 8L)));
        Bundle athena = controller.handle(
                MODULE_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_ATHENA, "boot", EPOCH, 9L)));

        assertEquals(0, calls.get());
        assertRejectedIdentity(system, 1);
        assertRejectedIdentity(athena, 1);
    }

    @Test
    public void sharedSystemUidAcceptsBothHookProcessIdentities() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, SYSTEM_UID, "boot", () -> EPOCH,
                batch -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(accepted(batch.size()));
                },
                new DiagnosticsWireParser());

        Bundle system = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 10L)));
        Bundle athena = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_ATHENA, "boot", EPOCH, 11L)));

        assertEquals(2, calls.get());
        assertTrue(system.getBoolean(BridgeContract.EXTRA_OK));
        assertTrue(athena.getBoolean(BridgeContract.EXTRA_OK));
    }

    @Test
    public void sharedSystemUidStillRejectsUnknownProcessIdentity() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, SYSTEM_UID, "boot", () -> EPOCH,
                batch -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(accepted(batch.size()));
                },
                new DiagnosticsWireParser());

        Bundle response = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        "unknown_process", "boot", EPOCH, 12L)));

        assertEquals(0, calls.get());
        assertRejectedIdentity(response, 1);
    }

    @Test
    public void bootAndEpochMismatchNeverReachBackend() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return accepted(batch.size());
        });

        Bundle boot = controller.handle(SYSTEM_UID, BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_SYSTEM_SERVER, "other-boot", EPOCH, 0L)));
        Bundle epoch = controller.handle(SYSTEM_UID, BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH + 1L, 0L)));

        assertEquals(0, calls.get());
        assertFalse(boot.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("boot_mismatch", boot.getString(BridgeContract.EXTRA_ERROR));
        assertFalse(epoch.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("epoch_mismatch", epoch.getString(BridgeContract.EXTRA_ERROR));
        assertEquals(EPOCH, epoch.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH));
    }

    @Test
    public void parserRejectionAndBackendBackpressureAreExplicit() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return new DiagnosticsProviderController.BatchResult(0, batch.size());
        });

        Bundle invalid = controller.handle(
                SYSTEM_UID, BridgeContract.METHOD_REPORT_BATCH, new Bundle());
        Bundle busy = controller.handle(
                SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch(
                        BridgeContract.PROCESS_SYSTEM_SERVER, "boot", EPOCH, 0L)));

        assertEquals(1, calls.get());
        assertFalse(invalid.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("invalid_batch_identity",
                invalid.getString(BridgeContract.EXTRA_ERROR));
        assertFalse(busy.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("backend_busy", busy.getString(BridgeContract.EXTRA_ERROR));
        assertEquals(0, busy.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(1, busy.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    @Test
    public void unresolvedAthenaUidDoesNotAuthorizeArbitraryNegativeUid() {
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, -1, "boot", () -> EPOCH,
                batch -> CompletableFuture.completedFuture(accepted(batch.size())),
                new DiagnosticsWireParser());

        assertFalse(controller.isAllowedCaller(-1));
    }

    private static DiagnosticsProviderController controller(
            Function<DiagnosticsWriteBatch, DiagnosticsProviderController.BatchResult> sink) {
        return new DiagnosticsProviderController(
                MODULE_UID, SYSTEM_UID, ATHENA_UID, "boot", () -> EPOCH,
                batch -> CompletableFuture.completedFuture(sink.apply(batch)),
                new DiagnosticsWireParser());
    }

    private static DiagnosticsProviderController.BatchResult accepted(int count) {
        return new DiagnosticsProviderController.BatchResult(count, 0);
    }

    private static DiagnosticsProviderController.BatchResult durable(
            ResidencyDiagnosticsBackend.DurableWriteResult result) {
        return result.persisted
                ? accepted(result.itemCount)
                : new DiagnosticsProviderController.BatchResult(0, 0, result.errorCode);
    }

    private static void assertRejectedIdentity(Bundle response, int droppedItems) {
        assertFalse(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("sender_identity_mismatch",
                response.getString(BridgeContract.EXTRA_ERROR));
        assertEquals(0, response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(droppedItems, response.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    private static DiagnosticsWriteBatch batch(
            String processId, String bootId, long epoch, long sequence) {
        return batch(processId, bootId, epoch, sequence, 1);
    }

    private static DiagnosticsWriteBatch batch(
            String processId, String bootId, long epoch, long sequence, int eventCount) {
        DiagnosticsWriteBatch.Builder builder = DiagnosticsWriteBatch.builder()
                .wireIdentity(
                        DiagnosticsWireParser.WIRE_VERSION,
                        bootId,
                        processId,
                        "sender-instance",
                        epoch,
                        sequence)
                .countingMode(DiagnosticsWriteBatch.CountingMode.TYPED);
        for (int index = 0; index < eventCount; index++) {
            builder.addEvent(NormalizedDiagnosticsEvent.builder()
                    .timestampMillis(System.currentTimeMillis())
                    .bootId(bootId)
                    .processId(processId)
                    .featureId(FeatureIds.HANS)
                    .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                    .behavior("FREEZE")
                    .source("FAST")
                    .packageName("com.example.target")
                    .uid(10_123)
                    .decision("POLICY_BLOCKS_FREEZE")
                    .result(DiagnosticsResult.BLOCKED)
                    .eventToken("event-" + sequence + "-" + index)
                    .build());
        }
        return builder.build();
    }
}
