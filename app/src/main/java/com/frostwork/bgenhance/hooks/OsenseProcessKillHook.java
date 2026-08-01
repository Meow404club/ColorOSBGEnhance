package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.osense.OsenseKillContextAdapter;
import com.frostwork.bgenhance.hooks.osense.OsenseProcessIdentity;
import com.frostwork.bgenhance.hooks.osense.OsenseProcessIdentityAdapter;
import com.frostwork.bgenhance.hooks.osense.OsenseProcessIdentityResolution;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Per-process terminal gate for OSense kills. The callback runs while AMS locks are held. */
public final class OsenseProcessKillHook implements FeatureHook {
    private static final String CLASS_NAME = "com.android.server.am.OplusOsenseKillAction";
    private static final String KILL_CONTEXT_CLASS =
            "com.android.server.am.OplusOsenseKillAction$KillContext";
    private static final String PROCESS_INFO_CLASS = "com.android.server.am.ProcessInfo";
    private boolean accessorsPrepared = true;

    @Override public String hookTargetId() { return HookTargetIds.OSENSE_PROCESS_KILL; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching(
                CLASS_NAME, loader, this::isTarget, this::intercept);
        return status(count, accessorsPrepared);
    }

    private boolean isTarget(Method method) {
        if (!matchesTarget(method)) return false;
        Class<?>[] parameters = method.getParameterTypes();
        boolean contextPrepared = OsenseKillContextAdapter.prepare(parameters[0]);
        boolean identityPrepared = OsenseProcessIdentityAdapter.prepare(parameters[1]);
        if (!contextPrepared || !identityPrepared) accessorsPrepared = false;
        return true;
    }

    static boolean matchesTarget(Method method) {
        if (method == null
                || !"killOneProcessLocked".equals(method.getName())
                || method.getReturnType() != void.class
                || !Modifier.isPrivate(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 4
                && KILL_CONTEXT_CLASS.equals(parameters[0].getName())
                && PROCESS_INFO_CLASS.equals(parameters[1].getName())
                && parameters[2] == String.class
                && parameters[3] == int.class;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object contextValue = HookUtils.objectArg(chain, 0);
        Object processInfoValue = HookUtils.objectArg(chain, 1);
        String reason = HookUtils.stringArg(chain, 2);
        int subReasonCode = HookUtils.intArg(chain, 3, -1);

        OsenseKillContextAdapter.Resolution contextResolution;
        OsenseProcessIdentityResolution identityResolution;
        try {
            contextResolution = OsenseKillContextAdapter.resolve(
                    contextValue, subReasonCode);
            identityResolution = OsenseProcessIdentityAdapter.resolve(processInfoValue);
        } catch (Throwable error) {
            recordFailOpen(null, null, subReasonCode, reason);
            return chain.proceed();
        }

        OsenseKillContextAdapter.Mapping mapping = contextResolution.mappingOrNull();
        OsenseProcessIdentity identity = identityResolution.identityOrNull();
        if (mapping == null || identity == null) {
            recordFailOpen(mapping, identity, subReasonCode, reason);
            return chain.proceed();
        }

        HookEvent event = event(mapping, identity, reason);
        KillDecision decision;
        try {
            KillContext context = new KillContext(
                    new ResidencyTarget(
                            identity.packageName(), identity.uid(), identity.pid()),
                    mapping.path(),
                    mapping.featureId(),
                    HookTargetIds.OSENSE_PROCESS_KILL);
            decision = mapping.hasFeatureGate()
                    ? HookResidencyRuntime.decideKill(
                            context,
                            mapping.featureKey(),
                            ModuleSettings.defaultFor(mapping.featureKey()))
                    : HookResidencyRuntime.decideKill(context);
        } catch (Throwable error) {
            recordFailOpen(mapping, identity, subReasonCode, reason);
            return chain.proceed();
        }

        ResidencyDecisionReporter.recordKillFromLockedPath(event, decision);
        return complete(chain::proceed, decision);
    }

    static Object complete(OriginalCall originalCall, KillDecision decision) throws Throwable {
        if (decision.blocksAction()) return null;
        return originalCall.proceed();
    }

    interface OriginalCall {
        Object proceed() throws Throwable;
    }

    static String status(int count, boolean accessorsPrepared) {
        if (count < 0) return "UNSUPPORTED_CLASS";
        if (count == 0) return "UNSUPPORTED_SIGNATURE";
        if (!accessorsPrepared) return "FAILED_FAIL_OPEN:ACCESSOR_PREPARE:INSTALLED=" + count;
        return "INSTALLED:" + count;
    }

    private static HookEvent event(
            OsenseKillContextAdapter.Mapping mapping,
            OsenseProcessIdentity identity,
            String reason) {
        String scene = mapping.scene().isEmpty() ? reason : mapping.scene();
        return new HookEvent(
                mapping.featureId(),
                mapping.code(),
                identity.uid(),
                identity.packageName(),
                identity.processName(),
                scene);
    }

    private static void recordFailOpen(
            OsenseKillContextAdapter.Mapping mapping,
            OsenseProcessIdentity identity,
            int subReasonCode,
            String reason) {
        try {
            String featureId = mapping == null
                    ? FeatureIds.OSENSE_PER_PROCESS
                    : mapping.featureId();
            int code = mapping == null ? subReasonCode : mapping.code();
            HookDiagnostics.recordEventFromLockedPath(new HookEvent(
                    featureId,
                    code,
                    identity == null ? -1 : identity.uid(),
                    identity == null ? "" : identity.packageName(),
                    identity == null ? "" : identity.processName(),
                    mapping == null || mapping.scene().isEmpty()
                            ? reason
                            : mapping.scene()), "fail_open");
        } catch (Throwable ignored) {
            // Diagnostics must never delay or change an AMS-locked kill callback.
        }
    }
}
