package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.residency.decision.FreezeTimingContext;
import com.frostwork.bgenhance.residency.decision.FreezeTimingDecision;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Objects;

/** Per-application HANS state-transition timing override. */
public final class HansTimingHook implements FeatureHook {
    static final long MIN_DELAY_MS = 1_000L;
    static final long MAX_DELAY_MS = 86_400_000L;

    private final FreezeTimingContext.Transition transition;

    public HansTimingHook(FreezeTimingContext.Transition transition) {
        this.transition = Objects.requireNonNull(transition, "transition");
    }

    public static HansTimingHook rToM() {
        return new HansTimingHook(FreezeTimingContext.Transition.R_TO_M);
    }

    public static HansTimingHook mToF() {
        return new HansTimingHook(FreezeTimingContext.Transition.M_TO_F);
    }

    @Override
    public String hookTargetId() {
        return transition == FreezeTimingContext.Transition.R_TO_M
                ? HookTargetIds.HANS_R_TO_M_TIMING
                : HookTargetIds.HANS_M_TO_F_TIMING;
    }

    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        HansRuntime.Capability capability = capability();
        HansRuntime.CapabilityMatrix matrix = HansRuntime.inspect(loader);
        HansRuntime.Status capabilityStatus = matrix.status(capability);
        if (capabilityStatus != HansRuntime.Status.AVAILABLE) {
            return capabilityStatus(capabilityStatus);
        }
        Method target = matrix.methodOrNull(capability);
        if (target == null || !HansRuntime.matches(capability, target)) {
            return "FAILED_FAIL_OPEN:CAPABILITY_METHOD_UNAVAILABLE";
        }
        int count = HookUtils.hookMatching(
                target.getDeclaringClass().getName(),
                loader,
                target::equals,
                this::intercept);
        return installationStatus(count);
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        return complete(chain::proceed, () -> decide(chain));
    }

    private FreezeTimingDecision decide(XposedInterface.Chain chain) {
        ResidencyTarget target = target(chain);
        if (target == null) return null;
        FreezeTimingContext context = new FreezeTimingContext(
                target,
                transition,
                "",
                hookTargetId());
        return HookResidencyRuntime.decideFreezeTiming(context);
    }

    HansRuntime.Capability capability() {
        return transition == FreezeTimingContext.Transition.R_TO_M
                ? HansRuntime.Capability.R_TO_M_TIMING
                : HansRuntime.Capability.M_TO_F_TIMING;
    }

    static ResidencyTarget target(int uid, String packageName) {
        if (uid < 0 || packageName == null || packageName.isEmpty()) return null;
        return new ResidencyTarget(packageName, uid, ResidencyTarget.UNKNOWN_PID);
    }

    static ResidencyTarget target(XposedInterface.Chain chain) {
        Object uid = HookUtils.objectArg(chain, 0);
        Object packageName = HookUtils.objectArg(chain, 1);
        if (!(uid instanceof Integer) || !(packageName instanceof String)) return null;
        return target((Integer) uid, (String) packageName);
    }

    static Object complete(
            OriginalCall originalCall,
            DecisionCall decisionCall) throws Throwable {
        Object originalResult = originalCall.proceed();
        if (!(originalResult instanceof Long)) return originalResult;
        FreezeTimingDecision decision;
        try {
            decision = decisionCall.decide();
        } catch (Throwable ignored) {
            return originalResult;
        }
        if (decision == null
                || decision.effectiveAction()
                != FreezeTimingDecision.Action.APPLY_CUSTOM_TIMING) {
            return originalResult;
        }
        long delayMs = decision.delayMs();
        return isValidDelay(delayMs) ? delayMs : originalResult;
    }

    static boolean isValidDelay(long delayMs) {
        return delayMs >= MIN_DELAY_MS && delayMs <= MAX_DELAY_MS;
    }

    static String capabilityStatus(HansRuntime.Status status) {
        if (status == HansRuntime.Status.LOOKUP_FAILED) {
            return "FAILED_FAIL_OPEN:" + status.name();
        }
        return "UNSUPPORTED_" + status.name();
    }

    static String installationStatus(int count) {
        if (count < 0) return "UNSUPPORTED_CLASS";
        if (count == 0) return "FAILED_FAIL_OPEN:HOOK_NOT_INSTALLED";
        if (count != 1) return "FAILED_FAIL_OPEN:UNEXPECTED_HOOK_COUNT=" + count;
        return "INSTALLED:1";
    }

    interface OriginalCall {
        Object proceed() throws Throwable;
    }

    interface DecisionCall {
        FreezeTimingDecision decide() throws Throwable;
    }
}
