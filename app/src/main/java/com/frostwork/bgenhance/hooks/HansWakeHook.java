package com.frostwork.bgenhance.hooks;

import android.os.SystemClock;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.hooks.hans.HansWakeSourceClassifier;
import com.frostwork.bgenhance.hooks.hans.WakeThrottleState;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Objects;

/** Controls verified HANS kernel and terminal wake paths without blocking Alarm delivery. */
public final class HansWakeHook implements FeatureHook {
    private static final String MANAGER_CLASS = "com.android.server.am.OplusHansManager";
    private static final String CGROUP_CLASS = "com.android.server.hans.freeze.HansCGroup";
    static final int UNKNOWN_SOURCE_CODE = -1;

    private final WakeDecisionResolver decisionResolver;
    private final WakeThrottleState throttleState;
    private final ElapsedRealtimeClock clock;
    private final WakeDiagnosticsRecorder diagnosticsRecorder;
    private final ThreadLocal<WakeFrame> activeFrame = new ThreadLocal<>();
    private volatile boolean active;

    public HansWakeHook() {
        this(
                HansWakeHook::decide,
                new WakeThrottleState(),
                SystemClock::elapsedRealtime,
                new DefaultWakeDiagnosticsRecorder(),
                false);
    }

    HansWakeHook(
            WakeDecisionResolver decisionResolver,
            WakeThrottleState throttleState,
            ElapsedRealtimeClock clock,
            WakeDiagnosticsRecorder diagnosticsRecorder) {
        this(
                decisionResolver,
                throttleState,
                clock,
                diagnosticsRecorder,
                true);
    }

    HansWakeHook(
            WakeDecisionResolver decisionResolver,
            WakeThrottleState throttleState,
            ElapsedRealtimeClock clock,
            WakeDiagnosticsRecorder diagnosticsRecorder,
            boolean active) {
        this.decisionResolver = Objects.requireNonNull(
                decisionResolver, "decisionResolver");
        this.throttleState = Objects.requireNonNull(throttleState, "throttleState");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.active = active;
    }

    @Override public String hookTargetId() { return HookTargetIds.HANS_WAKE_DISPATCH; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        active = false;
        HansRuntime.CapabilityMatrix matrix = HansRuntime.inspect(loader);
        HansRuntime.Status kernelStatus = matrix.status(HansRuntime.Capability.KERNEL_WAKE_7);
        HansRuntime.Status terminalStatus = matrix.status(HansRuntime.Capability.UNFREEZE_GATE);
        if (kernelStatus != HansRuntime.Status.AVAILABLE
                || terminalStatus != HansRuntime.Status.AVAILABLE) {
            return unavailableStatus(kernelStatus, terminalStatus);
        }

        Method kernel = matrix.methodOrNull(HansRuntime.Capability.KERNEL_WAKE_7);
        Method terminal = matrix.methodOrNull(HansRuntime.Capability.UNFREEZE_GATE);
        if (kernel == null
                || terminal == null
                || !HansRuntime.matches(HansRuntime.Capability.KERNEL_WAKE_7, kernel)
                || !HansRuntime.matches(HansRuntime.Capability.UNFREEZE_GATE, terminal)) {
            return "FAILED_FAIL_OPEN:CAPABILITY_METHOD_UNAVAILABLE";
        }

        int kernelCount = HookUtils.hookMatching(
                MANAGER_CLASS, loader, kernel::equals, this::interceptKernel);
        if (kernelCount != 1) {
            return hookCountFailure("kernel", kernelCount);
        }

        int terminalCount = HookUtils.hookMatching(
                CGROUP_CLASS, loader, terminal::equals, this::interceptTerminal);
        if (terminalCount != 1) {
            return "FAILED_FAIL_OPEN:PARTIAL_KERNEL_ONLY:"
                    + hookCountDetail("terminal", terminalCount);
        }
        active = true;
        return "INSTALLED:kernel=1,terminal=1";
    }

    Object interceptKernel(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        int type = HookUtils.intArg(chain, 0, UNKNOWN_SOURCE_CODE);
        int targetUid = HookUtils.intArg(chain, 4, ResidencyTarget.UNKNOWN_UID);
        HansWakeSourceClassifier.Resolution classified;
        try {
            classified = HansWakeSourceClassifier.classifyKernel(type);
        } catch (Throwable error) {
            recordFailure(kernelEvent(targetUid, null, type));
            return targetUid < 0
                    ? chain.proceed()
                    : proceedWithinFrame(
                            chain, WakeFrame.unknownKernel(targetUid));
        }
        if (targetUid < 0) {
            recordFailure(kernelEvent(targetUid, null, type));
            return chain.proceed();
        }
        if (!classified.isResolved()) {
            recordFailure(kernelEvent(targetUid, null, type));
            return proceedWithinFrame(
                    chain, WakeFrame.unknownKernel(targetUid));
        }

        WakeSource source = classified.sourceOrNull();
        HookEvent event = kernelEvent(targetUid, source, type);
        RuntimeResult result = evaluate(ResidencyTarget.forUid(targetUid), source, event);
        if (result.blocksWake()) return null;

        return proceedWithinFrame(
                chain,
                new WakeFrame(
                        targetUid,
                        source,
                        result == RuntimeResult.FAIL_OPEN,
                        false));
    }

