package com.frostwork.bgenhance.hooks;

import android.os.Message;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRegistry;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRuntime;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeStateAdapter;
import com.frostwork.bgenhance.hooks.hans.HansWakeSourceClassifier;
import com.frostwork.bgenhance.residency.decision.FreezeTimingContext;
import com.frostwork.bgenhance.residency.decision.FreezeTimingDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies per-source M-to-F delays and safely consumes disabled timed refreeze messages. */
public final class HansRefreezeHook implements FeatureHook {
    private static final String CONFIG_CLASS = "com.android.server.hans.OplusHansDBConfig";
    private static final String MIDDLE_STATE_CLASS =
            "com.android.server.hans.states.HansAppStateMachine$MiddleState";
    private static final String FROZEN_STATE_CLASS =
            "com.android.server.hans.states.HansAppStateMachine$FrozenState";
    static final int M_TO_F_MESSAGE = 8;
    static final int FORCE_FREEZE_MESSAGE = 21;
    static final int FAST_FREEZE_EXIT_MESSAGE = 102;
    static final int UNKNOWN_SOURCE_CODE = -1;
    private static final long[] RECOVERY_RETRY_DELAYS_MS =
            {100L, 500L, 2_000L, 5_000L, 10_000L};
    private static final long RECOVERY_CONFIRMATION_TIMEOUT_MS = 2_000L;
    private static final ScheduledExecutorService RECOVERY_SERVICE =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceHansRefreeze");
        thread.setDaemon(true);
        return thread;
    });
    private static final RecoveryExecutor RECOVERY_EXECUTOR =
            new DefaultRecoveryExecutor(RECOVERY_SERVICE);

    private final FreezeTimingDecisionResolver timingResolver;
    private final RefreezeDecisionResolver refreezeResolver;
    private final RefreezeDiagnosticsRecorder diagnosticsRecorder;
    private final HansRefreezeRegistry registry;
    private final HookInstaller hookInstaller;
    private final RuntimeStateListenerRegistrar stateListenerRegistrar;
    private final RuntimeStateSource runtimeStateSource;
    private final RecoveryExecutor recoveryExecutor;
    private final RecoveryConfirmationScheduler recoveryConfirmationScheduler;
    private final RecoveryMessageSender recoveryMessageSender;
    private final ResidencyRuntimeController.StateListener runtimeStateListener;
    private final ThreadLocal<TransitionFrame> transitionFrame = new ThreadLocal<>();
    private final Object runtimeStateLock = new Object();
    private final AtomicBoolean recoveryEnqueued = new AtomicBoolean();
    private final AtomicBoolean recoveryAgain = new AtomicBoolean();
    private final AtomicBoolean recoveryDispatchRetryScheduled = new AtomicBoolean();
    private volatile long latestRuntimeGeneration;
    private volatile ResidencyRuntimeState latestRuntimeState;
    private volatile boolean active;

    public HansRefreezeHook() {
        this(
                HansRefreezeHook::decideTiming,
                HansRefreezeHook::decideRefreeze,
                new DefaultRefreezeDiagnosticsRecorder(),
                new HansRefreezeRegistry(),
                HansRefreezeHook::installHook,
                listener -> {
                    HookResidencyRuntime.addStateListener(listener);
                    return HookResidencyRuntime.state();
                },
                HookResidencyRuntime::state,
                RECOVERY_EXECUTOR,
                RECOVERY_EXECUTOR::schedule,
                HansRefreezeStateAdapter::requestMtoF,
                false);
    }

    HansRefreezeHook(
            FreezeTimingDecisionResolver timingResolver,
            RefreezeDecisionResolver refreezeResolver,
            RefreezeDiagnosticsRecorder diagnosticsRecorder,
            HansRefreezeRegistry registry) {
        this(
                timingResolver,
                refreezeResolver,
                diagnosticsRecorder,
                registry,
                HansRefreezeHook::installHook,
                listener -> null,
                () -> null,
                RecoveryExecutor.direct(),
                (runnable, delayMs) -> true,
                HansRefreezeStateAdapter::requestMtoF,
                true);
    }

    HansRefreezeHook(
            FreezeTimingDecisionResolver timingResolver,
            RefreezeDecisionResolver refreezeResolver,
            RefreezeDiagnosticsRecorder diagnosticsRecorder,
            HansRefreezeRegistry registry,
            HookInstaller hookInstaller) {
        this(
                timingResolver,
                refreezeResolver,
                diagnosticsRecorder,
                registry,
                hookInstaller,
                listener -> null,
                () -> null,
                RecoveryExecutor.direct(),
                (runnable, delayMs) -> true,
                HansRefreezeStateAdapter::requestMtoF);
    }

    HansRefreezeHook(
            FreezeTimingDecisionResolver timingResolver,
            RefreezeDecisionResolver refreezeResolver,
            RefreezeDiagnosticsRecorder diagnosticsRecorder,
            HansRefreezeRegistry registry,
            HookInstaller hookInstaller,
            RuntimeStateListenerRegistrar stateListenerRegistrar,
            RecoveryExecutor recoveryExecutor,
            RecoveryMessageSender recoveryMessageSender) {
        this(
                timingResolver,
                refreezeResolver,
                diagnosticsRecorder,
                registry,
                hookInstaller,
                stateListenerRegistrar,
                () -> null,
                recoveryExecutor,
                (runnable, delayMs) -> true,
                recoveryMessageSender);
    }

    HansRefreezeHook(
            FreezeTimingDecisionResolver timingResolver,
            RefreezeDecisionResolver refreezeResolver,
            RefreezeDiagnosticsRecorder diagnosticsRecorder,
            HansRefreezeRegistry registry,
            HookInstaller hookInstaller,
            RuntimeStateListenerRegistrar stateListenerRegistrar,
            RuntimeStateSource runtimeStateSource,
            RecoveryExecutor recoveryExecutor,
            RecoveryMessageSender recoveryMessageSender) {
        this(
                timingResolver,
                refreezeResolver,
                diagnosticsRecorder,
                registry,
                hookInstaller,
                stateListenerRegistrar,
                runtimeStateSource,
                recoveryExecutor,
                (runnable, delayMs) -> true,
                recoveryMessageSender);
    }

    HansRefreezeHook(
            FreezeTimingDecisionResolver timingResolver,
            RefreezeDecisionResolver refreezeResolver,
            RefreezeDiagnosticsRecorder diagnosticsRecorder,
            HansRefreezeRegistry registry,
            HookInstaller hookInstaller,
            RuntimeStateListenerRegistrar stateListenerRegistrar,
            RuntimeStateSource runtimeStateSource,
            RecoveryExecutor recoveryExecutor,
            RecoveryConfirmationScheduler recoveryConfirmationScheduler,
            RecoveryMessageSender recoveryMessageSender) {
        this(
                timingResolver,
                refreezeResolver,
                diagnosticsRecorder,
                registry,
                hookInstaller,
                stateListenerRegistrar,
                runtimeStateSource,
                recoveryExecutor,
                recoveryConfirmationScheduler,
                recoveryMessageSender,
                false);
    }

    private HansRefreezeHook(
            FreezeTimingDecisionResolver timingResolver,
            RefreezeDecisionResolver refreezeResolver,
            RefreezeDiagnosticsRecorder diagnosticsRecorder,
            HansRefreezeRegistry registry,
            HookInstaller hookInstaller,
            RuntimeStateListenerRegistrar stateListenerRegistrar,
            RuntimeStateSource runtimeStateSource,
            RecoveryExecutor recoveryExecutor,
            RecoveryConfirmationScheduler recoveryConfirmationScheduler,
            RecoveryMessageSender recoveryMessageSender,
            boolean active) {
        this.timingResolver = Objects.requireNonNull(timingResolver, "timingResolver");
        this.refreezeResolver = Objects.requireNonNull(refreezeResolver, "refreezeResolver");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hookInstaller = Objects.requireNonNull(hookInstaller, "hookInstaller");
        this.stateListenerRegistrar = Objects.requireNonNull(
                stateListenerRegistrar, "stateListenerRegistrar");
        this.runtimeStateSource = Objects.requireNonNull(
                runtimeStateSource, "runtimeStateSource");
        this.recoveryExecutor = Objects.requireNonNull(recoveryExecutor, "recoveryExecutor");
        this.recoveryConfirmationScheduler = Objects.requireNonNull(
                recoveryConfirmationScheduler, "recoveryConfirmationScheduler");
        this.recoveryMessageSender = Objects.requireNonNull(
                recoveryMessageSender, "recoveryMessageSender");
        this.runtimeStateListener = this::onRuntimeStateChanged;
        this.active = active;
    }

    @Override public String hookTargetId() { return HookTargetIds.HANS_M_TO_F_TIMING; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        active = false;
        HansRefreezeRuntime.CapabilityMatrix matrix = HansRefreezeRuntime.inspect(loader);
        if (!matrix.allAvailable()) return unavailableStatus(matrix);

        if (!installOne(matrix, HansRefreezeRuntime.Capability.M_TO_F_TIMING,
                CONFIG_CLASS, loader, this::interceptTiming)) {
            return partialStatus(HansRefreezeRuntime.Capability.M_TO_F_TIMING);
        }
        if (!installOne(matrix, HansRefreezeRuntime.Capability.FROZEN_TO_MIDDLE,
                FROZEN_STATE_CLASS, loader, this::interceptFrozenToMiddle)) {
            return partialStatus(HansRefreezeRuntime.Capability.FROZEN_TO_MIDDLE);
        }
        if (!installOne(matrix, HansRefreezeRuntime.Capability.MIDDLE_ENTER,
                MIDDLE_STATE_CLASS, loader, this::interceptMiddleEnter)) {
            return partialStatus(HansRefreezeRuntime.Capability.MIDDLE_ENTER);
        }
        if (!installOne(matrix, HansRefreezeRuntime.Capability.MIDDLE_EXIT,
                MIDDLE_STATE_CLASS, loader, this::interceptMiddleExit)) {
            return partialStatus(HansRefreezeRuntime.Capability.MIDDLE_EXIT);
        }
        if (!installOne(matrix, HansRefreezeRuntime.Capability.MIDDLE_PROCESS_MESSAGE,
                MIDDLE_STATE_CLASS, loader, this::interceptMiddleProcess)) {
            return partialStatus(HansRefreezeRuntime.Capability.MIDDLE_PROCESS_MESSAGE);
        }

        ResidencyRuntimeState initialState;
        try {
            initialState = stateListenerRegistrar.register(runtimeStateListener);
        } catch (Throwable error) {
            return "FAILED_FAIL_OPEN:RECOVERY_LISTENER";
        }
        active = true;
        if (initialState != null) onRuntimeStateChanged(initialState);
        ResidencyRuntimeState observedState = latestRuntimeState;
        if (observedState != null && observedState != initialState) {
            onRuntimeStateChanged(observedState);
        }
        return "INSTALLED:timing=1,state=4";
    }

    private boolean installOne(
            HansRefreezeRuntime.CapabilityMatrix matrix,
            HansRefreezeRuntime.Capability capability,
            String className,
            ClassLoader loader,
            XposedInterface.Hooker hooker) {
        Method method = matrix.methodOrNull(capability);
        if (method == null || !HansRefreezeRuntime.matches(capability, method)) return false;
        return hookInstaller.hookMatching(
                className, loader, method::equals, hooker) == 1;
    }

    Object interceptTiming(XposedInterface.Chain chain) throws Throwable {
        Object original = chain.proceed();
        if (!active || !(original instanceof Long)) return original;

        Object uidValue = HookUtils.objectArg(chain, 0);
        Object packageValue = HookUtils.objectArg(chain, 1);
        Object reasonValue = HookUtils.objectArg(chain, 4);
        if (!(uidValue instanceof Integer)
                || !(packageValue instanceof String)
                || !(reasonValue instanceof String)) {
            return original;
        }
        int uid = (Integer) uidValue;
        String packageName = (String) packageValue;
        String reason = (String) reasonValue;
        ResidencyTarget target = HansTimingHook.target(uid, packageName);
        if (target == null) return original;

        TransitionFrame frame = transitionFrame.get();
        if (frame != null) {
            HansWakeSourceClassifier.Resolution source = classify(reason);
            if (!frame.armed
                    || source == null
                    || !source.isResolved()
                    || !frame.matches(uid, packageName, source.sourceOrNull())) {
                return original;
            }
            RefreezeDecision decision;
            try {
                decision = refreezeResolver.decide(target, frame.source);
            } catch (Throwable ignored) {
                return original;
            }
            return applyRefreezeDelay(original, decision);
        }

        if (!reason.isEmpty()) return original;
        FreezeTimingDecision timing;
        try {
            timing = timingResolver.decide(target);
        } catch (Throwable ignored) {
            return original;
        }
        if (timing != null) {
            try {
                diagnosticsRecorder.recordTimingDecision(timingEvent(target), timing);
            } catch (Throwable ignored) {
                // Diagnostics must not change a HANS timing query.
            }
        }
        return applyTimingDelay(original, timing);
    }

    Object interceptFrozenToMiddle(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        HansRefreezeStateAdapter.Resolution resolution = resolveState(
                chain.getThisObject(), "frozen_to_middle");
        if (resolution == null) return chain.proceed();

        Object stateMachine = resolution.stateMachineOrNull();
        HansObjectAdapter.Target target = resolution.targetOrNull();
        String reason = resolution.reasonOrNull();
        HansWakeSourceClassifier.Resolution classified = classify(reason);
        if (classified == null || !classified.isResolved()) {
            registry.remove(stateMachine);
            recordFailure(event(target, null, reason));
            return chain.proceed();
        }

        WakeSource source = classified.sourceOrNull();
        ResidencyTarget residencyTarget = new ResidencyTarget(
                target.packageName(), target.uid(), ResidencyTarget.UNKNOWN_PID);
        boolean armed = registry.arm(stateMachine, residencyTarget, source).armed();
        if (!armed) recordFailure(event(target, source, reason));
        TransitionFrame previous = transitionFrame.get();
        transitionFrame.set(new TransitionFrame(
                target.uid(), target.packageName(), source, armed));
        try {
            return chain.proceed();
        } catch (Throwable error) {
            registry.remove(stateMachine);
            throw error;
        } finally {
            restoreFrame(previous);
        }
    }

    Object interceptMiddleEnter(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        HansRefreezeStateAdapter.Resolution resolution = resolveState(
                chain.getThisObject(), "middle_enter");
        Object stateMachine = resolution == null ? null : resolution.stateMachineOrNull();
        if (stateMachine != null) registry.bindState(stateMachine, chain.getThisObject());
        boolean activated = stateMachine != null && registry.activate(stateMachine);
        try {
            return chain.proceed();
        } catch (Throwable error) {
            if (activated) registry.remove(stateMachine);
            throw error;
        }
    }

    Object interceptMiddleExit(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        HansRefreezeStateAdapter.Resolution resolution = resolveState(
                chain.getThisObject(), "middle_exit");
        if (resolution != null) registry.remove(resolution.stateMachineOrNull());
        return chain.proceed();
    }

    Object interceptMiddleProcess(XposedInterface.Chain chain) throws Throwable {
        Object message = HookUtils.objectArg(chain, 0);
        if (!(message instanceof Message)) return chain.proceed();
        Message value = (Message) message;
        return interceptMiddleProcess(chain, value.what, value.obj);
    }

    Object interceptMiddleProcess(XposedInterface.Chain chain, int what) throws Throwable {
        return interceptMiddleProcess(chain, what, null);
    }

    Object interceptMiddleProcess(
            XposedInterface.Chain chain, int what, Object messageObject) throws Throwable {
        if (!active) return chain.proceed();
        if (what == FORCE_FREEZE_MESSAGE) {
            clearFastFreezeOwnershipForForcedMessage(chain.getThisObject());
            return chain.proceed();
        }
        if (what == M_TO_F_MESSAGE) {
            return interceptTimedRefreeze(chain, messageObject);
        }
        if (what == FAST_FREEZE_EXIT_MESSAGE) return interceptFastFreezeExit(chain);
        return chain.proceed();
    }

    private Object interceptTimedRefreeze(
            XposedInterface.Chain chain, Object messageObject) throws Throwable {
        RecoveryToken recoveryToken = messageObject instanceof RecoveryToken
                ? (RecoveryToken) messageObject : null;
        HansRefreezeStateAdapter.Resolution resolution = resolveState(
                chain.getThisObject(), "middle_message");
        if (resolution == null) {
            if (recoveryToken == null) return chain.proceed();
            recoveryDeliveryFailed(recoveryToken);
            return Boolean.TRUE;
        }

        Object stateMachine = resolution.stateMachineOrNull();
        HansObjectAdapter.Target target = resolution.targetOrNull();
        registry.bindState(stateMachine, chain.getThisObject());
        HansRefreezeRegistry.ActiveCycle cycle = registry.activeCycleOrNull(stateMachine);
        if (cycle == null) {
            return recoveryToken == null ? chain.proceed() : Boolean.TRUE;
        }
        boolean recoveryQueued = recoveryToken != null;
        if (recoveryQueued
                && (recoveryToken.stateMachineOrNull() != stateMachine
                || !registry.isRecoveryQueued(
                stateMachine,
                recoveryToken.cycleGeneration,
                recoveryToken.runtimeGeneration))) {
            return Boolean.TRUE;
        }
        WakeSource source = cycle.source();
        if (!cycleMatchesTarget(cycle, target)) {
            registry.remove(stateMachine);
            recordFailure(event(target, source, resolution.reasonOrNull()));
            return recoveryToken == null ? chain.proceed() : Boolean.TRUE;
        }

        HansWakeSourceClassifier.Resolution current = classify(resolution.reasonOrNull());
        if (current == null || !current.isResolved()) {
            if (recoveryQueued) {
                registry.claimRecovery(
                        stateMachine,
                        recoveryToken.cycleGeneration,
                        recoveryToken.runtimeGeneration);
            }
            registry.remove(stateMachine);
            recordFailure(event(target, source, resolution.reasonOrNull()));
            return chain.proceed();
        }
        if (current.sourceOrNull() != source) {
            cycle = registry.rebindSource(
                    stateMachine,
                    cycle.generation(),
                    cycle.target(),
                    current.sourceOrNull());
            if (cycle == null) {
                recordFailure(event(target, source, resolution.reasonOrNull()));
                return recoveryToken == null ? chain.proceed() : Boolean.TRUE;
            }
            source = cycle.source();
            recoveryQueued = false;
        }

        ResidencyRuntimeState runtimeBeforeDecision = authoritativeRuntimeState();
        if (recoveryQueued && isIdentityRebuildPending(runtimeBeforeDecision)) {
            deferRecovery(
                    stateMachine,
                    cycle.generation(),
                    runtimeBeforeDecision.generation(),
                    false);
            return Boolean.TRUE;
        }

        RefreezeDecision decision;
        HookEvent event = event(target, source, resolution.reasonOrNull());
        try {
            decision = refreezeResolver.decide(
                    new ResidencyTarget(
                            target.packageName(),
                            target.uid(),
                            ResidencyTarget.UNKNOWN_PID),
                    source);
        } catch (Throwable error) {
            if (recoveryQueued && !registry.claimRecovery(
                    stateMachine,
                    recoveryToken.cycleGeneration,
                    recoveryToken.runtimeGeneration)) {
                return Boolean.TRUE;
            }
            recordFailure(event);
            return chain.proceed();
        }
        if (decision == null) {
            if (recoveryQueued && !registry.claimRecovery(
                    stateMachine,
                    recoveryToken.cycleGeneration,
                    recoveryToken.runtimeGeneration)) {
                return Boolean.TRUE;
            }
            recordFailure(event);
            return chain.proceed();
        }
        if (recoveryQueued) {
            ResidencyRuntimeState runtimeAfterDecision = authoritativeRuntimeState();
            if (!recoveryDecisionCurrent(
                    recoveryToken, decision, runtimeAfterDecision)) {
                long generation = runtimeAfterDecision == null
                        ? decision.trace().identityGeneration()
                        : runtimeAfterDecision.generation();
                deferRecovery(
                        stateMachine,
                        cycle.generation(),
                        generation,
                        !isIdentityRebuildPending(runtimeAfterDecision));
                return Boolean.TRUE;
            }
        }
        if (decision.effectiveAction() == RefreezeDecision.Action.DISABLE_REFREEZE) {
            BlockResult block = blockForDecision(stateMachine, cycle, decision);
            if (block == BlockResult.FAILED) {
                recordFailure(event);
                return chain.proceed();
            }
            if (block == BlockResult.STALE_PASSTHROUGH) {
                recordFailure(event);
                return chain.proceed();
            }
            recordDecision(event, decision);
            return Boolean.TRUE;
        }
        if (recoveryQueued && !registry.claimRecovery(
                stateMachine,
                recoveryToken.cycleGeneration,
                recoveryToken.runtimeGeneration)) {
            return Boolean.TRUE;
        }

        Boolean fastBefore = HansRefreezeStateAdapter.fastFreezePendingOrNull(stateMachine);
        Object original;
        try {
            original = chain.proceed();
        } catch (Throwable error) {
            registry.remove(stateMachine);
            throw error;
        }
        Boolean fastAfter = HansRefreezeStateAdapter.fastFreezePendingOrNull(stateMachine);
        if (Boolean.FALSE.equals(fastBefore)
                && Boolean.TRUE.equals(fastAfter)
                && registry.markFastFreezeOwned(stateMachine)) {
            return original;
        }
        recordDecision(event, decision);
        return original;
    }

    private Object interceptFastFreezeExit(XposedInterface.Chain chain) throws Throwable {
        HansRefreezeStateAdapter.Resolution resolution = resolveState(
                chain.getThisObject(), "fast_freeze_exit");
        if (resolution == null) return chain.proceed();

        Object stateMachine = resolution.stateMachineOrNull();
        registry.bindState(stateMachine, chain.getThisObject());
        HansRefreezeRegistry.ActiveCycle cycle = registry.activeCycleOrNull(stateMachine);
        if (cycle == null || !cycle.fastFreezeOwned()) {
            return chain.proceed();
        }
        WakeSource source = cycle.source();
        if (!cycleMatchesTarget(cycle, resolution.targetOrNull())) {
            registry.remove(stateMachine);
            recordFailure(event(
                    resolution.targetOrNull(), source, resolution.reasonOrNull()));
            return chain.proceed();
        }
        HansWakeSourceClassifier.Resolution current = classify(resolution.reasonOrNull());
        if (current == null || !current.isResolved()) {
            registry.remove(stateMachine);
            recordFailure(event(
                    resolution.targetOrNull(), source, resolution.reasonOrNull()));
            return chain.proceed();
        }
        if (current.sourceOrNull() != source) {
            cycle = registry.rebindSource(
                    stateMachine,
                    cycle.generation(),
                    cycle.target(),
                    current.sourceOrNull());
            if (cycle == null) {
                recordFailure(event(
                        resolution.targetOrNull(), source, resolution.reasonOrNull()));
                return chain.proceed();
            }
            source = cycle.source();
        }
        Boolean fastPending = HansRefreezeStateAdapter.fastFreezePendingOrNull(stateMachine);
        if (!Boolean.TRUE.equals(fastPending)) {
            registry.clearFastFreezeOwned(stateMachine);
            return chain.proceed();
        }

        HansObjectAdapter.Target target = resolution.targetOrNull();
        HookEvent event = event(target, source, resolution.reasonOrNull());
        RefreezeDecision decision;
        try {
            decision = refreezeResolver.decide(
                    new ResidencyTarget(
                            target.packageName(),
                            target.uid(),
                            ResidencyTarget.UNKNOWN_PID),
                    source);
        } catch (Throwable error) {
            recordFailure(event);
            return proceedFastFreezeExit(chain, stateMachine);
        }
        if (decision == null) {
            recordFailure(event);
            return proceedFastFreezeExit(chain, stateMachine);
        }
        if (decision.effectiveAction() != RefreezeDecision.Action.DISABLE_REFREEZE) {
            Object original = proceedFastFreezeExit(chain, stateMachine);
            recordDecision(event, decision);
            return original;
        }

        BlockResult block = blockForDecision(stateMachine, cycle, decision);
        if (block == BlockResult.FAILED
                || block == BlockResult.STALE_PASSTHROUGH) {
            recordFailure(event);
            return proceedFastFreezeExit(chain, stateMachine);
        }
        if (!HansRefreezeStateAdapter.clearFastFreezePending(stateMachine)) {
            registry.releaseBlocked(
                    stateMachine,
                    cycle.generation(),
                    decision.trace().identityGeneration());
            recordFailure(event);
            return proceedFastFreezeExit(chain, stateMachine);
        }
        registry.clearFastFreezeOwned(stateMachine);
        recordDecision(event, decision);
        return Boolean.TRUE;
    }

    private Object proceedFastFreezeExit(
            XposedInterface.Chain chain, Object stateMachine) throws Throwable {
        try {
            return chain.proceed();
        } finally {
            registry.clearFastFreezeOwned(stateMachine);
        }
    }

    void onRuntimeStateChanged(ResidencyRuntimeState state) {
        if (state == null) return;
        ResidencyRuntimeState observed;
        synchronized (runtimeStateLock) {
            ResidencyRuntimeState current = latestRuntimeState;
            if (current != null) {
                if (state.generation() < current.generation()) return;
                if (state.generation() == current.generation()
                        && state.status() != current.status()
                        && !advancesSameRuntimeGeneration(
                        current.status(), state.status())) {
                    return;
                }
                if (state.generation() > current.generation()
                        || state.status() != current.status()) {
                    latestRuntimeState = state;
                    latestRuntimeGeneration = state.generation();
                }
            } else {
                latestRuntimeState = state;
                latestRuntimeGeneration = state.generation();
            }
            observed = latestRuntimeState;
        }
        if (!active || observed == null
                || observed.status()
                == ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING) return;
        requestRecoveryPass();
    }

    private static boolean advancesSameRuntimeGeneration(
            ResidencyRuntimeState.Status current,
            ResidencyRuntimeState.Status next) {
        return current == ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING
                && next != ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING;
    }

    private void requestRecoveryPass() {
        recoveryAgain.set(true);
        if (!recoveryEnqueued.compareAndSet(false, true)) return;
        boolean accepted;
        try {
            accepted = recoveryExecutor.execute(this::drainRecoveryRequests);
        } catch (Throwable ignored) {
            accepted = false;
        }
        if (!accepted) {
            recoveryEnqueued.set(false);
            scheduleRecoveryPass(0);
        }
    }

    private void drainRecoveryRequests() {
        try {
            while (recoveryAgain.getAndSet(false)) recoverBlockedCycles();
        } finally {
            recoveryEnqueued.set(false);
            if (recoveryAgain.get()) requestRecoveryPass();
        }
    }

    private void recoverBlockedCycles() {
        if (isIdentityRebuildPending(authoritativeRuntimeState())) return;
        for (HansRefreezeRegistry.BlockedCycle cycle : registry.blockedCycles()) {
            ResidencyRuntimeState runtimeBeforeDecision = authoritativeRuntimeState();
            if (isIdentityRebuildPending(runtimeBeforeDecision)) return;
            RefreezeDecision decision = null;
            HookEvent event = event(cycle.target(), cycle.source(), "config_update");
            try {
                decision = refreezeResolver.decide(cycle.target(), cycle.source());
            } catch (Throwable error) {
                recordFailure(event);
            }
            ResidencyRuntimeState decisionState = authoritativeRuntimeState();
            if (decision != null
                    && decisionState != null
                    && decision.trace().identityGeneration()
                    != decisionState.generation()) {
                continue;
            }
            if (decision != null
                    && decision.effectiveAction()
                    == RefreezeDecision.Action.DISABLE_REFREEZE) {
                continue;
            }
            long recoveryGeneration = decision == null
                    ? runtimeGeneration(decisionState)
                    : decision.trace().identityGeneration();
            if (!registry.prepareRecovery(
                    cycle.stateMachine(), cycle.generation(), recoveryGeneration)) {
                continue;
            }
            ResidencyRuntimeState runtimeAfterQueue = authoritativeRuntimeState();
            if (isIdentityRebuildPending(runtimeAfterQueue)
                    || (runtimeAfterQueue != null
                    && runtimeAfterQueue.generation() != recoveryGeneration)) {
                deferRecovery(
                        cycle.stateMachine(),
                        cycle.generation(),
                        runtimeGeneration(runtimeAfterQueue),
                        !isIdentityRebuildPending(runtimeAfterQueue));
                continue;
            }
            RecoveryToken token = new RecoveryToken(
                    cycle.stateMachine(), cycle.generation(), recoveryGeneration);
            boolean sent;
            try {
                sent = recoveryMessageSender.send(cycle.stateMachine(), token);
            } catch (Throwable ignored) {
                sent = false;
            }
            if (!sent) {
                recoverySendFailed(token, event);
            } else {
                scheduleRecoveryConfirmation(token, event);
            }
        }
    }

    private void scheduleRecoveryPass(int delayIndex) {
        int index = Math.max(0, Math.min(
                delayIndex, RECOVERY_RETRY_DELAYS_MS.length - 1));
        if (!recoveryDispatchRetryScheduled.compareAndSet(false, true)) return;
        boolean accepted;
        try {
            accepted = recoveryExecutor.schedule(() -> {
                recoveryDispatchRetryScheduled.set(false);
                requestRecoveryPass();
            }, RECOVERY_RETRY_DELAYS_MS[index]);
        } catch (Throwable ignored) {
            accepted = false;
        }
        if (!accepted) recoveryDispatchRetryScheduled.set(false);
    }

    private void scheduleRecoveryConfirmation(RecoveryToken token, HookEvent event) {
        boolean accepted;
        try {
            accepted = recoveryConfirmationScheduler.schedule(
                    () -> recoverySendFailed(token, event),
                    RECOVERY_CONFIRMATION_TIMEOUT_MS);
        } catch (Throwable ignored) {
            accepted = false;
        }
        if (!accepted) recoverySendFailed(token, event);
    }

    private void recoveryDeliveryFailed(RecoveryToken token) {
        Object stateMachine = token.stateMachineOrNull();
        HansRefreezeRegistry.ActiveCycle cycle = stateMachine == null
                ? null : registry.activeCycleOrNull(stateMachine);
        HookEvent event = cycle == null
                ? new HookEvent(
                        FeatureIds.HANS_REFREEZE,
                        UNKNOWN_SOURCE_CODE,
                        ResidencyTarget.UNKNOWN_UID,
                        "",
                        "",
                        "recovery_delivery")
                : event(cycle.target(), cycle.source(), "recovery_delivery");
        recoverySendFailed(token, event);
    }

    private void recoverySendFailed(RecoveryToken token, HookEvent event) {
        Object stateMachine = token.stateMachineOrNull();
        int attempt = registry.recoveryFailed(
                stateMachine,
                token.cycleGeneration,
                token.runtimeGeneration);
        if (attempt <= 0) return;
        recordFailure(event);
        if (attempt <= RECOVERY_RETRY_DELAYS_MS.length) {
            scheduleRecoveryRetry(stateMachine, token.cycleGeneration, attempt);
        }
    }

    private void scheduleRecoveryRetry(
            Object stateMachine, long cycleGeneration, int attempt) {
        if (attempt <= 0 || attempt > RECOVERY_RETRY_DELAYS_MS.length
                || !registry.prepareRetry(stateMachine, cycleGeneration, attempt)) {
            return;
        }
        WeakReference<Object> stateMachineReference = new WeakReference<>(stateMachine);
        boolean accepted;
        try {
            accepted = recoveryExecutor.schedule(() -> {
                Object currentStateMachine = stateMachineReference.get();
                if (currentStateMachine != null
                        && registry.retryReady(
                        currentStateMachine, cycleGeneration, attempt)) {
                    requestRecoveryPass();
                }
            }, RECOVERY_RETRY_DELAYS_MS[attempt - 1]);
        } catch (Throwable ignored) {
            accepted = false;
        }
        if (!accepted && registry.retryScheduleFailed(
                stateMachine, cycleGeneration, attempt)) {
            requestRecoveryPass();
        }
    }

    private ResidencyRuntimeState authoritativeRuntimeState() {
        try {
            ResidencyRuntimeState state = runtimeStateSource.state();
            if (state != null) return state;
        } catch (Throwable ignored) {
            // The listener snapshot remains a conservative fallback.
        }
        return latestRuntimeState;
    }

    private long runtimeGeneration(ResidencyRuntimeState state) {
        return state == null ? latestRuntimeGeneration : state.generation();
    }

    private static boolean isIdentityRebuildPending(ResidencyRuntimeState state) {
        return state != null
                && state.status()
                == ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING;
    }

    private static boolean recoveryDecisionCurrent(
            RecoveryToken token,
            RefreezeDecision decision,
            ResidencyRuntimeState runtimeState) {
        if (decision.trace().identityGeneration() != token.runtimeGeneration) return false;
        return runtimeState == null
                || (runtimeState.generation() == token.runtimeGeneration
                && !isIdentityRebuildPending(runtimeState));
    }

    private void deferRecovery(
            Object stateMachine,
            long cycleGeneration,
            long runtimeGeneration,
            boolean requestWhenStable) {
        if (!registry.markBlocked(
                stateMachine, cycleGeneration, runtimeGeneration)) return;
        if (requestWhenStable) requestRecoveryPass();
    }

    private BlockResult blockForDecision(
            Object stateMachine,
            HansRefreezeRegistry.ActiveCycle cycle,
            RefreezeDecision decision) {
        long decisionGeneration = decision.trace().identityGeneration();
        if (!registry.markBlocked(
                stateMachine, cycle.generation(), decisionGeneration)) {
            return BlockResult.FAILED;
        }
        ResidencyRuntimeState current = authoritativeRuntimeState();
        boolean currentDecision = current == null
                ? latestRuntimeGeneration == decisionGeneration
                : current.generation() == decisionGeneration
                && !isIdentityRebuildPending(current);
        if (currentDecision) {
            return BlockResult.BLOCKED;
        }
        registry.releaseBlocked(
                stateMachine, cycle.generation(), decisionGeneration);
        return BlockResult.STALE_PASSTHROUGH;
    }

    private static boolean cycleMatchesTarget(
            HansRefreezeRegistry.ActiveCycle cycle,
            HansObjectAdapter.Target target) {
        return cycle != null
                && target != null
                && cycle.target().uid() == target.uid()
                && cycle.target().packageName().equals(target.packageName());
    }

    private void clearFastFreezeOwnershipForForcedMessage(Object middleState) {
        try {
            HansRefreezeStateAdapter.Resolution resolution =
                    HansRefreezeStateAdapter.resolve(middleState);
            if (resolution.isResolved()) {
                registry.clearFastFreezeOwned(resolution.stateMachineOrNull());
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to the state identity alias resolved earlier in this cycle.
        }
        registry.clearFastFreezeOwnedForState(middleState);
    }

    private HansRefreezeStateAdapter.Resolution resolveState(
            Object state, String phase) {
        try {
            HansRefreezeStateAdapter.Resolution resolution =
                    HansRefreezeStateAdapter.resolve(state);
            if (resolution.isResolved()) return resolution;
        } catch (Throwable ignored) {
            // Fall through to the fail-open diagnostic.
        }
        recordFailure(new HookEvent(
                FeatureIds.HANS_REFREEZE,
                UNKNOWN_SOURCE_CODE,
                ResidencyTarget.UNKNOWN_UID,
                "",
                "",
                phase));
        return null;
    }

    private static HansWakeSourceClassifier.Resolution classify(String reason) {
        try {
            return HansWakeSourceClassifier.classifyTerminal(reason, "");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object applyRefreezeDelay(Object original, RefreezeDecision decision) {
        if (!(original instanceof Long)
                || decision == null
                || decision.effectiveAction()
                != RefreezeDecision.Action.SCHEDULE_REFREEZE) {
            return original;
        }
        long delay = decision.delayMs();
        return HansTimingHook.isValidDelay(delay) ? Long.valueOf(delay) : original;
    }

    private static Object applyTimingDelay(
            Object original, FreezeTimingDecision decision) {
        if (!(original instanceof Long)
                || decision == null
                || decision.effectiveAction()
                != FreezeTimingDecision.Action.APPLY_CUSTOM_TIMING) {
            return original;
        }
        long delay = decision.delayMs();
        return HansTimingHook.isValidDelay(delay) ? Long.valueOf(delay) : original;
    }

    private static FreezeTimingDecision decideTiming(ResidencyTarget target) {
        return HookResidencyRuntime.decideFreezeTiming(new FreezeTimingContext(
                target,
                FreezeTimingContext.Transition.M_TO_F,
                FeatureIds.HANS_REFREEZE,
                HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static RefreezeDecision decideRefreeze(
            ResidencyTarget target, WakeSource source) {
        return HookResidencyRuntime.decideRefreeze(new RefreezeContext(
                target,
                source,
                FeatureIds.HANS_REFREEZE,
                HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static HookEvent event(
            HansObjectAdapter.Target target, WakeSource source, String reason) {
        ResidencyTarget residencyTarget = target == null
                ? null
                : new ResidencyTarget(
                        target.packageName(),
                        target.uid(),
                        ResidencyTarget.UNKNOWN_PID);
        return event(residencyTarget, source, reason);
    }

    private static HookEvent event(
            ResidencyTarget target, WakeSource source, String reason) {
        return new HookEvent(
                FeatureIds.HANS_REFREEZE,
                sourceCode(source),
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                "",
                reason == null ? "" : reason);
    }

    private static HookEvent timingEvent(ResidencyTarget target) {
        return new HookEvent(
                FeatureIds.HANS_FREEZE_TIMING,
                0,
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                target == null ? "" : target.packageName(),
                "m_to_f_timing");
    }

    static int sourceCode(WakeSource source) {
        if (source == null) return UNKNOWN_SOURCE_CODE;
        switch (source) {
            case PACKET: return 1;
            case ALARM: return 2;
            case ASYNC_BINDER: return 3;
            case SYNC_BINDER: return 4;
            case TRANSACTION_BINDER: return 5;
            case SIGNAL: return 6;
            case ACTIVITY_INPUT: return 7;
            case SERVICE: return 8;
            case BROADCAST: return 9;
            case PROVIDER: return 10;
            case JOB_SYNC: return 11;
            case WAKE_LOCK: return 12;
            case AUDIO_MEDIA: return 13;
            case CONNECTIVITY: return 14;
            case SYSTEM_SCENE: return 15;
            case INTERNAL_OPERATION: return 16;
            case OTHER: return 17;
            default: return UNKNOWN_SOURCE_CODE;
        }
    }

    private void recordDecision(HookEvent event, RefreezeDecision decision) {
        try {
            diagnosticsRecorder.recordDecision(event, decision);
        } catch (Throwable ignored) {
            // Diagnostics cannot change a state-machine decision.
        }
    }

    private void recordFailure(HookEvent event) {
        try {
            diagnosticsRecorder.recordFailOpen(event);
        } catch (Throwable ignored) {
            // Failure diagnostics are also fail-open.
        }
    }

    private void restoreFrame(TransitionFrame previous) {
        if (previous == null) {
            transitionFrame.remove();
        } else {
            transitionFrame.set(previous);
        }
    }

    static String unavailableStatus(HansRefreezeRuntime.CapabilityMatrix matrix) {
        String prefix = "UNSUPPORTED_";
        for (HansRefreezeRuntime.Capability capability
                : HansRefreezeRuntime.Capability.values()) {
            if (matrix.status(capability) == HansRefreezeRuntime.Status.LOOKUP_FAILED) {
                prefix = "FAILED_FAIL_OPEN:";
                break;
            }
        }
        return prefix + matrix.summary();
    }

    private static String partialStatus(HansRefreezeRuntime.Capability capability) {
        return "FAILED_FAIL_OPEN:PARTIAL=" + capability.name();
    }

    private static int installHook(
            String className,
            ClassLoader loader,
            HookUtils.MethodMatcher matcher,
            XposedInterface.Hooker hooker) {
        return HookUtils.hookMatching(className, loader, matcher, hooker);
    }

    interface FreezeTimingDecisionResolver {
        FreezeTimingDecision decide(ResidencyTarget target);
    }

    interface RefreezeDecisionResolver {
        RefreezeDecision decide(ResidencyTarget target, WakeSource source);
    }

    interface RefreezeDiagnosticsRecorder {
        void recordDecision(HookEvent event, RefreezeDecision decision);
        void recordTimingDecision(HookEvent event, FreezeTimingDecision decision);
        void recordFailOpen(HookEvent event);
    }

    interface HookInstaller {
        int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker);
    }

    interface RuntimeStateListenerRegistrar {
        ResidencyRuntimeState register(ResidencyRuntimeController.StateListener listener);
    }

    interface RuntimeStateSource {
        ResidencyRuntimeState state();
    }

    interface RecoveryMessageSender {
        boolean send(Object stateMachine, Object recoveryToken);
    }

    interface RecoveryConfirmationScheduler {
        boolean schedule(Runnable runnable, long delayMs);
    }

    interface RecoveryExecutor {
        boolean execute(Runnable runnable);
        boolean schedule(Runnable runnable, long delayMs);

        static RecoveryExecutor direct() {
            return new RecoveryExecutor() {
                @Override
                public boolean execute(Runnable runnable) {
                    runnable.run();
                    return true;
                }

                @Override
                public boolean schedule(Runnable runnable, long delayMs) {
                    runnable.run();
                    return true;
                }
            };
        }
    }

    private enum BlockResult {
        BLOCKED,
        STALE_PASSTHROUGH,
        FAILED
    }

    static final class RecoveryToken {
        private final WeakReference<Object> stateMachine;
        private final long cycleGeneration;
        private final long runtimeGeneration;

        RecoveryToken(
                Object stateMachine, long cycleGeneration, long runtimeGeneration) {
            this.stateMachine = new WeakReference<>(
                    Objects.requireNonNull(stateMachine, "stateMachine"));
            this.cycleGeneration = cycleGeneration;
            this.runtimeGeneration = runtimeGeneration;
        }

        Object stateMachineOrNull() { return stateMachine.get(); }
        long cycleGeneration() { return cycleGeneration; }
        long runtimeGeneration() { return runtimeGeneration; }
    }

    private static final class DefaultRecoveryExecutor implements RecoveryExecutor {
        private final ScheduledExecutorService executor;

        private DefaultRecoveryExecutor(ScheduledExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        @Override
        public boolean execute(Runnable runnable) {
            return dispatch(runnable, 0L);
        }

        @Override
        public boolean schedule(Runnable runnable, long delayMs) {
            return dispatch(runnable, Math.max(0L, delayMs));
        }

        private boolean dispatch(Runnable runnable, long delayMs) {
            Objects.requireNonNull(runnable, "runnable");
            try {
                if (delayMs == 0L) {
                    executor.execute(runnable);
                } else {
                    executor.schedule(runnable, delayMs, TimeUnit.MILLISECONDS);
                }
                return true;
            } catch (Throwable ignored) {
                return startFallback(runnable, delayMs);
            }
        }

        private static boolean startFallback(Runnable runnable, long delayMs) {
            try {
                Thread thread = new Thread(() -> {
                    if (delayMs > 0L) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    runnable.run();
                }, "BgEnhanceHansRecoveryFallback");
                thread.setDaemon(true);
                thread.start();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static final class DefaultRefreezeDiagnosticsRecorder
            implements RefreezeDiagnosticsRecorder {
        @Override
        public void recordDecision(HookEvent event, RefreezeDecision decision) {
            ResidencyDecisionReporter.recordRefreezeFromLockedPath(event, decision);
        }

        @Override
        public void recordTimingDecision(
                HookEvent event, FreezeTimingDecision decision) {
            ResidencyDecisionReporter.recordFreezeTimingFromLockedPath(event, decision);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            HookDiagnostics.recordEventFromLockedPath(
                    event, com.frostwork.bgenhance.DiagnosticsResult.FAIL_OPEN);
        }
    }

    private static final class TransitionFrame {
        private final int uid;
        private final String packageName;
        private final WakeSource source;
        private final boolean armed;

        private TransitionFrame(
                int uid, String packageName, WakeSource source, boolean armed) {
            this.uid = uid;
            this.packageName = Objects.requireNonNull(packageName, "packageName");
            this.source = Objects.requireNonNull(source, "source");
            this.armed = armed;
        }

        private boolean matches(
                int candidateUid,
                String candidatePackage,
                WakeSource candidateSource) {
            return uid == candidateUid
                    && packageName.equals(candidatePackage)
                    && source == candidateSource;
        }
    }
}
