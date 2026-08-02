package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusHansManager;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryAdapter;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.PackageUidSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class HansActiveRecoveryHookTest {
    private static final String PACKAGE = "com.example.recovery.hook";
    private static final int UID = 10_941;

    @Test
    public void installCreatesWorkerRegistersListenerAndSubmitsAuthoritativeState() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor callExecutor = new ManualCallExecutor();
        RecordingRegistry registry = new RecordingRegistry();
        AtomicInteger recoveries = new AtomicInteger();
        HansActiveRecoveryHook hook = hook(
                scheduler,
                callExecutor,
                registry,
                state,
                uid -> {
                    recoveries.incrementAndGet();
                    return HansActiveRecoveryAdapter.Outcome.RECOVERED;
                });

        String status = hook.install(OplusHansManager.class.getClassLoader());
        scheduler.runAll();

        assertEquals("INSTALLED:runtime=6,worker=2", status);
        assertEquals(1, registry.addCalls);
        assertEquals(0, registry.removeCalls);
        assertEquals(1, recoveries.get());
        assertFalse(scheduler.shutdown);
        assertFalse(callExecutor.shutdown);
    }

    @Test
    public void registrationFailureRemovesPossiblePartialListenerAndStopsWorker() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor callExecutor = new ManualCallExecutor();
        RecordingRegistry registry = new RecordingRegistry();
        registry.failAdd = true;
        HansActiveRecoveryHook hook = hook(
                scheduler,
                callExecutor,
                registry,
                state,
                uid -> HansActiveRecoveryAdapter.Outcome.RECOVERED);

        String status = hook.install(OplusHansManager.class.getClassLoader());

        assertEquals("FAILED_FAIL_OPEN:RECOVERY_INITIALIZATION", status);
        assertEquals(1, registry.addCalls);
        assertEquals(1, registry.removeCalls);
        assertTrue(scheduler.shutdown);
        assertTrue(callExecutor.shutdown);
    }

    @Test
    public void rejectedWorkerProbeNeverRegistersAListener() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor callExecutor = new ManualCallExecutor();
        scheduler.reject = true;
        RecordingRegistry registry = new RecordingRegistry();
        HansActiveRecoveryHook hook = hook(
                scheduler,
                callExecutor,
                registry,
                state,
                uid -> HansActiveRecoveryAdapter.Outcome.RECOVERED);

        String status = hook.install(OplusHansManager.class.getClassLoader());

        assertEquals("FAILED_FAIL_OPEN:RECOVERY_THREAD_REJECTED", status);
        assertEquals(0, registry.addCalls);
        assertTrue(scheduler.shutdown);
        assertTrue(callExecutor.shutdown);
    }

    @Test
    public void missingCallExecutorShutsDownSchedulerBeforeRegistration() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler scheduler = new ManualScheduler();
        RecordingRegistry registry = new RecordingRegistry();
        HansActiveRecoveryRuntime.CapabilityMatrix matrix = capabilities();
        HansActiveRecoveryHook hook = new HansActiveRecoveryHook(
                ignored -> matrix,
                ignored -> uid -> HansActiveRecoveryAdapter.Outcome.RECOVERED,
                () -> scheduler,
                () -> null,
                HansActiveRecoveryPlanner.Capabilities::all,
                registry,
                () -> state,
                uid -> Set.of(PACKAGE),
                (request, outcome, adapterOutcome) -> { });

        String status = hook.install(getClass().getClassLoader());

        assertEquals("FAILED_FAIL_OPEN:RECOVERY_CALL_THREAD", status);
        assertTrue(scheduler.shutdown);
        assertEquals(0, registry.addCalls);
    }

    @Test
    public void capabilityDiscoveryFailureShutsDownBothWorkers() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor callExecutor = new ManualCallExecutor();
        RecordingRegistry registry = new RecordingRegistry();
        HansActiveRecoveryRuntime.CapabilityMatrix matrix = capabilities();
        HansActiveRecoveryHook hook = new HansActiveRecoveryHook(
                ignored -> matrix,
                ignored -> uid -> HansActiveRecoveryAdapter.Outcome.RECOVERED,
                () -> scheduler,
                () -> callExecutor,
                () -> { throw new IllegalStateException("capability failure"); },
                registry,
                () -> state,
                uid -> Set.of(PACKAGE),
                (request, outcome, adapterOutcome) -> { });

        String status = hook.install(getClass().getClassLoader());

        assertEquals("FAILED_FAIL_OPEN:RECOVERY_CAPABILITIES", status);
        assertTrue(scheduler.shutdown);
        assertTrue(callExecutor.shutdown);
        assertEquals(0, registry.addCalls);
    }

    @Test
    public void missingFirmwareCapabilitiesDoNotCreateAWorker() {
        AtomicInteger schedulerCreations = new AtomicInteger();
        HansActiveRecoveryHook hook = new HansActiveRecoveryHook(
                ignored -> HansActiveRecoveryRuntime.inspect(new ClassLoader(null) { }),
                matrix -> uid -> HansActiveRecoveryAdapter.Outcome.RECOVERED,
                () -> {
                    schedulerCreations.incrementAndGet();
                    return new ManualScheduler();
                },
                ManualCallExecutor::new,
                HansActiveRecoveryPlanner.Capabilities::all,
                new RecordingRegistry(),
                this::unavailableState,
                uid -> Collections.emptySet(),
                (request, outcome, adapterOutcome) -> { });

        String status = hook.install(getClass().getClassLoader());

        assertTrue(status.startsWith("UNSUPPORTED_"));
        assertEquals(0, schedulerCreations.get());
    }

    @Test
    public void reinstallShutsDownThePreviousWorkerAndReplacesTheListener() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler first = new ManualScheduler();
        ManualScheduler second = new ManualScheduler();
        ManualCallExecutor firstCall = new ManualCallExecutor();
        ManualCallExecutor secondCall = new ManualCallExecutor();
        Deque<ManualScheduler> schedulers = new ArrayDeque<>();
        schedulers.add(first);
        schedulers.add(second);
        Deque<ManualCallExecutor> callExecutors = new ArrayDeque<>();
        callExecutors.add(firstCall);
        callExecutors.add(secondCall);
        RecordingRegistry registry = new RecordingRegistry();
        HansActiveRecoveryRuntime.CapabilityMatrix matrix = capabilities();
        HansActiveRecoveryHook hook = new HansActiveRecoveryHook(
                ignored -> matrix,
                ignored -> uid -> HansActiveRecoveryAdapter.Outcome.NOT_FROZEN,
                schedulers::remove,
                callExecutors::remove,
                HansActiveRecoveryPlanner.Capabilities::all,
                registry,
                () -> state,
                uid -> Set.of(PACKAGE),
                (request, outcome, adapterOutcome) -> { });

        assertTrue(hook.install(getClass().getClassLoader()).startsWith("INSTALLED"));
        assertTrue(hook.install(getClass().getClassLoader()).startsWith("INSTALLED"));

        assertTrue(first.shutdown);
        assertTrue(firstCall.shutdown);
        assertFalse(second.shutdown);
        assertFalse(secondCall.shutdown);
        assertEquals(2, registry.addCalls);
        assertEquals(1, registry.removeCalls);
    }

    @Test
    public void fatalCoordinatorFailureUnregistersListenerAndStopsBothWorkers() {
        ResidencyRuntimeState state = readyState();
        ManualScheduler scheduler = new ManualScheduler();
        ManualCallExecutor callExecutor = new ManualCallExecutor();
        RecordingRegistry registry = new RecordingRegistry();
        HansActiveRecoveryHook hook = hook(
                scheduler,
                callExecutor,
                registry,
                state,
                uid -> HansActiveRecoveryAdapter.Outcome.RECOVERED);
        assertTrue(hook.install(getClass().getClassLoader()).startsWith("INSTALLED"));
        scheduler.runAll();

        enqueueCoordinatorFailure(hook);
        scheduler.runAll();

        assertEquals(1, registry.removeCalls);
        assertTrue(scheduler.shutdown);
        assertTrue(callExecutor.shutdown);
    }

    @Test
    public void diagnosticsUseStableCodesAndDistinguishSafetyAborts() {
        int[] expectedCodes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        Set<Integer> actualCodes = new HashSet<>();
        HansActiveRecoveryCoordinator.TerminalOutcome[] outcomes =
                HansActiveRecoveryCoordinator.TerminalOutcome.values();
        assertEquals(expectedCodes.length, outcomes.length);
        for (int index = 0; index < outcomes.length; index++) {
            int code = HansActiveRecoveryHook.DefaultDiagnosticsRecorder.stableCode(
                    outcomes[index]);
            assertEquals(expectedCodes[index], code);
            assertTrue(actualCodes.add(code));
        }
        assertEquals(DiagnosticsResult.SAFETY_ABORTED,
                HansActiveRecoveryHook.DefaultDiagnosticsRecorder.resultFor(
                        HansActiveRecoveryCoordinator.TerminalOutcome.INVALID_TARGET));
        assertEquals(DiagnosticsResult.SAFETY_ABORTED,
                HansActiveRecoveryHook.DefaultDiagnosticsRecorder.resultFor(
                        HansActiveRecoveryCoordinator.TerminalOutcome.UID_PACKAGE_MISMATCH));
    }

    @Test
    public void diagnosticsAttributeSharedUidRecoveryToEveryPackage() {
        assertEquals(
                "com.example.first,com.example.second",
                HansActiveRecoveryHook.DefaultDiagnosticsRecorder.packageLabel(
                        Set.of("com.example.first", "com.example.second")));
    }

    @Test
    public void behaviorCapabilitiesFollowInstalledGatesAndExcludeUnsafeOnes() {
        HansActiveRecoveryPlanner.Capabilities capabilities =
                HansActiveRecoveryHook.behaviorCapabilities(
                        EnumSet.of(
                                FreezeSource.NORMAL,
                                FreezeSource.SUPER,
                                FreezeSource.PRELOAD),
                        false,
                        true,
                        true);

        assertTrue(capabilities.supportsFreeze(FreezeSource.NORMAL));
        assertTrue(capabilities.supportsFreeze(FreezeSource.SUPER));
        assertTrue(capabilities.supportsFreeze(FreezeSource.PRELOAD));
        assertFalse(capabilities.supportsFreeze(FreezeSource.FAST));
        assertFalse(capabilities.coreExemption());
        assertTrue(capabilities.lcdOnExemption());
        assertTrue(capabilities.network());
        for (ResourceType resource : ResourceType.values()) {
            assertTrue(capabilities.supportsResource(resource));
        }
    }

    @Test
    public void missingBehaviorGatesProduceNoRecoveryCapabilities() {
        HansActiveRecoveryPlanner.Capabilities capabilities =
                HansActiveRecoveryHook.behaviorCapabilities(
                        Collections.emptySet(), false, false, false);

        for (FreezeSource source : FreezeSource.values()) {
            assertFalse(capabilities.supportsFreeze(source));
        }
        assertFalse(capabilities.coreExemption());
        assertFalse(capabilities.lcdOnExemption());
        assertFalse(capabilities.network());
        for (ResourceType resource : ResourceType.values()) {
            assertFalse(capabilities.supportsResource(resource));
        }
    }

    @Test
    public void fastFreezeRecoveryRequiresItsIndependentInstalledGate() {
        HansActiveRecoveryPlanner.Capabilities withoutFast =
                HansActiveRecoveryHook.behaviorCapabilities(
                        EnumSet.of(FreezeSource.NORMAL, FreezeSource.FAST),
                        false,
                        true,
                        true);
        HansActiveRecoveryPlanner.Capabilities withFast =
                HansActiveRecoveryHook.behaviorCapabilities(
                        EnumSet.of(FreezeSource.NORMAL), true, true, true);

        assertFalse(withoutFast.supportsFreeze(FreezeSource.FAST));
        assertTrue(withFast.supportsFreeze(FreezeSource.FAST));
        assertTrue(withFast.supportsFreeze(FreezeSource.NORMAL));
    }

    private HansActiveRecoveryHook hook(
            ManualScheduler scheduler,
            ManualCallExecutor callExecutor,
            RecordingRegistry registry,
            ResidencyRuntimeState state,
            HansActiveRecoveryCoordinator.RecoveryAction recoveryAction) {
        HansActiveRecoveryRuntime.CapabilityMatrix matrix = capabilities();
        return new HansActiveRecoveryHook(
                ignored -> matrix,
                ignored -> recoveryAction,
                () -> scheduler,
                () -> callExecutor,
                HansActiveRecoveryPlanner.Capabilities::all,
                registry,
                () -> state,
                uid -> Set.of(PACKAGE),
                (request, outcome, adapterOutcome) -> { });
    }

    private HansActiveRecoveryRuntime.CapabilityMatrix capabilities() {
        HansActiveRecoveryRuntime.CapabilityMatrix matrix =
                HansActiveRecoveryRuntime.inspect(OplusHansManager.class.getClassLoader());
        assertTrue(matrix.allAvailable());
        return matrix;
    }

    private static void enqueueCoordinatorFailure(HansActiveRecoveryHook hook) {
        try {
            Field coordinatorField = HansActiveRecoveryHook.class.getDeclaredField(
                    "coordinator");
            coordinatorField.setAccessible(true);
            Object coordinator = coordinatorField.get(hook);
            Method execute = HansActiveRecoveryCoordinator.class.getDeclaredMethod(
                    "execute", Runnable.class);
            execute.setAccessible(true);
            execute.invoke(coordinator, (Runnable) () -> {
                throw new AssertionError("fatal worker failure");
            });
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private ResidencyRuntimeState unavailableState() {
        return new ResidencyRuntimeController(Runnable::run).state();
    }

    private static ResidencyRuntimeState readyState() {
        ResidencyRuntimeController controller = new ResidencyRuntimeController(Runnable::run);
        controller.setPackageUidSource(new PackageUidSource() {
            @Override public boolean hasCompleteUidEnumeration() { return true; }

            @Override
            public OptionalInt findUid(String packageName) {
                return PACKAGE.equals(packageName)
                        ? OptionalInt.of(UID) : OptionalInt.empty();
            }

            @Override
            public Set<String> findPackagesForUid(int uid) {
                return uid == UID ? Set.of(PACKAGE) : Collections.emptySet();
            }
        });
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE, ResidencyMode.ACTIVE);
        controller.updateConfig(RuntimeConfigSnapshot.available(
                1L, true, true, false, 1L, Map.of(PACKAGE, policy)));
        assertTrue(controller.state().ready());
        return controller.state();
    }

    private static final class RecordingRegistry
            implements HansActiveRecoveryHook.StateListenerRegistry {
        private int addCalls;
        private int removeCalls;
        private boolean failAdd;
        private final AtomicReference<ResidencyRuntimeController.StateListener> listener =
                new AtomicReference<>();

        @Override
        public void add(ResidencyRuntimeController.StateListener listener) {
            addCalls++;
            this.listener.set(listener);
            if (failAdd) throw new IllegalStateException("listener failure");
        }

        @Override
        public void remove(ResidencyRuntimeController.StateListener listener) {
            removeCalls++;
            this.listener.compareAndSet(listener, null);
        }
    }

    private static final class ManualScheduler
            implements HansActiveRecoveryCoordinator.RecoveryScheduler {
        private final Deque<Runnable> ready = new ArrayDeque<>();
        private final Deque<Runnable> delayed = new ArrayDeque<>();
        private boolean reject;
        private boolean shutdown;

        @Override
        public boolean execute(Runnable task) {
            if (reject || shutdown) return false;
            ready.add(task);
            return true;
        }

        @Override
        public boolean schedule(Runnable task, long delayMs) {
            if (reject || shutdown) return false;
            delayed.add(task);
            return true;
        }

        @Override
        public void cancel(Runnable task) {
            delayed.remove(task);
        }

        @Override
        public void shutdown() {
            shutdown = true;
            ready.clear();
            delayed.clear();
        }

        private void runAll() {
            int guard = 1_000;
            while (!ready.isEmpty() && guard-- > 0) ready.remove().run();
            if (guard <= 0) throw new AssertionError("worker did not drain");
        }
    }

    private static final class ManualCallExecutor
            implements HansActiveRecoveryCoordinator.RecoveryCallExecutor {
        private boolean reject;
        private boolean shutdown;

        @Override
        public boolean execute(Runnable task) {
            if (reject || shutdown) return false;
            task.run();
            return true;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }
    }
}