    Object interceptTerminal(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        Object reasonValue = HookUtils.objectArg(chain, 1);
        Object componentValue = HookUtils.objectArg(chain, 2);
        String reason = reasonValue instanceof String ? (String) reasonValue : null;
        String component = componentValue instanceof String ? (String) componentValue : null;
        if (HansWakeSourceClassifier.isModuleRecovery(reason, component)) {
            return chain.proceed();
        }

        HansObjectAdapter.Resolution objectResolution;
        try {
            objectResolution = HansObjectAdapter.resolve(HookUtils.objectArg(chain, 0));
        } catch (Throwable error) {
            recordFailure(terminalEvent(null, null, reason));
            return chain.proceed();
        }
        if (!objectResolution.isResolved()) {
            recordFailure(terminalEvent(null, null, reason));
            return chain.proceed();
        }

        HansObjectAdapter.Target target = objectResolution.targetOrNull();
        if (!target.frozen()) return chain.proceed();

        WakeFrame frame = activeFrame.get();
        HansWakeSourceClassifier.Resolution classified;
        try {
            classified = HansWakeSourceClassifier.classifyTerminal(reason, component);
        } catch (Throwable error) {
            if (frame != null && frame.matches(target.uid(), null)) {
                return chain.proceed();
            }
            recordFailure(terminalEvent(target, null, reason));
            return chain.proceed();
        }
        if (!classified.isResolved()) {
            if (frame != null && frame.matches(target.uid(), null)) {
                return chain.proceed();
            }
            recordFailure(terminalEvent(target, null, reason));
            return chain.proceed();
        }

        WakeSource source = classified.sourceOrNull();
        if (source == WakeSource.INTERNAL_OPERATION || source == WakeSource.ALARM) {
            return chain.proceed();
        }

        if (frame != null && frame.matches(target.uid(), source)) {
            return chain.proceed();
        }

        HookEvent event = terminalEvent(target, source, reason);
        RuntimeResult result = evaluate(
                new ResidencyTarget(
                        target.packageName(),
                        target.uid(),
                        ResidencyTarget.UNKNOWN_PID),
                source,
                event);
        return result.blocksWake() ? Boolean.FALSE : chain.proceed();
    }

    private RuntimeResult evaluate(
            ResidencyTarget target, WakeSource source, HookEvent event) {
        WakeDecision decision;
        try {
            decision = decisionResolver.decide(target, source);
        } catch (Throwable error) {
            recordFailure(event);
            return RuntimeResult.FAIL_OPEN;
        }
        if (decision == null) {
            recordFailure(event);
            return RuntimeResult.FAIL_OPEN;
        }

        WakeThrottleState.Outcome throttleOutcome = null;
        RuntimeResult result;
        if (decision.trace().disposition() == DecisionDisposition.FAIL_OPEN) {
            result = RuntimeResult.FAIL_OPEN;
        } else {
            switch (decision.effectiveAction()) {
                case BLOCK_WAKE:
                    result = RuntimeResult.BLOCKED;
                    break;
                case THROTTLE_WAKE:
                    throttleOutcome = evaluateThrottle(target.uid(), source, decision);
                    result = throttleOutcome == WakeThrottleState.Outcome.THROTTLED
                            ? RuntimeResult.BLOCKED
                            : throttleOutcome == WakeThrottleState.Outcome.FAIL_OPEN
                                    ? RuntimeResult.FAIL_OPEN
                                    : RuntimeResult.PASSED;
                    break;
                case ALLOW_SYSTEM:
                case ALLOW_WAKE:
                default:
                    result = RuntimeResult.PASSED;
                    break;
            }
        }
        recordDecision(event, decision, throttleOutcome);
        return result;
    }

    private WakeThrottleState.Outcome evaluateThrottle(
            int uid, WakeSource source, WakeDecision decision) {
        try {
            return throttleState.evaluate(
                    uid,
                    source,
                    decision.trace().configRevision(),
                    decision.trace().policyRevision(),
                    decision.trace().identityGeneration(),
                    decision.cooldownMs(),
                    clock.elapsedRealtime());
        } catch (Throwable error) {
            return WakeThrottleState.Outcome.FAIL_OPEN;
        }
    }

    private void recordDecision(
            HookEvent event,
            WakeDecision decision,
            WakeThrottleState.Outcome throttleOutcome) {
        try {
            diagnosticsRecorder.recordDecision(event, decision, throttleOutcome);
        } catch (Throwable ignored) {
            // Diagnostics must never alter a system-server wake decision.
        }
    }

    private static WakeDecision decide(ResidencyTarget target, WakeSource source) {
        return HookResidencyRuntime.decideWake(new WakeContext(
                target,
                source,
                FeatureIds.HANS_WAKE,
                HookTargetIds.HANS_WAKE_DISPATCH));
    }

