package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansFastFreezeRuntime;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Objects;

/** Filters cgroup-v2 FAST candidates before OEM lists, state, and traces are written. */
public final class HansFastFreezeHook implements FeatureHook {
    static final String SCENE_MARKER = "enterFF:cgroup_v2";

    private final FreezeDecisionResolver decisionResolver;
    private final DiagnosticsRecorder diagnosticsRecorder;
    private final HookInstaller hookInstaller;
    private final MethodDeoptimizer methodDeoptimizer;
    private final ThreadLocal<FastFreezeFrame> activeFrame = new ThreadLocal<>();
    private volatile boolean active;

    public HansFastFreezeHook() {
        this(
                HansFastFreezeHook::decide,
                new DefaultDiagnosticsRecorder(),
                HansFastFreezeHook::installHook,
                HookUtils::deoptimize,
                false);
    }

    HansFastFreezeHook(
            FreezeDecisionResolver decisionResolver,
            DiagnosticsRecorder diagnosticsRecorder,
            boolean active) {
        this(
                decisionResolver,
                diagnosticsRecorder,
                HansFastFreezeHook::installHook,
                method -> true,
                active);
    }

    HansFastFreezeHook(
            FreezeDecisionResolver decisionResolver,
            DiagnosticsRecorder diagnosticsRecorder,
            HookInstaller hookInstaller) {
        this(decisionResolver, diagnosticsRecorder, hookInstaller, method -> true, false);
    }

    HansFastFreezeHook(
            FreezeDecisionResolver decisionResolver,
            DiagnosticsRecorder diagnosticsRecorder,
            HookInstaller hookInstaller,
            MethodDeoptimizer methodDeoptimizer) {
        this(
                decisionResolver,
                diagnosticsRecorder,
                hookInstaller,
                methodDeoptimizer,
                false);
    }

    private HansFastFreezeHook(
            FreezeDecisionResolver decisionResolver,
            DiagnosticsRecorder diagnosticsRecorder,
            HookInstaller hookInstaller,
            MethodDeoptimizer methodDeoptimizer,
            boolean active) {
        this.decisionResolver = Objects.requireNonNull(
                decisionResolver, "decisionResolver");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.hookInstaller = Objects.requireNonNull(hookInstaller, "hookInstaller");
        this.methodDeoptimizer = Objects.requireNonNull(
                methodDeoptimizer, "methodDeoptimizer");
        this.active = active;
    }

    @Override public String hookTargetId() {
        return HookTargetIds.HANS_FAST_FREEZE_DISPATCH;
    }

    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        active = false;
        HansFastFreezeRuntime.CapabilityMatrix matrix =
                HansFastFreezeRuntime.inspect(loader);
        if (!matrix.allAvailable()) return unavailableStatus(matrix);

        Method entry = validatedMethod(
                matrix, HansFastFreezeRuntime.Capability.ENTER_FAST_FREEZER);
        Method bridge = validatedMethod(
                matrix,
                HansFastFreezeRuntime.Capability.ENTER_FAST_FREEZER_BRIDGE);
        Method candidates = validatedMethod(
                matrix,
                HansFastFreezeRuntime.Capability.FILTER_FAST_FREEZER_CANDIDATES);
        Method terminal = validatedMethod(
                matrix, HansFastFreezeRuntime.Capability.FAST_FREEZE_V2_TERMINAL);
        if (entry == null || bridge == null || candidates == null || terminal == null) {
            return "FAILED_FAIL_OPEN:CAPABILITY_METHOD_UNAVAILABLE";
        }

        try {
            if (!methodDeoptimizer.deoptimize(bridge)) {
                return "FAILED_FAIL_OPEN:DEOPTIMIZE_ENTRY_CALLER";
            }
            if (!methodDeoptimizer.deoptimize(entry)) {
                return "FAILED_FAIL_OPEN:DEOPTIMIZE_CANDIDATE_CALLER";
            }
        } catch (Throwable error) {
            return "FAILED_FAIL_OPEN:DEOPTIMIZATION";
        }

        int entryCount = hookInstaller.hookMatching(
                entry.getDeclaringClass().getName(),
                loader,
                entry::equals,
                this::interceptEntry);
        int candidateCount = hookInstaller.hookMatching(
                candidates.getDeclaringClass().getName(),
                loader,
                candidates::equals,
                this::interceptCandidates);
        if (entryCount != 1 || candidateCount != 1) {
            return "FAILED_FAIL_OPEN:ENTRY=" + entryCount
                    + ",CANDIDATES=" + candidateCount;
        }

