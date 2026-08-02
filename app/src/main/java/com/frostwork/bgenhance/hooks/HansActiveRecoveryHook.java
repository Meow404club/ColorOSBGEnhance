package com.frostwork.bgenhance.hooks;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.FeatureRegistry;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryAdapter;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator.RecoveryAction;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator.RecoveryCallExecutor;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryCoordinator.RecoveryScheduler;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores frozen HANS state asynchronously after effective per-UID policy changes. */
public final class HansActiveRecoveryHook implements FeatureHook {
    private final RuntimeInspector runtimeInspector;
    private final RecoveryActionFactory recoveryActionFactory;
    private final SchedulerFactory schedulerFactory;
    private final CallExecutorFactory callExecutorFactory;
    private final PlannerCapabilitiesSource plannerCapabilitiesSource;
    private final StateListenerRegistry stateListenerRegistry;
    private final HansActiveRecoveryCoordinator.RuntimeStateSource runtimeStateSource;
    private final HansActiveRecoveryCoordinator.UidPackageSource uidPackageSource;
    private final HansActiveRecoveryCoordinator.DiagnosticsRecorder diagnosticsRecorder;
    private final ResidencyRuntimeController.StateListener stateListener;
    private volatile HansActiveRecoveryCoordinator coordinator;
    private volatile boolean listenerRegistered;
    private volatile boolean active;

    public HansActiveRecoveryHook() {
        this(
                HansActiveRecoveryRuntime::inspect,
                matrix -> {
                    HansActiveRecoveryAdapter adapter =
                            HansActiveRecoveryAdapter.create(matrix);
                    return adapter == null ? null : adapter::recover;
                },
                HandlerThreadRecoveryScheduler::create,
                SingleThreadRecoveryCallExecutor::create,
                HansActiveRecoveryHook::installedBehaviorCapabilities,
                new StateListenerRegistry() {
                    @Override
                    public void add(ResidencyRuntimeController.StateListener listener) {
                        HookResidencyRuntime.addStateListener(listener);
                    }

                    @Override
                    public void remove(ResidencyRuntimeController.StateListener listener) {
                        HookResidencyRuntime.removeStateListener(listener);
                    }
                },
                HookResidencyRuntime::state,
                HookResidencyRuntime::findPackagesForUidForRecovery,
                new DefaultDiagnosticsRecorder());
    }

