package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryAdapter.Outcome;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator.Request;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator.TerminalOutcome;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.PackageUidSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class HansActiveRecoveryCoordinatorTest {
    private static final String PACKAGE = "com.example.recovery.coordinator";
    private static final String SECOND_PACKAGE = "com.example.recovery.second";
    private static final String REUSED_PACKAGE = "com.example.recovery.reused";
    private static final int UID = 10_931;
    private static final int SECOND_UID = 10_932;

    @Test
    public void successfulRecoveryRunsAfterExactUidOwnershipCheck() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    packageQueries.incrementAndGet();
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);

        requestReconcile(coordinator, scheduler);
        completeAttempt(scheduler, calls);

        assertEquals(2, packageQueries.get());
        assertEquals(1, recoveries.get());
        assertEquals(List.of(TerminalOutcome.RECOVERED), diagnostics.outcomes);
        assertEquals(0, coordinator.pendingRequestCount());
        assertTrue(scheduler.delays().isEmpty());
    }

    @Test
    public void uidReuseMismatchNeverCallsVendorRecoveryAndDoesNotRetry() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(REUSED_PACKAGE),
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);

        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();

        assertEquals(0, recoveries.get());
        assertEquals(List.of(TerminalOutcome.UID_PACKAGE_MISMATCH), diagnostics.outcomes);
        assertEquals(0, calls.pendingCount());
        assertEquals(0, coordinator.pendingRequestCount());
    }

    @Test
    public void packageQueryFailureUsesBoundedBackoffThenRecovers() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        AtomicInteger queries = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    if (queries.getAndIncrement() == 0) {
                        throw new IllegalStateException("temporarily unavailable");
                    }
                    return Set.of(PACKAGE);
                },
                uid -> Outcome.RECOVERED,
                diagnostics);

        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();

        assertEquals(1, coordinator.pendingRequestOrNull(UID).retryIndex());
        assertEquals(List.of(1_000L), scheduler.delays());
        assertTrue(diagnostics.outcomes.isEmpty());

        scheduler.runNextDelayed(1_000L);
        completeAttempt(scheduler, calls);

        assertEquals(3, queries.get());
        assertEquals(List.of(TerminalOutcome.RECOVERED), diagnostics.outcomes);
        assertEquals(0, coordinator.pendingRequestCount());
    }

    @Test
    public void retryableVendorFailuresExhaustExactlyTheDocumentedSchedule() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger attempts = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> {
                    attempts.incrementAndGet();
                    return Outcome.NOT_READY;
                },
                diagnostics);

        requestReconcile(coordinator, scheduler);
        exhaustCurrentRequest(coordinator, scheduler, calls);

        assertEquals(7, attempts.get());
        assertEquals(List.of(TerminalOutcome.RETRY_EXHAUSTED), diagnostics.outcomes);
        Request exhausted = coordinator.pendingRequestOrNull(UID);
        assertNotNull(exhausted);
        assertTrue(exhausted.exhausted());
        assertEquals(Outcome.NOT_READY, exhausted.lastAdapterOutcome());
        assertEquals(0, coordinator.pendingRequestCount());
        assertEquals(1, coordinator.exhaustedRequestCount());
    }

    @Test
    public void nextReadyPublicationRestartsAnExhaustedRequest() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NOT_READY);
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> outcome.get(),
                diagnostics);
        requestReconcile(coordinator, scheduler);
        exhaustCurrentRequest(coordinator, scheduler, calls);
        Request exhausted = coordinator.pendingRequestOrNull(UID);
        assertTrue(exhausted.exhausted());
        long oldSequence = exhausted.sequence();

        outcome.set(Outcome.RECOVERED);
        runtime.publish(true, activePolicy(), 2L);
        requestReconcile(coordinator, scheduler);
        completeAttempt(scheduler, calls);

        assertEquals(List.of(
                TerminalOutcome.RETRY_EXHAUSTED,
                TerminalOutcome.RECOVERED), diagnostics.outcomes);
        assertEquals(0, coordinator.pendingRequestCount());
        assertEquals(0, coordinator.exhaustedRequestCount());
        assertTrue(diagnostics.sequences.get(1) > oldSequence);
    }

    @Test
    public void schedulerRejectionReemitsRecoveryForTheSameState() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);
        scheduler.rejectNextSchedule();

        requestReconcile(coordinator, scheduler);

        assertEquals(List.of(TerminalOutcome.SCHEDULER_REJECTED), diagnostics.outcomes);
        assertEquals(0, calls.pendingCount());
        assertEquals(0, coordinator.pendingRequestCount());

        requestReconcile(coordinator, scheduler);
        assertEquals(1, calls.pendingCount());
        assertEquals(1, coordinator.pendingRequestCount());

        completeAttempt(scheduler, calls);

        assertEquals(1, recoveries.get());
        assertEquals(List.of(
                TerminalOutcome.SCHEDULER_REJECTED,
                TerminalOutcome.RECOVERED), diagnostics.outcomes);
    }

    @Test
    public void generationRebindPreservesBackoffWithoutAnImmediateAttempt() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    packageQueries.incrementAndGet();
                    return Set.of(PACKAGE);
                },
                uid -> attempts.getAndIncrement() == 0
                        ? Outcome.NOT_READY : Outcome.RECOVERED,
                diagnostics);
        requestReconcile(coordinator, scheduler);
        completeAttempt(scheduler, calls);
        Request waiting = coordinator.pendingRequestOrNull(UID);
        long sequence = waiting.sequence();
        assertEquals(1, waiting.retryIndex());
        assertFalse(waiting.ready());

        runtime.publish(true, activePolicy(), 2L);
        requestReconcile(coordinator, scheduler);

        Request rebound = coordinator.pendingRequestOrNull(UID);
        assertEquals(sequence, rebound.sequence());
        assertEquals(1, rebound.retryIndex());
        assertFalse(rebound.ready());
        assertEquals(runtime.state().generation(), rebound.runtimeGeneration());
        assertEquals(0, calls.pendingCount());
        assertEquals(2, packageQueries.get());
        assertEquals(1, attempts.get());
        assertEquals(List.of(1_000L), scheduler.delays());

        scheduler.runNextDelayed(1_000L);
        completeAttempt(scheduler, calls);

        assertEquals(4, packageQueries.get());
        assertEquals(2, attempts.get());
        assertEquals(List.of(TerminalOutcome.RECOVERED), diagnostics.outcomes);
    }

    @Test
    public void exhaustedRequestDoesNotStarveANewUidAtCapacityOne() {
        MutableRuntime runtime = new MutableRuntime(Map.of(
                PACKAGE, UID,
                SECOND_PACKAGE, SECOND_UID));
        runtime.publish(true, List.of(activePolicy()), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        List<Integer> queriedUids = new ArrayList<>();
        List<Integer> recoveredUids = new ArrayList<>();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    queriedUids.add(uid);
                    return runtime.packagesForUid(uid);
                },
                uid -> {
                    if (uid == UID) return Outcome.NOT_READY;
                    recoveredUids.add(uid);
                    return Outcome.RECOVERED;
                },
                diagnostics,
                1);
        requestReconcile(coordinator, scheduler);
        exhaustCurrentRequest(coordinator, scheduler, calls);
        assertEquals(1, coordinator.exhaustedRequestCount());

        runtime.publish(true, List.of(
                activePolicy(),
                activePolicy(SECOND_PACKAGE)), 2L);
        requestReconcile(coordinator, scheduler);

        assertEquals(1, calls.pendingCount());
        calls.runNext();
        scheduler.runAllReady();
        assertEquals(SECOND_UID, (int) queriedUids.get(queriedUids.size() - 1));
        calls.runNext();
        scheduler.runNextReady();

        assertEquals(List.of(SECOND_UID), recoveredUids);
        assertEquals(List.of(
                TerminalOutcome.RETRY_EXHAUSTED,
                TerminalOutcome.RECOVERED), diagnostics.outcomes);
        assertEquals(List.of(UID, SECOND_UID), diagnostics.uids);
        assertEquals(1, coordinator.exhaustedRequestCount());
    }

    @Test
    public void packageQueryTimeoutIsReportedOnceAndNeverRunsOemRecovery() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    queryEntered.countDown();
                    await(releaseQuery);
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);
        requestReconcile(coordinator, scheduler);
        RunningTask blockedQuery = runTask(
                calls.takeNext(), "package-timeout");
        await(queryEntered);
        ScheduledTask timeout = scheduler.takeNextDelayed();
        assertEquals(HansActiveRecoveryCoordinator.CALL_TIMEOUT_MS, timeout.delayMs);

        timeout.run();
        timeout.run();
        scheduler.runAllReady();

        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);
        assertEquals(0, recoveries.get());

        releaseQuery.countDown();
        blockedQuery.joinAndAssertSuccess();
        scheduler.runAllReady();
        timeout.run();

        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);
        assertEquals(0, recoveries.get());
        assertEquals(0, calls.pendingCount());
    }

    @Test
    public void oemTimeoutBeforeQueuedCallPreventsTheVendorCall() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    packageQueries.incrementAndGet();
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);

        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();
        assertEquals(1, calls.pendingCount());

        scheduler.runNextDelayed(HansActiveRecoveryCoordinator.CALL_TIMEOUT_MS);
        scheduler.runAllReady();
        calls.runNext();
        scheduler.runAllReady();

        assertEquals(1, packageQueries.get());
        assertEquals(0, recoveries.get());
        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);
    }

    @Test
    public void oemTimeoutIsReportedOnceAndDoesNotStartAConcurrentReplacement() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        CountDownLatch recoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    packageQueries.incrementAndGet();
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    recoveryEntered.countDown();
                    await(releaseRecovery);
                    return Outcome.RECOVERED;
                },
                diagnostics);
        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();
        assertEquals(1, calls.pendingCount());
        RunningTask blockedRecovery = runTask(
                calls.takeNext(), "oem-timeout");
        await(recoveryEntered);
        ScheduledTask timeout = scheduler.takeNextDelayed();
        assertEquals(HansActiveRecoveryCoordinator.CALL_TIMEOUT_MS, timeout.delayMs);

        timeout.run();
        timeout.run();
        scheduler.runAllReady();
        runtime.publish(true, activePolicy(), 2L);
        requestReconcile(coordinator, scheduler);

        Request replacement = coordinator.pendingRequestOrNull(UID);
        assertNotNull(replacement);
        assertFalse(replacement.exhausted());
        assertEquals(0, calls.pendingCount());
        assertEquals(2, packageQueries.get());
        assertEquals(1, recoveries.get());
        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);

        timeout.run();
        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);

        releaseRecovery.countDown();
        blockedRecovery.joinAndAssertSuccess();
        scheduler.runAllReady();

        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);
        assertEquals(1, calls.pendingCount());
        coordinator.close();
    }

    @Test
    public void lateOemCompletionIsDiscardedBeforeTheReplacementRuns() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        CountDownLatch firstRecoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRecovery = new CountDownLatch(1);
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    packageQueries.incrementAndGet();
                    return Set.of(PACKAGE);
                },
                uid -> {
                    if (recoveries.incrementAndGet() == 1) {
                        firstRecoveryEntered.countDown();
                        await(releaseFirstRecovery);
                    }
                    return Outcome.RECOVERED;
                },
                diagnostics);
        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();
        RunningTask blockedRecovery = runTask(
                calls.takeNext(), "late-oem-completion");
        await(firstRecoveryEntered);
        ScheduledTask timeout = scheduler.takeNextDelayed();
        assertEquals(HansActiveRecoveryCoordinator.CALL_TIMEOUT_MS, timeout.delayMs);
        timeout.run();
        scheduler.runAllReady();
        runtime.publish(true, activePolicy(), 2L);
        requestReconcile(coordinator, scheduler);

        assertEquals(0, calls.pendingCount());
        releaseFirstRecovery.countDown();
        blockedRecovery.joinAndAssertSuccess();
        scheduler.runAllReady();

        assertEquals(1, recoveries.get());
        assertEquals(2, packageQueries.get());
        assertEquals(List.of(TerminalOutcome.CALL_TIMEOUT), diagnostics.outcomes);
        assertEquals(1, calls.pendingCount());

        completeAttempt(scheduler, calls);

        assertEquals(2, recoveries.get());
        assertEquals(4, packageQueries.get());
        assertEquals(List.of(
                TerminalOutcome.CALL_TIMEOUT,
                TerminalOutcome.RECOVERED), diagnostics.outcomes);
        assertTrue(diagnostics.sequences.get(1) > diagnostics.sequences.get(0));
    }

    @Test
    public void closeDuringBlockedPackageQueryPreventsOemRecovery() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    queryEntered.countDown();
                    await(releaseQuery);
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);
        requestReconcile(coordinator, scheduler);
        RunningTask blockedQuery = runTask(
                calls.takeNext(), "close-package-query");
        await(queryEntered);

        coordinator.close();
        releaseQuery.countDown();
        blockedQuery.joinAndAssertSuccess();

        assertEquals(0, recoveries.get());
        assertTrue(scheduler.shutdown);
        assertTrue(calls.shutdown);
        assertTrue(diagnostics.outcomes.isEmpty());
        assertTrue(diagnostics.workerFailures.isEmpty());
    }

    @Test
    public void policyChangeWhileOemTaskIsQueuedIsRevalidatedBeforeVendorCall() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    packageQueries.incrementAndGet();
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);

        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();
        assertEquals(1, calls.pendingCount());

        runtime.publish(false, activePolicy(), 2L);
        calls.runNext();
        scheduler.runAllReady();

        assertEquals(0, recoveries.get());
        assertEquals(1, packageQueries.get());
        assertEquals(1, calls.pendingCount());

        completeAttempt(scheduler, calls);
        assertEquals(1, recoveries.get());
        assertEquals(3, packageQueries.get());
        assertEquals(List.of(TerminalOutcome.RECOVERED), diagnostics.outcomes);
    }

    @Test
    public void runtimeChangeDuringFinalPermitCheckSkipsTheVendorCall() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger packageQueries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        AtomicBoolean replaceStateOnNextRead = new AtomicBoolean();
        HansActiveRecoveryCoordinator coordinator = new HansActiveRecoveryCoordinator(
                new HansActiveRecoveryPlanner(),
                scheduler,
                calls,
                () -> {
                    if (replaceStateOnNextRead.compareAndSet(true, false)) {
                        runtime.publish(true, activePolicy(), 2L);
                    }
                    return runtime.state();
                },
                uid -> {
                    if (packageQueries.incrementAndGet() == 2) {
                        replaceStateOnNextRead.set(true);
                    }
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics,
                HansActiveRecoveryCoordinator.MAX_PENDING_UIDS);

        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();
        calls.runNext();
        scheduler.runAllReady();

        assertEquals(2, packageQueries.get());
        assertEquals(0, recoveries.get());
        assertEquals(1, coordinator.pendingRequestOrNull(UID).retryIndex());
        assertEquals(List.of(1_000L), scheduler.delays());
        assertTrue(diagnostics.outcomes.isEmpty());
    }

    @Test
    public void callExecutorRejectionWaitsForAnExternalReconcileWithoutSpinning() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> {
                    recoveries.incrementAndGet();
                    return Outcome.RECOVERED;
                },
                diagnostics);
        calls.rejectNext();

        requestReconcile(coordinator, scheduler);

        assertEquals(List.of(TerminalOutcome.CALL_EXECUTOR_REJECTED),
                diagnostics.outcomes);
        assertEquals(0, calls.pendingCount());
        assertEquals(0, coordinator.pendingRequestCount());

        requestReconcile(coordinator, scheduler);
        completeAttempt(scheduler, calls);

        assertEquals(1, recoveries.get());
        assertEquals(List.of(
                TerminalOutcome.CALL_EXECUTOR_REJECTED,
                TerminalOutcome.RECOVERED), diagnostics.outcomes);
    }

    @Test
    public void workerTopLevelFailureIsContainedAndClosesTheCoordinator() {
        MutableRuntime runtime = new MutableRuntime();
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> Outcome.RECOVERED,
                diagnostics);
        AssertionError workerFailure = new AssertionError("worker failed");

        assertTrue(invokeWorkerExecute(coordinator, () -> {
            throw workerFailure;
        }));
        scheduler.runNextReady();

        assertEquals(1, diagnostics.workerFailures.size());
        assertSame(workerFailure, diagnostics.workerFailures.get(0));
        assertTrue(scheduler.shutdown);
        assertTrue(calls.shutdown);
        assertFalse(coordinator.requestReconcile());
    }

    @Test
    public void authoritativePolicyReplacementInvalidatesTheOldQueuedCallback() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        List<Long> recoveredSignatures = new ArrayList<>();
        AtomicReference<HansActiveRecoveryCoordinator> coordinatorRef = new AtomicReference<>();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> {
                    Request request = coordinatorRef.get().pendingRequestOrNull(uid);
                    recoveredSignatures.add(request.desiredSignature());
                    return Outcome.RECOVERED;
                },
                diagnostics);
        coordinatorRef.set(coordinator);
        requestReconcile(coordinator, scheduler);
        Request oldRequest = coordinator.pendingRequestOrNull(UID);

        runtime.publish(false, activePolicy(), 2L);
        requestReconcile(coordinator, scheduler);
        Request replacement = coordinator.pendingRequestOrNull(UID);
        assertTrue(replacement.sequence() > oldRequest.sequence());
        assertEquals(1, calls.pendingCount());

        calls.runNext();
        scheduler.runAllReady();
        assertEquals(1, calls.pendingCount());
        completeAttempt(scheduler, calls);

        assertEquals(List.of(0L), recoveredSignatures);
        assertEquals(List.of(TerminalOutcome.RECOVERED), diagnostics.outcomes);
    }

    @Test
    public void policyChangeDuringPackageManagerQueryIsRecheckedBeforeVendorCall() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        AtomicInteger queries = new AtomicInteger();
        List<Long> recoveredSignatures = new ArrayList<>();
        AtomicReference<HansActiveRecoveryCoordinator> coordinatorRef = new AtomicReference<>();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> {
                    if (queries.getAndIncrement() == 0) {
                        runtime.publish(false, activePolicy(), 2L);
                    }
                    return Set.of(PACKAGE);
                },
                uid -> {
                    recoveredSignatures.add(coordinatorRef.get()
                            .pendingRequestOrNull(uid).desiredSignature());
                    return Outcome.RECOVERED;
                },
                new RecordingDiagnostics());
        coordinatorRef.set(coordinator);

        requestReconcile(coordinator, scheduler);
        calls.runNext();
        scheduler.runAllReady();
        assertEquals(1, calls.pendingCount());
        completeAttempt(scheduler, calls);

        assertEquals(List.of(0L), recoveredSignatures);
        assertEquals(3, queries.get());
    }

    @Test
    public void coordinatorUsesDocumentedBounds() {
        assertEquals(512, HansActiveRecoveryCoordinator.MAX_PENDING_UIDS);
        assertEquals(1_024, HansActiveRecoveryCoordinator.MAX_EXHAUSTED_UIDS);
        assertEquals(15_000L, HansActiveRecoveryCoordinator.CALL_TIMEOUT_MS);
        assertEquals(6, HansActiveRecoveryCoordinator.RETRY_DELAYS_MS.length);
    }

    @Test
    public void closeStopsNewWorkAndShutsDownBothExecutors() {
        MutableRuntime runtime = new MutableRuntime();
        runtime.publish(true, activePolicy(), 1L);
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor calls = new ManualCallExecutor();
        HansActiveRecoveryCoordinator coordinator = coordinator(
                runtime,
                scheduler,
                calls,
                uid -> Set.of(PACKAGE),
                uid -> Outcome.RECOVERED,
                new RecordingDiagnostics());

        coordinator.close();

        assertFalse(coordinator.requestReconcile());
        assertTrue(scheduler.shutdown);
        assertTrue(calls.shutdown);
    }

    private static HansActiveRecoveryCoordinator coordinator(
            MutableRuntime runtime,
            ManualScheduler scheduler,
            ManualCallExecutor calls,
            HansActiveRecoveryCoordinator.UidPackageSource packages,
            HansActiveRecoveryCoordinator.RecoveryAction recovery,
            RecordingDiagnostics diagnostics) {
        return coordinator(runtime, scheduler, calls, packages, recovery, diagnostics,
                HansActiveRecoveryCoordinator.MAX_PENDING_UIDS);
    }

    private static HansActiveRecoveryCoordinator coordinator(
            MutableRuntime runtime,
            ManualScheduler scheduler,
            ManualCallExecutor calls,
            HansActiveRecoveryCoordinator.UidPackageSource packages,
            HansActiveRecoveryCoordinator.RecoveryAction recovery,
            RecordingDiagnostics diagnostics,
            int maxPendingUids) {
        return new HansActiveRecoveryCoordinator(
                new HansActiveRecoveryPlanner(),
                scheduler,
                calls,
                runtime::state,
                packages,
                recovery,
                diagnostics,
                maxPendingUids);
    }

    private static void requestReconcile(
            HansActiveRecoveryCoordinator coordinator, ManualScheduler scheduler) {
        assertTrue(coordinator.requestReconcile());
        scheduler.runAllReady();
    }

    private static void completeAttempt(
            ManualScheduler scheduler, ManualCallExecutor calls) {
        calls.runNext();
        scheduler.runAllReady();
        calls.runNext();
        scheduler.runAllReady();
    }

    private static void exhaustCurrentRequest(
            HansActiveRecoveryCoordinator coordinator,
            ManualScheduler scheduler,
            ManualCallExecutor calls) {
        for (int attempt = 0; attempt <= HansActiveRecoveryCoordinator.RETRY_DELAYS_MS.length;
                attempt++) {
            completeAttempt(scheduler, calls);
            if (attempt < HansActiveRecoveryCoordinator.RETRY_DELAYS_MS.length) {
                assertEquals(attempt + 1, coordinator.pendingRequestOrNull(UID).retryIndex());
                scheduler.runNextDelayed(
                        HansActiveRecoveryCoordinator.RETRY_DELAYS_MS[attempt]);
            }
        }
    }

    private static boolean invokeWorkerExecute(
            HansActiveRecoveryCoordinator coordinator, Runnable task) {
        try {
            Method execute = HansActiveRecoveryCoordinator.class.getDeclaredMethod(
                    "execute", Runnable.class);
            execute.setAccessible(true);
            return "ACCEPTED".equals(String.valueOf(execute.invoke(coordinator, task)));
        } catch (InvocationTargetException error) {
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static RunningTask runTask(Runnable task, String nameSuffix) {
        return new RunningTask(task, nameSuffix);
    }

    private static AppResidencyPolicy activePolicy() {
        return activePolicy(PACKAGE);
    }

    private static AppResidencyPolicy activePolicy(String packageName) {
        return ResidencyPresets.create(packageName, ResidencyMode.ACTIVE);
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void join(Thread thread) {
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class RunningTask {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private RunningTask(Runnable task, String nameSuffix) {
            thread = new Thread(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "HansActiveRecoveryCoordinatorTest-" + nameSuffix);
            thread.start();
        }

        private void joinAndAssertSuccess() {
            join(thread);
            assertNull(failure.get());
        }
    }

    private static final class MutableRuntime {
        private final Map<String, Integer> packageUids = new LinkedHashMap<>();
        private final ResidencyRuntimeController controller =
                new ResidencyRuntimeController(Runnable::run);

        private MutableRuntime() {
            this(Map.of(PACKAGE, UID));
        }

        private MutableRuntime(Map<String, Integer> identities) {
            packageUids.putAll(identities);
            controller.setPackageUidSource(new PackageUidSource() {
                @Override public boolean hasCompleteUidEnumeration() { return true; }

                @Override
                public OptionalInt findUid(String packageName) {
                    Integer uid = packageUids.get(packageName);
                    return uid == null ? OptionalInt.empty() : OptionalInt.of(uid);
                }

                @Override
                public Set<String> findPackagesForUid(int uid) {
                    return packagesForUid(uid);
                }
            });
        }

        private void publish(boolean master, AppResidencyPolicy policy, long revision) {
            publish(master, List.of(policy), revision);
        }

        private void publish(
                boolean master, List<AppResidencyPolicy> policies, long revision) {
            Map<String, AppResidencyPolicy> policiesByPackage = new LinkedHashMap<>();
            for (AppResidencyPolicy policy : policies) {
                policiesByPackage.put(policy.instanceKey().encode(), policy);
            }
            controller.updateConfig(RuntimeConfigSnapshot.available(
                    revision,
                    master,
                    true,
                    false,
                    revision,
                    policiesByPackage));
            assertTrue(controller.state().ready());
        }

        private Set<String> packagesForUid(int uid) {
            Set<String> packages = new LinkedHashSet<>();
            for (Map.Entry<String, Integer> entry : packageUids.entrySet()) {
                if (entry.getValue() == uid) packages.add(entry.getKey());
            }
            return packages.isEmpty()
                    ? Collections.emptySet() : Collections.unmodifiableSet(packages);
        }

        private ResidencyRuntimeState state() { return controller.state(); }
    }

    private static final class RecordingDiagnostics
            implements HansActiveRecoveryCoordinator.DiagnosticsRecorder {
        private final List<TerminalOutcome> outcomes = new ArrayList<>();
        private final List<Long> sequences = new ArrayList<>();
        private final List<Integer> uids = new ArrayList<>();
        private final List<Throwable> workerFailures = new ArrayList<>();

        @Override
        public void record(Request request, TerminalOutcome outcome, Outcome adapterOutcome) {
            outcomes.add(outcome);
            sequences.add(request.sequence());
            uids.add(request.uid());
        }

        @Override
        public void workerFailed(Throwable error) {
            workerFailures.add(error);
        }
    }

    private static final class ManualScheduler
            implements HansActiveRecoveryCoordinator.RecoveryScheduler {
        private final Deque<Runnable> ready = new ArrayDeque<>();
        private final Deque<ScheduledTask> delayed = new ArrayDeque<>();
        private boolean shutdown;
        private int rejectedSchedules;

        @Override
        public boolean execute(Runnable task) {
            if (shutdown) return false;
            ready.add(task);
            return true;
        }

        @Override
        public boolean schedule(Runnable task, long delayMs) {
            if (shutdown) return false;
            if (rejectedSchedules > 0) {
                rejectedSchedules--;
                return false;
            }
            delayed.add(new ScheduledTask(task, delayMs));
            return true;
        }

        @Override
        public void cancel(Runnable task) {
            delayed.removeIf(scheduled -> scheduled.runnable == task);
        }

        @Override
        public void shutdown() {
            shutdown = true;
            ready.clear();
            delayed.clear();
        }

        private void rejectNextSchedule() {
            rejectedSchedules++;
        }

        private void runNextReady() {
            ready.remove().run();
        }

        private void runAllReady() {
            int guard = 10_000;
            while (!ready.isEmpty() && guard-- > 0) ready.remove().run();
            if (guard <= 0) throw new AssertionError("ready queue did not drain");
        }

        private ScheduledTask takeNextDelayed() {
            return delayed.remove();
        }

        private void runNextDelayed(long expectedDelayMs) {
            ScheduledTask task = takeNextDelayed();
            assertEquals(expectedDelayMs, task.delayMs);
            task.run();
        }

        private List<Long> delays() {
            List<Long> result = new ArrayList<>();
            for (ScheduledTask task : delayed) result.add(task.delayMs);
            return result;
        }
    }

    private static final class ManualCallExecutor
            implements HansActiveRecoveryCoordinator.RecoveryCallExecutor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        private int rejectedExecutions;

        @Override
        public boolean execute(Runnable task) {
            if (shutdown) return false;
            if (rejectedExecutions > 0) {
                rejectedExecutions--;
                return false;
            }
            tasks.add(task);
            return true;
        }

        @Override
        public void shutdown() {
            shutdown = true;
            tasks.clear();
        }

        private Runnable takeNext() {
            return tasks.remove();
        }

        private void runNext() {
            takeNext().run();
        }

        private int pendingCount() {
            return tasks.size();
        }

        private void rejectNext() {
            rejectedExecutions++;
        }
    }

    private static final class ScheduledTask {
        private final Runnable runnable;
        private final long delayMs;

        private ScheduledTask(Runnable runnable, long delayMs) {
            this.runnable = runnable;
            this.delayMs = delayMs;
        }

        private void run() {
            runnable.run();
        }
    }
}