        active = true;
        return "INSTALLED:entry=1,candidates=1,terminal=verified,deopt=2";
    }

    Object interceptEntry(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        Object sceneManager = chain.getThisObject();
        if (sceneManager == null) return chain.proceed();
        FastFreezeFrame previous = activeFrame.get();
        activeFrame.set(new FastFreezeFrame(sceneManager));
        try {
            return chain.proceed();
        } finally {
            if (previous == null) {
                activeFrame.remove();
            } else {
                activeFrame.set(previous);
            }
        }
    }

    Object interceptCandidates(XposedInterface.Chain chain) throws Throwable {
        Object original = chain.proceed();
        if (!active || !(original instanceof ArrayList<?>)) return original;
        FastFreezeFrame frame = activeFrame.get();
        if (frame == null || frame.sceneManager != chain.getThisObject()) return original;

        try {
            ArrayList<?> candidates = (ArrayList<?>) original;
            ArrayList<Object> filtered = new ArrayList<>(candidates.size());
            boolean changed = false;
            for (Object candidate : candidates) {
                if (keepCandidate(candidate)) {
                    filtered.add(candidate);
                } else {
                    changed = true;
                }
            }
            return changed ? filtered : original;
        } catch (Throwable error) {
            recordFailure(failureEvent(null));
            return original;
        }
    }

    private boolean keepCandidate(Object candidate) {
        HansObjectAdapter.Resolution resolution;
        try {
            resolution = HansObjectAdapter.resolve(candidate);
        } catch (Throwable error) {
            recordFailure(failureEvent(null));
            return true;
        }
        if (!resolution.isResolved()) {
            recordFailure(failureEvent(null));
            return true;
        }

        HansObjectAdapter.Target target = resolution.targetOrNull();
        HookEvent event = event(target);
        FreezeDecision decision;
        try {
            decision = decisionResolver.decide(new ResidencyTarget(
                    target.packageName(),
                    target.uid(),
                    ResidencyTarget.UNKNOWN_PID));
        } catch (Throwable error) {
            recordFailure(event);
            return true;
        }
        if (decision == null) {
            recordFailure(event);
            return true;
        }

        if (HansFreezeHook.shouldUseLegacyGlobal(decision)) {
            Decision.LockedResult legacy;
            try {
                legacy = Decision.evaluateFromLockedPath(ModuleSettings.KEY_HANS);
            } catch (Throwable error) {
                recordFailure(event);
                return true;
            }
            recordLegacy(event, legacy);
            return !legacy.blocked();
        }

        recordDecision(event, decision);
        return decision.effectiveAction() != FreezeDecision.Action.BLOCK_FREEZE;
    }

    private static FreezeDecision decide(ResidencyTarget target) {
        return HookResidencyRuntime.decideFreeze(new FreezeContext(
                target,
                FreezeSource.FAST,
                FeatureIds.HANS,
                HookTargetIds.HANS_FAST_FREEZE_DISPATCH));
    }

    private static HookEvent event(HansObjectAdapter.Target target) {
        return new HookEvent(
                FeatureIds.HANS,
                FreezeSource.FAST.ordinal(),
                target.uid(),
                target.packageName(),
                "",
                SCENE_MARKER);
    }

    private static HookEvent failureEvent(HansObjectAdapter.Target target) {
        return new HookEvent(
                FeatureIds.HANS,
                HansFreezeHook.UNKNOWN_SOURCE_CODE,
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                "",
                SCENE_MARKER);
    }

    private void recordDecision(HookEvent event, FreezeDecision decision) {
        try {
            diagnosticsRecorder.recordDecision(event, decision);
        } catch (Throwable ignored) {
            // Diagnostics must never affect FAST candidate selection.
        }
    }

    private void recordLegacy(HookEvent event, Decision.LockedResult result) {
        try {
            diagnosticsRecorder.recordLegacy(event, result);
        } catch (Throwable ignored) {
            // Diagnostics must never affect FAST candidate selection.
        }
    }

    private void recordFailure(HookEvent event) {
        try {
            diagnosticsRecorder.recordFailure(event);
        } catch (Throwable ignored) {
            // Diagnostics must never affect FAST candidate selection.
        }
    }

    private static Method validatedMethod(
            HansFastFreezeRuntime.CapabilityMatrix matrix,
            HansFastFreezeRuntime.Capability capability) {
        Method method = matrix.methodOrNull(capability);
        return HansFastFreezeRuntime.matches(capability, method) ? method : null;
    }

    private static String unavailableStatus(
            HansFastFreezeRuntime.CapabilityMatrix matrix) {
        for (HansFastFreezeRuntime.Capability capability
                : HansFastFreezeRuntime.Capability.values()) {
            if (matrix.status(capability) == HansFastFreezeRuntime.Status.LOOKUP_FAILED) {
                return "FAILED_FAIL_OPEN:" + matrix.summary();
            }
        }
        return "UNSUPPORTED_" + matrix.summary();
    }

    private static int installHook(
            String className,
            ClassLoader loader,
            HookUtils.MethodMatcher matcher,
            XposedInterface.Hooker hooker) {
        return HookUtils.hookMatching(className, loader, matcher, hooker);
    }

    interface FreezeDecisionResolver {
        FreezeDecision decide(ResidencyTarget target);
    }

    interface DiagnosticsRecorder {
        void recordDecision(HookEvent event, FreezeDecision decision);
        void recordLegacy(HookEvent event, Decision.LockedResult result);
        void recordFailure(HookEvent event);
    }

    interface HookInstaller {
        int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker);
    }

    interface MethodDeoptimizer {
        boolean deoptimize(Method method);
    }

    private static final class DefaultDiagnosticsRecorder implements DiagnosticsRecorder {
        @Override
        public void recordDecision(HookEvent event, FreezeDecision decision) {
            ResidencyDecisionReporter.recordFreezeFromLockedPath(event, decision);
        }

        @Override
        public void recordLegacy(HookEvent event, Decision.LockedResult result) {
            HookDiagnostics.recordEventFromLockedPath(
                    event,
                    result == null ? DiagnosticsResult.FAIL_OPEN : result.wireValue());
        }

        @Override
        public void recordFailure(HookEvent event) {
            HookDiagnostics.recordEventFromLockedPath(event, DiagnosticsResult.FAIL_OPEN);
        }
    }

    private static final class FastFreezeFrame {
        private final Object sceneManager;

        private FastFreezeFrame(Object sceneManager) {
            this.sceneManager = sceneManager;
        }
    }
}