    HansActiveRecoveryHook(
            RuntimeInspector runtimeInspector,
            RecoveryActionFactory recoveryActionFactory,
            SchedulerFactory schedulerFactory,
            CallExecutorFactory callExecutorFactory,
            PlannerCapabilitiesSource plannerCapabilitiesSource,
            StateListenerRegistry stateListenerRegistry,
            HansActiveRecoveryCoordinator.RuntimeStateSource runtimeStateSource,
            HansActiveRecoveryCoordinator.UidPackageSource uidPackageSource,
            HansActiveRecoveryCoordinator.DiagnosticsRecorder diagnosticsRecorder) {
        this.runtimeInspector = Objects.requireNonNull(runtimeInspector, "runtimeInspector");
        this.recoveryActionFactory = Objects.requireNonNull(
                recoveryActionFactory, "recoveryActionFactory");
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory, "schedulerFactory");
        this.callExecutorFactory = Objects.requireNonNull(
                callExecutorFactory, "callExecutorFactory");
        this.plannerCapabilitiesSource = Objects.requireNonNull(
                plannerCapabilitiesSource, "plannerCapabilitiesSource");
        this.stateListenerRegistry = Objects.requireNonNull(
                stateListenerRegistry, "stateListenerRegistry");
        this.runtimeStateSource = Objects.requireNonNull(
                runtimeStateSource, "runtimeStateSource");
        this.uidPackageSource = Objects.requireNonNull(uidPackageSource, "uidPackageSource");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.stateListener = ignored -> onRuntimeStateChanged();
    }

    @Override public String hookTargetId() { return HookTargetIds.HANS_ACTIVE_RECOVERY; }
    @Override public String prefKey() { return ""; }

    @Override
    public synchronized String install(ClassLoader loader) {
        cleanup();
        HansActiveRecoveryRuntime.CapabilityMatrix matrix;
        try {
            matrix = runtimeInspector.inspect(loader);
        } catch (Throwable error) {
            return "FAILED_FAIL_OPEN:RUNTIME_INSPECTION";
        }
        if (matrix == null || !matrix.allAvailable()) return unavailableStatus(matrix);

        RecoveryAction recoveryAction;
        try {
            recoveryAction = recoveryActionFactory.create(matrix);
        } catch (Throwable error) {
            recoveryAction = null;
        }
        if (recoveryAction == null) return "FAILED_FAIL_OPEN:RECOVERY_ADAPTER";

        RecoveryScheduler scheduler;
        try {
            scheduler = schedulerFactory.create();
        } catch (Throwable error) {
            scheduler = null;
        }
        if (scheduler == null) return "FAILED_FAIL_OPEN:RECOVERY_THREAD";

        RecoveryCallExecutor callExecutor;
        try {
            callExecutor = callExecutorFactory.create();
        } catch (Throwable error) {
            callExecutor = null;
        }
        if (callExecutor == null) {
            shutdownQuietly(scheduler);
            return "FAILED_FAIL_OPEN:RECOVERY_CALL_THREAD";
        }

        HansActiveRecoveryPlanner.Capabilities plannerCapabilities;
        try {
            plannerCapabilities = plannerCapabilitiesSource.current();
        } catch (Throwable error) {
            plannerCapabilities = null;
        }
        if (plannerCapabilities == null) {
            shutdownQuietly(callExecutor);
            shutdownQuietly(scheduler);
            return "FAILED_FAIL_OPEN:RECOVERY_CAPABILITIES";
        }

        HansActiveRecoveryCoordinator created;
        try {
            created = new HansActiveRecoveryCoordinator(
                    new HansActiveRecoveryPlanner(plannerCapabilities),
                    scheduler,
                    callExecutor,
                    runtimeStateSource,
                    uidPackageSource,
                    recoveryAction,
                    diagnosticsRecorder,
                    this::onCoordinatorFatalFailure);
        } catch (Throwable error) {
            shutdownQuietly(callExecutor);
            shutdownQuietly(scheduler);
            return "FAILED_FAIL_OPEN:RECOVERY_COORDINATOR";
        }
        coordinator = created;
        try {
            if (!scheduler.execute(() -> { })) {
                cleanup();
                return "FAILED_FAIL_OPEN:RECOVERY_THREAD_REJECTED";
            }
            listenerRegistered = true;
            stateListenerRegistry.add(stateListener);
            active = true;
            if (!created.requestReconcile()) {
                cleanup();
                return "FAILED_FAIL_OPEN:RECOVERY_INITIALIZATION";
            }
            return "INSTALLED:runtime=6,worker=2";
        } catch (Throwable error) {
            cleanup();
            return "FAILED_FAIL_OPEN:RECOVERY_INITIALIZATION";
        }
    }

    private void onRuntimeStateChanged() {
        HansActiveRecoveryCoordinator current = coordinator;
        if (!active || current == null) return;
        current.requestReconcile();
    }

    private synchronized void onCoordinatorFatalFailure(
            HansActiveRecoveryCoordinator failed,
            Throwable error) {
        if (coordinator == failed) cleanup();
    }

    private synchronized void cleanup() {
        active = false;
        if (listenerRegistered) {
            try {
                stateListenerRegistry.remove(stateListener);
            } catch (Throwable ignored) {
                // Installation already remains fail-open.
            }
            listenerRegistered = false;
        }
        HansActiveRecoveryCoordinator current = coordinator;
        coordinator = null;
        if (current != null) current.close();
    }

    private static String unavailableStatus(
            HansActiveRecoveryRuntime.CapabilityMatrix matrix) {
        if (matrix == null) return "FAILED_FAIL_OPEN:RUNTIME_UNAVAILABLE";
        for (HansActiveRecoveryRuntime.Capability capability
                : HansActiveRecoveryRuntime.Capability.values()) {
            if (matrix.status(capability) == HansActiveRecoveryRuntime.Status.LOOKUP_FAILED) {
                return "FAILED_FAIL_OPEN:" + matrix.summary();
            }
        }
        return "UNSUPPORTED_" + matrix.summary();
    }

    private static void shutdownQuietly(RecoveryCallExecutor executor) {
        try {
            executor.shutdown();
        } catch (Throwable ignored) {
            // Installation already remains fail-open.
        }
    }

    private static void shutdownQuietly(RecoveryScheduler scheduler) {
        try {
            scheduler.shutdown();
        } catch (Throwable ignored) {
            // Installation already remains fail-open.
        }
    }

    interface RuntimeInspector {
        HansActiveRecoveryRuntime.CapabilityMatrix inspect(ClassLoader loader);
    }

    interface RecoveryActionFactory {
        RecoveryAction create(HansActiveRecoveryRuntime.CapabilityMatrix matrix);
    }

    interface SchedulerFactory {
        RecoveryScheduler create();
    }

    interface CallExecutorFactory {
        RecoveryCallExecutor create();
    }

    interface PlannerCapabilitiesSource {
        HansActiveRecoveryPlanner.Capabilities current();
    }

    interface StateListenerRegistry {
        void add(ResidencyRuntimeController.StateListener listener);
        void remove(ResidencyRuntimeController.StateListener listener);
    }

    static HansActiveRecoveryPlanner.Capabilities installedBehaviorCapabilities() {
        return behaviorCapabilities(
                FeatureRegistry.isInstalled(HookTargetIds.HANS_FREEZE_DISPATCH)
                        ? HansFreezeHook.installedFreezeSources()
                        : Collections.emptySet(),
                FeatureRegistry.isInstalled(HookTargetIds.HANS_LCD_ON_EXEMPTION),
                FeatureRegistry.isInstalled(HookTargetIds.HANS_RESOURCE_DISPATCH));
    }

    static HansActiveRecoveryPlanner.Capabilities behaviorCapabilities(
            java.util.Set<FreezeSource> installedFreezeSources,
            boolean lcdOnExemptionInstalled,
            boolean resourceInstalled) {
        EnumSet<ResourceType> resources = resourceInstalled
                ? EnumSet.allOf(ResourceType.class)
                : EnumSet.noneOf(ResourceType.class);
        return new HansActiveRecoveryPlanner.Capabilities(
                Objects.requireNonNull(installedFreezeSources, "installedFreezeSources"),
                false,
                lcdOnExemptionInstalled,
                resourceInstalled,
                resources.isEmpty() ? Collections.emptySet() : resources);
    }

    static final class DefaultDiagnosticsRecorder
            implements HansActiveRecoveryCoordinator.DiagnosticsRecorder {
        static final int CODE_RECOVERED = 1;
        static final int CODE_NOT_FROZEN = 2;
        static final int CODE_TARGET_UNAVAILABLE = 3;
        static final int CODE_INVALID_TARGET = 4;
        static final int CODE_UID_PACKAGE_MISMATCH = 5;
        static final int CODE_RETRY_EXHAUSTED = 6;
        static final int CODE_SCHEDULER_REJECTED = 7;
        static final int CODE_CALL_EXECUTOR_REJECTED = 8;
        static final int CODE_CALL_TIMEOUT = 9;
        static final int CODE_CAPACITY_DROPPED = 10;

        @Override
        public void record(
                HansActiveRecoveryCoordinator.Request request,
                HansActiveRecoveryCoordinator.TerminalOutcome outcome,
                HansActiveRecoveryAdapter.Outcome adapterOutcome) {
            String packageName = packageLabel(request.expectedPackages());
            int code = stableCode(outcome);
            String scene = outcome.name().toLowerCase(Locale.ROOT);
            if (adapterOutcome != null) {
                scene += ":" + adapterOutcome.name().toLowerCase(Locale.ROOT);
            }
            HookEvent event = new HookEvent(
                    FeatureIds.HANS_RECOVERY,
                    code,
                    request.uid(),
                    packageName,
                    "",
                    scene);
            String result = resultFor(outcome);
            HookDiagnostics.recordEvent(event, result);
        }

        static String packageLabel(java.util.Set<String> packages) {
            return packages == null
                    ? ""
                    : String.join(",", new java.util.TreeSet<>(packages));
        }

        static String resultFor(HansActiveRecoveryCoordinator.TerminalOutcome outcome) {
            switch (outcome) {
                case RECOVERED:
                    return DiagnosticsResult.RECOVERED;
                case RETRY_EXHAUSTED:
                case SCHEDULER_REJECTED:
                case CALL_EXECUTOR_REJECTED:
                case CALL_TIMEOUT:
                case CAPACITY_DROPPED:
                    return DiagnosticsResult.RUNTIME_FAIL_OPEN;
                case INVALID_TARGET:
                case UID_PACKAGE_MISMATCH:
                    return DiagnosticsResult.SAFETY_ABORTED;
                case NOT_FROZEN:
                case TARGET_UNAVAILABLE:
                default:
                    return DiagnosticsResult.NO_ACTION_NEEDED;
            }
        }

        @Override
        public void workerFailed(Throwable error) {
            String type = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
            HookDiagnostics.reportFeature(
                    HookTargetIds.HANS_ACTIVE_RECOVERY,
                    "FAILED_FAIL_OPEN:WORKER_" + type);
        }

        static int stableCode(HansActiveRecoveryCoordinator.TerminalOutcome outcome) {
            switch (outcome) {
                case RECOVERED: return CODE_RECOVERED;
                case NOT_FROZEN: return CODE_NOT_FROZEN;
                case TARGET_UNAVAILABLE: return CODE_TARGET_UNAVAILABLE;
                case INVALID_TARGET: return CODE_INVALID_TARGET;
                case UID_PACKAGE_MISMATCH: return CODE_UID_PACKAGE_MISMATCH;
                case RETRY_EXHAUSTED: return CODE_RETRY_EXHAUSTED;
                case SCHEDULER_REJECTED: return CODE_SCHEDULER_REJECTED;
                case CALL_EXECUTOR_REJECTED: return CODE_CALL_EXECUTOR_REJECTED;
                case CALL_TIMEOUT: return CODE_CALL_TIMEOUT;
                case CAPACITY_DROPPED: return CODE_CAPACITY_DROPPED;
                default: throw new AssertionError("unmapped recovery outcome " + outcome);
            }
        }
    }

    private static final class SingleThreadRecoveryCallExecutor
            implements RecoveryCallExecutor {
        private static final String THREAD_NAME = "BgEnhanceHansCall";

        private final ThreadPoolExecutor executor;
        private final AtomicBoolean shutdown = new AtomicBoolean();

        private SingleThreadRecoveryCallExecutor(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        private static RecoveryCallExecutor create() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    runnable -> {
                        Thread thread = new Thread(() -> {
                            try {
                                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                            } catch (Throwable ignored) {
                                // Thread priority is best effort.
                            }
                            runnable.run();
                        }, THREAD_NAME);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            try {
                executor.prestartCoreThread();
                return new SingleThreadRecoveryCallExecutor(executor);
            } catch (Throwable error) {
                executor.shutdownNow();
                throw error;
            }
        }

        @Override
        public boolean execute(Runnable task) {
            if (shutdown.get()) return false;
            try {
                executor.execute(Objects.requireNonNull(task, "task"));
                return true;
            } catch (RejectedExecutionException ignored) {
                return false;
            }
        }

        @Override
        public void shutdown() {
            if (!shutdown.compareAndSet(false, true)) return;
            executor.shutdownNow();
        }
    }

    private static final class HandlerThreadRecoveryScheduler implements RecoveryScheduler {
        private static final String THREAD_NAME = "BgEnhanceHansRecovery";

        private final HandlerThread thread;
        private final Handler handler;
        private final AtomicBoolean shutdown = new AtomicBoolean();

        private HandlerThreadRecoveryScheduler(HandlerThread thread, Handler handler) {
            this.thread = thread;
            this.handler = handler;
        }

        private static RecoveryScheduler create() {
            HandlerThread thread = new HandlerThread(
                    THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            if (thread.getLooper() == null) {
                thread.quitSafely();
                return null;
            }
            return new HandlerThreadRecoveryScheduler(
                    thread, new Handler(thread.getLooper()));
        }

        @Override
        public boolean execute(Runnable task) {
            return !shutdown.get() && handler.post(Objects.requireNonNull(task, "task"));
        }

        @Override
        public boolean schedule(Runnable task, long delayMs) {
            if (delayMs < 0L) throw new IllegalArgumentException("delayMs < 0");
            return !shutdown.get()
                    && handler.postDelayed(Objects.requireNonNull(task, "task"), delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            if (task != null) handler.removeCallbacks(task);
        }

        @Override
        public void shutdown() {
            if (!shutdown.compareAndSet(false, true)) return;
            handler.removeCallbacksAndMessages(null);
            thread.quitSafely();
        }
    }
}
