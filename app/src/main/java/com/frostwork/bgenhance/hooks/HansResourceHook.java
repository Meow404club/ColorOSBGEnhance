package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansActionAdapter;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.hooks.hans.HansResourceRuntime;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Keeps explicitly selected HANS resources through verified UID-scoped gates. */
public final class HansResourceHook implements FeatureHook {
    private static final String ACTION_CLASS =
            "com.android.server.hans.states.action.Action";
    private static final String MANAGER_CLASS =
            "com.android.server.am.OplusHansManager";
    static final int NETWORK_ACTION_CODE = 100;
    static final int UNKNOWN_ACTION_CODE = -1;
    private static final int SERVICE_ACTION_CODE = 1;
    private static final int BROADCAST_ACTION_CODE = 2;
    private static final int JOB_ACTION_CODE = 3;
    private static final int SENSOR_ACTION_CODE = 4;
    private static final int BINDER_ACTION_CODE = 5;
    private static final int ALARM_ACTION_CODE = 6;
    private static final int WAKE_LOCK_ACTION_CODE = 7;
    private static final int GPS_ACTION_CODE = 8;
    private static final int AUDIO_ACTION_CODE = 9;
    private static final int BLUETOOTH_SCAN_ACTION_CODE = 10;

    private static final List<ActionTarget> ACTION_TARGETS =
            Collections.unmodifiableList(Arrays.asList(
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_SERVICE,
                            ResourceType.SERVICE),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_BROADCAST,
                            ResourceType.BROADCAST),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_JOB,
                            ResourceType.JOB),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_SENSOR,
                            ResourceType.SENSOR),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_BINDER,
                            ResourceType.BINDER),
                    new ActionTarget(
                            HansResourceRuntime.Capability.MANAGE_ALARM,
                            ResourceType.ALARM),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_WAKE_LOCK,
                            ResourceType.WAKE_LOCK),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_GPS,
                            ResourceType.GPS),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_AUDIO,
                            ResourceType.AUDIO),
                    new ActionTarget(
                            HansResourceRuntime.Capability.PROXY_BT_SCAN,
                            ResourceType.BLUETOOTH_SCAN)));

    private final ResourceDecisionResolver decisionResolver;
    private final ResourceDiagnosticsRecorder diagnosticsRecorder;
    private final HookInstaller hookInstaller;
    private final ThreadLocal<WakeLockFrame> wakeLockFrame = new ThreadLocal<>();
    private volatile boolean active;

    public HansResourceHook() {
        this(
                HansResourceHook::decide,
                new DefaultResourceDiagnosticsRecorder(),
                HansResourceHook::installHook,
                false);
    }

    HansResourceHook(
            ResourceDecisionResolver decisionResolver,
            ResourceDiagnosticsRecorder diagnosticsRecorder) {
        this(
                decisionResolver,
                diagnosticsRecorder,
                HansResourceHook::installHook,
                true);
    }

    HansResourceHook(
            ResourceDecisionResolver decisionResolver,
            ResourceDiagnosticsRecorder diagnosticsRecorder,
            boolean active) {
        this(
                decisionResolver,
                diagnosticsRecorder,
                HansResourceHook::installHook,
                active);
    }

    HansResourceHook(
            ResourceDecisionResolver decisionResolver,
            ResourceDiagnosticsRecorder diagnosticsRecorder,
            HookInstaller hookInstaller) {
        this(decisionResolver, diagnosticsRecorder, hookInstaller, false);
    }

    private HansResourceHook(
            ResourceDecisionResolver decisionResolver,
            ResourceDiagnosticsRecorder diagnosticsRecorder,
            HookInstaller hookInstaller,
            boolean active) {
        this.decisionResolver = Objects.requireNonNull(
                decisionResolver, "decisionResolver");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.hookInstaller = Objects.requireNonNull(hookInstaller, "hookInstaller");
        this.active = active;
    }

    @Override public String hookTargetId() {
        return HookTargetIds.HANS_RESOURCE_DISPATCH;
    }

    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        active = false;
        HansResourceRuntime.CapabilityMatrix matrix =
                HansResourceRuntime.inspect(loader);
        if (!matrix.allAvailable()) return unavailableStatus(matrix);

        for (ActionTarget target : ACTION_TARGETS) {
            Method method = validatedMethod(matrix, target.capability);
            if (method == null) return methodFailure(target.capability);
            int count = hookInstaller.hookMatching(
                    ACTION_CLASS,
                    loader,
                    method::equals,
                    chain -> interceptAction(chain, target.resourceType));
            if (count != 1) return partialFailure(target.capability, count);
        }

        Method wakeLock = validatedMethod(
                matrix, HansResourceRuntime.Capability.TRY_PROXY_WAKE_LOCK);
        if (wakeLock == null) {
            return methodFailure(HansResourceRuntime.Capability.TRY_PROXY_WAKE_LOCK);
        }
        int wakeLockCount = hookInstaller.hookMatching(
                MANAGER_CLASS, loader, wakeLock::equals, this::interceptWakeLockManager);
        if (wakeLockCount != 1) {
            return partialFailure(
                    HansResourceRuntime.Capability.TRY_PROXY_WAKE_LOCK,
                    wakeLockCount);
        }

        Method firewall = validatedMethod(
                matrix, HansResourceRuntime.Capability.FIREWALL);
        if (firewall == null) {
            return methodFailure(HansResourceRuntime.Capability.FIREWALL);
        }
        int firewallCount = hookInstaller.hookMatching(
                MANAGER_CLASS, loader, firewall::equals, this::interceptFirewall);
        if (firewallCount != 1) {
            return partialFailure(
                    HansResourceRuntime.Capability.FIREWALL, firewallCount);
        }

        Method rstate = validatedMethod(
                matrix, HansResourceRuntime.Capability.RSTATE_NETWORK);
        if (rstate == null) {
            return methodFailure(HansResourceRuntime.Capability.RSTATE_NETWORK);
        }
        int rstateCount = hookInstaller.hookMatching(
                MANAGER_CLASS, loader, rstate::equals, this::interceptRstateNetwork);
        if (rstateCount != 1) {
            return partialFailure(
                    HansResourceRuntime.Capability.RSTATE_NETWORK, rstateCount);
        }

        active = true;
        return "INSTALLED:action=10,manager=3";
    }

    Object interceptAction(
            XposedInterface.Chain chain, ResourceType resourceType) throws Throwable {
        if (!active) return chain.proceed();

        HansActionAdapter.Resolution resolution;
        try {
            resolution = HansActionAdapter.resolve(chain.getThisObject());
        } catch (Throwable error) {
            recordFailure(resourceEvent(null, resourceType, "action"));
            return proceedAfterActionFailure(chain, resourceType);
        }
        if (!resolution.isResolved()) {
            recordFailure(resourceEvent(null, resourceType, "action"));
            return proceedAfterActionFailure(chain, resourceType);
        }

        HansObjectAdapter.Target target = resolution.targetOrNull();
        HookEvent event = resourceEvent(target, resourceType, "action");
        RuntimeResult result = evaluate(
                residencyTarget(target),
                ResourceContext.Kind.HANS_RESOURCE,
                resourceType,
                event);
        if (result == RuntimeResult.KEEP) return null;
        if (resourceType != ResourceType.WAKE_LOCK) return chain.proceed();
        Object packageObject = resolution.packageObjectOrNull();
        return proceedWithinWakeLockFrame(
                chain,
                packageObject == null
                        ? WakeLockFrame.bypassAll()
                        : WakeLockFrame.forPackage(packageObject));
    }

    Object interceptWakeLockManager(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();

        Object packageObject = HookUtils.objectArg(chain, 0);
        WakeLockFrame frame = wakeLockFrame.get();
        if (frame != null && frame.matches(packageObject)) {
            return chain.proceed();
        }

        HansObjectAdapter.Resolution resolution = resolvePackage(
                packageObject, ResourceType.WAKE_LOCK, "manager");
        if (resolution == null || !resolution.isResolved()) return chain.proceed();

        HansObjectAdapter.Target target = resolution.targetOrNull();
        RuntimeResult result = evaluate(
                residencyTarget(target),
                ResourceContext.Kind.HANS_RESOURCE,
                ResourceType.WAKE_LOCK,
                resourceEvent(target, ResourceType.WAKE_LOCK, "manager"));
        return result == RuntimeResult.KEEP ? Boolean.TRUE : chain.proceed();
    }

    Object interceptFirewall(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        Object allowValue = HookUtils.objectArg(chain, 1);
        if (!(allowValue instanceof Boolean)) {
            recordFailure(networkEvent(null, "firewall_invalid"));
            return chain.proceed();
        }
        if (((Boolean) allowValue).booleanValue()) return chain.proceed();

        HansObjectAdapter.Resolution resolution = resolvePackage(
                HookUtils.objectArg(chain, 0), null, "firewall_deny");
        if (resolution == null || !resolution.isResolved()) return chain.proceed();

        HansObjectAdapter.Target target = resolution.targetOrNull();
        RuntimeResult result = evaluate(
                residencyTarget(target),
                ResourceContext.Kind.NETWORK,
                null,
                networkEvent(target, "firewall_deny"));
        return result == RuntimeResult.KEEP ? null : chain.proceed();
    }

    Object interceptRstateNetwork(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        Object uidValue = HookUtils.objectArg(chain, 1);
        Object packageValue = HookUtils.objectArg(chain, 2);
        if (!(uidValue instanceof Integer)
                || (packageValue != null && !(packageValue instanceof String))
                || ((Integer) uidValue).intValue() < 0) {
            recordFailure(networkEvent(null, "rstate_invalid"));
            return chain.proceed();
        }

        int uid = ((Integer) uidValue).intValue();
        String packageName = (String) packageValue;
        ResidencyTarget target = new ResidencyTarget(
                packageName, uid, ResidencyTarget.UNKNOWN_PID);
        RuntimeResult result = evaluate(
                target,
                ResourceContext.Kind.NETWORK,
                null,
                networkEvent(uid, packageName, "rstate_socket"));
        return result == RuntimeResult.KEEP ? Boolean.FALSE : chain.proceed();
    }

    private HansObjectAdapter.Resolution resolvePackage(
            Object packageObject, ResourceType resourceType, String source) {
        HansObjectAdapter.Resolution resolution;
        try {
            resolution = HansObjectAdapter.resolve(packageObject);
        } catch (Throwable error) {
            recordFailure(resourceType == null
                    ? networkEvent(null, source)
                    : resourceEvent(null, resourceType, source));
            return null;
        }
        if (!resolution.isResolved()) {
            recordFailure(resourceType == null
                    ? networkEvent(null, source)
                    : resourceEvent(null, resourceType, source));
        }
        return resolution;
    }

    private RuntimeResult evaluate(
            ResidencyTarget target,
            ResourceContext.Kind kind,
            ResourceType resourceType,
            HookEvent event) {
        ResourceDecision decision;
        try {
            decision = decisionResolver.decide(target, kind, resourceType);
        } catch (Throwable error) {
            recordFailure(event);
            return RuntimeResult.FAIL_OPEN;
        }
        if (decision == null) {
            recordFailure(event);
            return RuntimeResult.FAIL_OPEN;
        }
        recordDecision(event, decision);
        return decision.keepsResource() ? RuntimeResult.KEEP : RuntimeResult.PASS;
    }

    private static ResourceDecision decide(
            ResidencyTarget target,
            ResourceContext.Kind kind,
            ResourceType resourceType) {
        ResourceContext context = kind == ResourceContext.Kind.NETWORK
                ? ResourceContext.network(
                        target,
                        FeatureIds.HANS_RESOURCE,
                        HookTargetIds.HANS_RESOURCE_DISPATCH)
                : ResourceContext.resource(
                        target,
                        resourceType,
                        FeatureIds.HANS_RESOURCE,
                        HookTargetIds.HANS_RESOURCE_DISPATCH);
        return HookResidencyRuntime.decideResource(context);
    }

    private static ResidencyTarget residencyTarget(HansObjectAdapter.Target target) {
        return new ResidencyTarget(
                target.packageName(), target.uid(), ResidencyTarget.UNKNOWN_PID);
    }

    private Object proceedAfterActionFailure(
            XposedInterface.Chain chain, ResourceType resourceType) throws Throwable {
        return resourceType == ResourceType.WAKE_LOCK
                ? proceedWithinWakeLockFrame(chain, WakeLockFrame.bypassAll())
                : chain.proceed();
    }

    private Object proceedWithinWakeLockFrame(
            XposedInterface.Chain chain, WakeLockFrame frame) throws Throwable {
        WakeLockFrame previous = wakeLockFrame.get();
        wakeLockFrame.set(frame);
        try {
            return chain.proceed();
        } finally {
            if (previous == null) {
                wakeLockFrame.remove();
            } else {
                wakeLockFrame.set(previous);
            }
        }
    }

    private void recordDecision(HookEvent event, ResourceDecision decision) {
        try {
            diagnosticsRecorder.recordDecision(event, decision);
        } catch (Throwable ignored) {
            // Diagnostics must never alter a resource decision.
        }
    }

    private void recordFailure(HookEvent event) {
        try {
            diagnosticsRecorder.recordFailOpen(event);
        } catch (Throwable ignored) {
            // Failure diagnostics are also fail-open.
        }
    }

    private static HookEvent resourceEvent(
            HansObjectAdapter.Target target, ResourceType type, String source) {
        return new HookEvent(
                FeatureIds.HANS_RESOURCE,
                actionCodeFor(type),
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                "",
                source + ':' + (type == null
                        ? "unknown" : type.name().toLowerCase(Locale.ROOT)));
    }

    private static HookEvent networkEvent(
            HansObjectAdapter.Target target, String source) {
        return networkEvent(
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                source);
    }

    private static HookEvent networkEvent(
            int uid, String packageName, String source) {
        return new HookEvent(
                FeatureIds.HANS_RESOURCE,
                NETWORK_ACTION_CODE,
                uid,
                packageName == null ? "" : packageName,
                "",
                source);
    }

    static ResourceType resourceTypeFor(
            HansResourceRuntime.Capability capability) {
        for (ActionTarget target : ACTION_TARGETS) {
            if (target.capability == capability) return target.resourceType;
        }
        return null;
    }

    static int actionCodeFor(ResourceType type) {
        if (type == null) return UNKNOWN_ACTION_CODE;
        switch (type) {
            case SERVICE:
                return SERVICE_ACTION_CODE;
            case BROADCAST:
                return BROADCAST_ACTION_CODE;
            case JOB:
                return JOB_ACTION_CODE;
            case SENSOR:
                return SENSOR_ACTION_CODE;
            case BINDER:
                return BINDER_ACTION_CODE;
            case ALARM:
                return ALARM_ACTION_CODE;
            case WAKE_LOCK:
                return WAKE_LOCK_ACTION_CODE;
            case GPS:
                return GPS_ACTION_CODE;
            case AUDIO:
                return AUDIO_ACTION_CODE;
            case BLUETOOTH_SCAN:
                return BLUETOOTH_SCAN_ACTION_CODE;
            default:
                throw new AssertionError(type);
        }
    }

    private static int installHook(
            String className,
            ClassLoader loader,
            HookUtils.MethodMatcher matcher,
            XposedInterface.Hooker hooker) {
        return HookUtils.hookMatching(className, loader, matcher, hooker);
    }

    private static Method validatedMethod(
            HansResourceRuntime.CapabilityMatrix matrix,
            HansResourceRuntime.Capability capability) {
        Method method = matrix.methodOrNull(capability);
        return method != null && HansResourceRuntime.matches(capability, method)
                ? method : null;
    }

    static String unavailableStatus(HansResourceRuntime.CapabilityMatrix matrix) {
        boolean lookupFailed = false;
        for (HansResourceRuntime.Capability capability
                : HansResourceRuntime.Capability.values()) {
            if (matrix.status(capability) == HansResourceRuntime.Status.LOOKUP_FAILED) {
                lookupFailed = true;
                break;
            }
        }
        return (lookupFailed ? "FAILED_FAIL_OPEN:" : "UNSUPPORTED_")
                + matrix.summary();
    }

    private static String methodFailure(HansResourceRuntime.Capability capability) {
        return "FAILED_FAIL_OPEN:METHOD_UNAVAILABLE=" + capability.name();
    }

    private static String partialFailure(
            HansResourceRuntime.Capability capability, int count) {
        return "FAILED_FAIL_OPEN:PARTIAL=" + capability.name()
                + ",count=" + count;
    }

    interface ResourceDecisionResolver {
        ResourceDecision decide(
                ResidencyTarget target,
                ResourceContext.Kind kind,
                ResourceType resourceType);
    }

    interface ResourceDiagnosticsRecorder {
        void recordDecision(HookEvent event, ResourceDecision decision);
        void recordFailOpen(HookEvent event);
    }

    interface HookInstaller {
        int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker);
    }

    private static final class DefaultResourceDiagnosticsRecorder
            implements ResourceDiagnosticsRecorder {
        @Override
        public void recordDecision(HookEvent event, ResourceDecision decision) {
            ResidencyDecisionReporter.recordResourceFromLockedPath(event, decision);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            HookDiagnostics.recordEventFromLockedPath(event, "fail_open");
        }
    }

    private enum RuntimeResult {
        PASS,
        KEEP,
        FAIL_OPEN
    }

    private static final class ActionTarget {
        private final HansResourceRuntime.Capability capability;
        private final ResourceType resourceType;

        private ActionTarget(
                HansResourceRuntime.Capability capability,
                ResourceType resourceType) {
            this.capability = capability;
            this.resourceType = resourceType;
        }
    }

    private static final class WakeLockFrame {
        private final Object packageObject;
        private final boolean bypassAll;

        private WakeLockFrame(Object packageObject, boolean bypassAll) {
            this.packageObject = packageObject;
            this.bypassAll = bypassAll;
        }

        private static WakeLockFrame forPackage(Object packageObject) {
            return new WakeLockFrame(
                    Objects.requireNonNull(packageObject, "packageObject"), false);
        }

        private static WakeLockFrame bypassAll() {
            return new WakeLockFrame(null, true);
        }

        private boolean matches(Object candidate) {
            return bypassAll || packageObject == candidate;
        }
    }
}