    private static void recordWakeDecision(
            HookEvent event,
            WakeDecision decision,
            WakeThrottleState.Outcome throttleOutcome) {
        ResidencyDecisionReporter.recordWakeFromLockedPath(
                event, decision, reporterOutcome(decision, throttleOutcome));
    }

    static ResidencyDecisionReporter.WakeRuntimeOutcome reporterOutcome(
            WakeDecision decision, WakeThrottleState.Outcome throttleOutcome) {
        if (decision == null
                || decision.effectiveAction() != WakeDecision.Action.THROTTLE_WAKE) {
            return ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED;
        }
        if (throttleOutcome == WakeThrottleState.Outcome.ALLOWED) {
            return ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_ALLOWED;
        }
        if (throttleOutcome == WakeThrottleState.Outcome.THROTTLED) {
            return ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_BLOCKED;
        }
        return ResidencyDecisionReporter.WakeRuntimeOutcome.FAIL_OPEN;
    }

    private static HookEvent kernelEvent(int uid, WakeSource source, int rawType) {
        return new HookEvent(
                FeatureIds.HANS_WAKE,
                source == null ? UNKNOWN_SOURCE_CODE : source.ordinal(),
                uid,
                "",
                "",
                "kernel:" + rawType);
    }

    private static HookEvent terminalEvent(
            HansObjectAdapter.Target target, WakeSource source, String reason) {
        return new HookEvent(
                FeatureIds.HANS_WAKE,
                source == null ? UNKNOWN_SOURCE_CODE : source.ordinal(),
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                "",
                reason == null ? "" : reason);
    }

    private void recordFailure(HookEvent event) {
        try {
            diagnosticsRecorder.recordFailOpen(event);
        } catch (Throwable ignored) {
            // Failure diagnostics are also fail-open.
        }
    }

    private Object proceedWithinFrame(
            XposedInterface.Chain chain, WakeFrame frame) throws Throwable {
        WakeFrame previous = activeFrame.get();
        activeFrame.set(frame);
        try {
            return chain.proceed();
        } finally {
            restoreFrame(previous);
        }
    }

    private void restoreFrame(WakeFrame previous) {
        if (previous == null) {
            activeFrame.remove();
        } else {
            activeFrame.set(previous);
        }
    }

    static String unavailableStatus(
            HansRuntime.Status kernelStatus, HansRuntime.Status terminalStatus) {
        String prefix = kernelStatus == HansRuntime.Status.LOOKUP_FAILED
                || terminalStatus == HansRuntime.Status.LOOKUP_FAILED
                ? "FAILED_FAIL_OPEN:" : "UNSUPPORTED_";
        return prefix + "kernel=" + kernelStatus.name()
                + ",terminal=" + terminalStatus.name();
    }

    private static String hookCountFailure(String target, int count) {
        return "FAILED_FAIL_OPEN:" + hookCountDetail(target, count);
    }

    private static String hookCountDetail(String target, int count) {
        return count < 0
                ? target + "_CLASS_LOOKUP"
                : target + "_HOOK_COUNT=" + count;
    }

    interface WakeDecisionResolver {
        WakeDecision decide(ResidencyTarget target, WakeSource source);
    }

    interface ElapsedRealtimeClock {
        long elapsedRealtime();
    }

    interface WakeDiagnosticsRecorder {
        void recordDecision(
                HookEvent event,
                WakeDecision decision,
                WakeThrottleState.Outcome throttleOutcome);

        void recordFailOpen(HookEvent event);
    }

    private static final class DefaultWakeDiagnosticsRecorder
            implements WakeDiagnosticsRecorder {
        @Override
        public void recordDecision(
                HookEvent event,
                WakeDecision decision,
                WakeThrottleState.Outcome throttleOutcome) {
            recordWakeDecision(event, decision, throttleOutcome);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            HookDiagnostics.recordEventFromLockedPath(event, "fail_open");
        }
    }

    private enum RuntimeResult {
        PASSED,
        BLOCKED,
        FAIL_OPEN;

        private boolean blocksWake() { return this == BLOCKED; }
    }

    private static final class WakeFrame {
        private final int uid;
        private final WakeSource source;
        private final boolean allowUidAlias;
        private final boolean bypassAllSources;

        private WakeFrame(
                int uid,
                WakeSource source,
                boolean allowUidAlias,
                boolean bypassAllSources) {
            this.uid = uid;
            this.source = source;
            this.allowUidAlias = allowUidAlias;
            this.bypassAllSources = bypassAllSources;
        }

        private static WakeFrame unknownKernel(int uid) {
            return new WakeFrame(uid, null, true, true);
        }

        private boolean matches(int candidateUid, WakeSource candidateSource) {
            if (bypassAllSources) return true;
            if (source != candidateSource) return false;
            // Firmware may translate an isolated Kernel UID to its owning app UID
            // before the terminal object callback. Never enforce a second decision
            // after the upstream path has already failed open.
            return uid == candidateUid || allowUidAlias;
        }
    }
}
