package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionContext;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionDecision;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Objects;

/** Verified HANS exemption handling with per-target safety gates. */
public final class HansExemptionHook implements FeatureHook {
    public static final String FREEZE_GATE_INCOMPLETE =
            "UNSUPPORTED_FREEZE_GATE_INCOMPLETE";

    private final FreezeExemptionContext.Kind kind;

    public HansExemptionHook(FreezeExemptionContext.Kind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public static HansExemptionHook core() {
        return new HansExemptionHook(FreezeExemptionContext.Kind.CORE);
    }

    public static HansExemptionHook lcdOn() {
        return new HansExemptionHook(FreezeExemptionContext.Kind.LCD_ON);
    }

    @Override
    public String hookTargetId() {
        return kind == FreezeExemptionContext.Kind.CORE
                ? HookTargetIds.HANS_CORE_EXEMPTION
                : HookTargetIds.HANS_LCD_ON_EXEMPTION;
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
        return installVerifiedTarget(() -> HookUtils.hookMatching(
                target.getDeclaringClass().getName(),
                loader,
                target::equals,
                this::intercept));
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        ResidencyTarget target = target(chain);
        FreezeExemptionDecision[] evaluated = { null };
        Object result;
        try {
            result = complete(chain::proceed, () -> {
                evaluated[0] = decide(target);
                return evaluated[0];
            });
        } catch (Throwable error) {
            recordRawHit(target);
            throw error;
        }
        if (!recordDecision(target, evaluated[0])) recordRawHit(target);
        return result;
    }

    private FreezeExemptionDecision decide(ResidencyTarget target) {
        if (target == null) return null;
        FreezeExemptionContext context = new FreezeExemptionContext(
                target,
                kind,
                "",
                hookTargetId());
        return HookResidencyRuntime.decideFreezeExemption(context);
    }

    private void recordRawHit(ResidencyTarget target) {
        try {
            boolean parsed = target != null;
            HookDiagnostics.recordRawHookHit(
                    hookTargetId(),
                    parsed ? target.uid() : ResidencyTarget.UNKNOWN_UID,
                    parsed ? target.packageName() : "",
                    parsed,
                    parsed,
                    parsed ? "" : "hook_arguments_unparsed");
        } catch (Throwable ignored) {
            // Diagnostics must not change a HANS exemption query.
        }
    }

    private boolean recordDecision(ResidencyTarget target, FreezeExemptionDecision decision) {
        if (!producesStandardEvent(kind, target, decision)) return false;
        try {
            ResidencyDecisionReporter.recordFreezeExemptionFromLockedPath(
                    new HookEvent(
                            FeatureIds.HANS_FREEZE_EXEMPTION,
                            0,
                            target.uid(),
                            target.packageName(),
                            target.packageName(),
                            "lcd_on_exemption"),
                    decision);
        } catch (Throwable ignored) {
            // Diagnostics must not change a HANS exemption query.
        }
        return true;
    }

    static boolean producesStandardEvent(
            FreezeExemptionContext.Kind kind,
            ResidencyTarget target,
            FreezeExemptionDecision decision) {
        return kind == FreezeExemptionContext.Kind.LCD_ON
                && target != null
                && decision != null;
    }

    HansRuntime.Capability capability() {
        return kind == FreezeExemptionContext.Kind.CORE
                ? HansRuntime.Capability.CORE_EXEMPTION
                : HansRuntime.Capability.LCD_ON_EXEMPTION;
    }

    boolean enforcesBehavior() {
        return kind == FreezeExemptionContext.Kind.LCD_ON;
    }

    String installVerifiedTarget(HookInstallCall installCall) {
        Objects.requireNonNull(installCall, "installCall");
        if (!enforcesBehavior()) return FREEZE_GATE_INCOMPLETE;
        return installationStatus(installCall.install());
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
        if (!(originalResult instanceof Boolean)
                || ((Boolean) originalResult).booleanValue()) {
            return originalResult;
        }
        FreezeExemptionDecision decision;
        try {
            decision = decisionCall.decide();
        } catch (Throwable ignored) {
            return originalResult;
        }
        if (decision == null
                || decision.effectiveAction()
                != FreezeExemptionDecision.Action.GRANT_EXEMPTION) {
            return originalResult;
        }
        return Boolean.TRUE;
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
        if (count != 1) {
            return "FAILED_FAIL_OPEN:UNEXPECTED_HOOK_COUNT=" + count;
        }
        return "INSTALLED:1";
    }

    interface OriginalCall {
        Object proceed() throws Throwable;
    }

    interface DecisionCall {
        FreezeExemptionDecision decide() throws Throwable;
    }

    interface HookInstallCall {
        int install();
    }
}
