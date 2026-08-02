package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.Test;

public final class ResidencyRuntimeControllerTest {
    private static final String PACKAGE = "com.example.runtime.target";
    private static final int UID = 10_401;

    @Test
    public void configStaysFailOpenUntilIdentityBuildCompletes() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller =
                new ResidencyRuntimeController(executor, Runnable::run);
        RuntimeConfigSnapshot snapshot = snapshot(1L, 1L, true);

        controller.updateConfig(snapshot);
        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_SOURCE_UNAVAILABLE,
                controller.state().status());
        assertFailOpen(controller);

        controller.setPackageUidSource(source(packageName -> OptionalInt.of(UID)));
        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                controller.state().status());
        assertFailOpen(controller);

        executor.runNext();
        assertTrue(controller.state().ready());
        KillDecision decision = controller.decideKill(killContext());
        assertTrue(decision.blocksAction());
        assertEquals(controller.state().generation(),
                decision.trace().identityGeneration());
    }

    @Test
    public void globalOnlyUpdateReusesMatchingIdentityIndex() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(executor);
        controller.setPackageUidSource(source(packageName -> OptionalInt.of(UID)));
        controller.updateConfig(snapshot(1L, 2L, true));
        executor.runNext();
        ResidencyIdentityIndex firstIndex = controller.state().identityIndex();

        controller.updateConfig(snapshot(2L, 2L, false));

        assertTrue(controller.state().ready());
        assertTrue(firstIndex == controller.state().identityIndex());
        assertFalse(controller.decideKill(killContext()).blocksAction());
        assertTrue(executor.isEmpty());
    }

    @Test
    public void staleBuildCannotReplaceANewerPolicyGeneration() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(executor);
        AtomicInteger queries = new AtomicInteger();
        controller.setPackageUidSource(source(packageName -> {
            queries.incrementAndGet();
            return OptionalInt.of(UID);
        }));
        controller.updateConfig(snapshot(1L, 3L, true));
        controller.updateConfig(snapshot(2L, 4L, true));

        executor.runNext();
        assertEquals(0, queries.get());
        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                controller.state().status());
        assertEquals(4L, controller.state().snapshot().policyRevision());

        executor.runNext();
        assertEquals(1, queries.get());
        assertTrue(controller.state().ready());
        assertEquals(4L, controller.state().identityIndex().policyRevision());
    }

    @Test
    public void staleBuildStopsBeforeSharedUidEnumeration() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(executor);
        AtomicBoolean advanceGeneration = new AtomicBoolean(true);
        AtomicInteger sharedUidQueries = new AtomicInteger();
        controller.setPackageUidSource(new PackageUidSource() {
            @Override
            public boolean hasCompleteUidEnumeration() {
                return true;
            }

            @Override
            public OptionalInt findUid(String packageName) {
                if (advanceGeneration.getAndSet(false)) {
                    controller.updateConfig(snapshot(2L, 4L, true));
                }
                return OptionalInt.of(UID);
            }

            @Override
            public Set<String> findPackagesForUid(int uid) {
                sharedUidQueries.incrementAndGet();
                return Set.of(PACKAGE);
            }
        });
        controller.updateConfig(snapshot(1L, 3L, true));

        executor.runNext();

        assertEquals(0, sharedUidQueries.get());
        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                controller.state().status());
        assertEquals(4L, controller.state().snapshot().policyRevision());

        executor.runNext();
        assertEquals(1, sharedUidQueries.get());
        assertTrue(controller.state().ready());
    }

    @Test
    public void changedPolicyContentCannotReuseAnIndexWithTheSameRevision() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(executor);
        controller.setPackageUidSource(source(packageName -> OptionalInt.of(UID)));
        controller.updateConfig(snapshot(1L, 5L, true, ResidencyMode.BALANCED));
        executor.runNext();
        ResidencyIdentityIndex firstIndex = controller.state().identityIndex();

        controller.updateConfig(snapshot(2L, 5L, true, ResidencyMode.LOW_POWER));

        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                controller.state().status());
        assertTrue(controller.state().identityIndex().isInvalidated());
        executor.runNext();
        assertTrue(controller.state().ready());
        assertFalse(firstIndex == controller.state().identityIndex());
    }

    @Test
    public void identityFailureNeverPublishesAPartialEngine() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(executor);
        controller.setPackageUidSource(source(packageName -> {
            throw new SecurityException("visibility denied");
        }));
        controller.updateConfig(snapshot(1L, 5L, true));

        executor.runNext();

        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_FAILED,
                controller.state().status());
        assertTrue(controller.state().identityIndex().isInvalidated());
        assertTrue(controller.state().error().contains("SecurityException"));
        assertFailOpen(controller);
    }

    @Test
    public void packageInvalidationFailsOpenBeforeRebuildingNewAppId() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(executor);
        AtomicInteger uid = new AtomicInteger(UID);
        controller.setPackageUidSource(
                source(packageName -> OptionalInt.of(uid.get())));
        controller.updateConfig(snapshot(1L, 6L, true));
        executor.runNext();
        assertTrue(controller.state().ready());

        uid.set(UID + 1);
        controller.invalidateIdentities();

        assertEquals(
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                controller.state().status());
        assertFailOpen(controller);
        executor.runNext();
        assertTrue(controller.state().ready());
        assertEquals(
                Integer.valueOf(UID + 1),
                controller.state().identityIndex().packageAppIds().get(PACKAGE));
    }

    @Test
    public void stateListenersObserveEveryPublicationAndCannotBreakTheRuntime() {
        QueuedExecutor executor = new QueuedExecutor();
        ResidencyRuntimeController controller =
                new ResidencyRuntimeController(executor, Runnable::run);
        List<ResidencyRuntimeState.Status> statuses = new ArrayList<>();
        ResidencyRuntimeController.StateListener recorder =
                state -> statuses.add(state.status());
        controller.addStateListener(recorder);
        controller.addStateListener(state -> {
            throw new IllegalStateException("observer failure");
        });

        controller.setPackageUidSource(source(packageName -> OptionalInt.of(UID)));
        controller.updateConfig(snapshot(1L, 7L, true));
        executor.runNext();

        assertEquals(List.of(
                ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                ResidencyRuntimeState.Status.READY), statuses);
        assertTrue(controller.state().ready());

        controller.removeStateListener(recorder);
        controller.updateConfig(snapshot(2L, 7L, false));
        assertEquals(4, statuses.size());
        assertTrue(controller.state().ready());
    }

    @Test
    public void listenerDeliveryDropsAnOlderInitialSnapshotQueuedAfterPublication() {
        QueuedExecutor listenerExecutor = new QueuedExecutor();
        ResidencyRuntimeController controller =
                new ResidencyRuntimeController(Runnable::run, listenerExecutor);
        List<Long> generations = new ArrayList<>();
        controller.addStateListener(state -> generations.add(state.generation()));
        controller.updateConfig(RuntimeConfigSnapshot.available(
                1L, true, true, false, 1L, Map.of()));

        listenerExecutor.runLast();
        listenerExecutor.runNext();

        assertEquals(List.of(1L), generations);
    }

    @Test
    public void blockingListenerNeverHoldsTheControllerMonitor() throws Exception {
        ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch listenerRelease = new CountDownLatch(1);
        try {
            ResidencyRuntimeController controller =
                    new ResidencyRuntimeController(Runnable::run, listenerExecutor);
            controller.addStateListener(state -> {
                listenerEntered.countDown();
                try {
                    listenerRelease.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(listenerEntered.await(1, TimeUnit.SECONDS));

            Future<?> publication = publisher.submit(() ->
                    controller.updateConfig(RuntimeConfigSnapshot.available(
                            1L, true, true, false, 1L, Map.of())));
            publication.get(1, TimeUnit.SECONDS);
            assertTrue(controller.state().ready());
        } finally {
            listenerRelease.countDown();
            publisher.shutdownNow();
            listenerExecutor.shutdownNow();
        }
    }

    @Test
    public void blockingListenerDoesNotPreventConcurrentRemoval() throws Exception {
        ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService remover = Executors.newSingleThreadExecutor();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch listenerRelease = new CountDownLatch(1);
        try {
            ResidencyRuntimeController controller =
                    new ResidencyRuntimeController(Runnable::run, listenerExecutor);
            ResidencyRuntimeController.StateListener listener = state -> {
                listenerEntered.countDown();
                try {
                    listenerRelease.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            };
            controller.addStateListener(listener);
            assertTrue(listenerEntered.await(1, TimeUnit.SECONDS));

            Future<?> removal = remover.submit(() ->
                    controller.removeStateListener(listener));
            removal.get(1, TimeUnit.SECONDS);
        } finally {
            listenerRelease.countDown();
            remover.shutdownNow();
            listenerExecutor.shutdownNow();
        }
    }

    private static PackageUidSource source(
            Function<String, OptionalInt> uidLookup) {
        return new PackageUidSource() {
            @Override
            public boolean hasCompleteUidEnumeration() {
                return true;
            }

            @Override
            public OptionalInt findUid(String packageName) {
                return uidLookup.apply(packageName);
            }

            @Override
            public Set<String> findPackagesForUid(int uid) {
                return Set.of(PACKAGE);
            }
        };
    }

    private static void assertFailOpen(ResidencyRuntimeController controller) {
        KillDecision decision = controller.decideKill(killContext());
        assertEquals(KillDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.STALE_IDENTITY_INDEX, decision.trace().reason());
    }

    private static KillContext killContext() {
        return new KillContext(
                new ResidencyTarget(PACKAGE, UID, -1),
                KillContext.Path.OGUARD_KILL,
                "oguard_kill",
                "oguard_kill");
    }

    private static RuntimeConfigSnapshot snapshot(
            long configRevision, long policyRevision, boolean masterEnabled) {
        return snapshot(
                configRevision, policyRevision, masterEnabled, ResidencyMode.BALANCED);
    }

    private static RuntimeConfigSnapshot snapshot(
            long configRevision,
            long policyRevision,
            boolean masterEnabled,
            ResidencyMode mode) {
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE, mode);
        Map<String, AppResidencyPolicy> policies = new LinkedHashMap<>();
        policies.put(PACKAGE, policy);
        return RuntimeConfigSnapshot.available(
                configRevision,
                masterEnabled,
                true,
                false,
                policyRevision,
                policies);
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }

        void runLast() {
            Runnable task = ((ArrayDeque<Runnable>) tasks).removeLast();
            task.run();
        }

        boolean isEmpty() { return tasks.isEmpty(); }
    }
}
