package com.frostwork.bgenhance.hooks.athena;

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
import com.frostwork.bgenhance.hooks.athena.AthenaIdentityAdapter.ActionCodeResolution;
import com.frostwork.bgenhance.hooks.athena.AthenaIdentityAdapter.Identity;
import com.frostwork.bgenhance.hooks.athena.AthenaIdentityAdapter.IdentityResolution;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Atomic per-application gate for Athena's six verified terminal paths. */
public final class AthenaTerminalGate {
    private static final int PERIODIC_APP_CODE = 321;
    private static final int PERIODIC_SYSTEM_CODE = 322;
    private static final int TOURNAMENT_CODE = 78;

    private static final ActionProfile PERIODIC_APP = new ActionProfile(
            KillContext.Path.ATHENA_PERIODIC,
            FeatureIds.ATHENA_PERIODIC,
            HookTargetIds.ATHENA_PERIODIC,
            ModuleSettings.KEY_PERIODIC,
            PERIODIC_APP_CODE,
            "periodic_app");
    private static final ActionProfile PERIODIC_SYSTEM = new ActionProfile(
            KillContext.Path.ATHENA_PERIODIC,
            FeatureIds.ATHENA_PERIODIC,
            HookTargetIds.ATHENA_PERIODIC,
            ModuleSettings.KEY_PERIODIC,
            PERIODIC_SYSTEM_CODE,
            "periodic_system");
    private static final ActionProfile TOURNAMENT = new ActionProfile(
            KillContext.Path.TOURNAMENT,
            FeatureIds.TOURNAMENT,
            HookTargetIds.TOURNAMENT,
            ModuleSettings.KEY_TOURNAMENT,
            TOURNAMENT_CODE,
            "tournament");

    private final DecisionResolver decisionResolver;
    private final DiagnosticsRecorder diagnosticsRecorder;
    private final HookInstaller hookInstaller;
    private final AdapterPreparer adapterPreparer;
    private final MethodDeoptimizer methodDeoptimizer;
    private volatile Activation activation;
    private boolean installAttempted;
    private String installationStatus;

    public AthenaTerminalGate() {
        this(
                AthenaTerminalGate::decide,
                new DefaultDiagnosticsRecorder(),
                AthenaTerminalGate::installHook,
                AthenaIdentityAdapter::prepare,
                HookUtils::deoptimize);
    }

    AthenaTerminalGate(
            DecisionResolver decisionResolver,
            DiagnosticsRecorder diagnosticsRecorder,
            HookInstaller hookInstaller,
            AdapterPreparer adapterPreparer,
            MethodDeoptimizer methodDeoptimizer) {
        this.decisionResolver = Objects.requireNonNull(
                decisionResolver, "decisionResolver");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.hookInstaller = Objects.requireNonNull(hookInstaller, "hookInstaller");
        this.adapterPreparer = Objects.requireNonNull(adapterPreparer, "adapterPreparer");
        this.methodDeoptimizer = Objects.requireNonNull(
                methodDeoptimizer, "methodDeoptimizer");
    }

    AthenaTerminalGate(
            DecisionResolver decisionResolver,
            DiagnosticsRecorder diagnosticsRecorder,
            AthenaIdentityAdapter.Prepared adapter,
            boolean active) {
        this(
                decisionResolver,
                diagnosticsRecorder,
                AthenaTerminalGate::installHook,
                AthenaIdentityAdapter::prepare,
                method -> true);
        this.activation = new Activation(
                Objects.requireNonNull(adapter, "adapter"), active);
    }

    public static List<FeatureHook> featureHooks() {
        return new AthenaTerminalGate().targetHooks();
    }

    List<FeatureHook> targetHooks() {
        return Collections.unmodifiableList(Arrays.asList(
                new TargetHook(this, HookTargetIds.ATHENA_PERIODIC,
                        ModuleSettings.KEY_PERIODIC),
                new TargetHook(this, HookTargetIds.FAST_CLEAR_DISPATCH, ""),
                new TargetHook(this, HookTargetIds.TOURNAMENT,
                        ModuleSettings.KEY_TOURNAMENT),
                new TargetHook(this, HookTargetIds.MEMORY_GUARD_DISPATCH, "")));
    }

    public synchronized String install(ClassLoader loader) {
        if (installAttempted) return installationStatus;
        installAttempted = true;
        installationStatus = "FAILED_FAIL_OPEN:INSTALL_EXCEPTION";
        try {
            installationStatus = installFresh(loader);
        } catch (Throwable error) {
            installationStatus = "FAILED_FAIL_OPEN:INSTALL_EXCEPTION="
                    + error.getClass().getSimpleName();
        }
        return installationStatus;
    }

    private String installFresh(ClassLoader loader) {
        activation = null;

        AthenaTerminalRuntime.CapabilityMatrix matrix =
                AthenaTerminalRuntime.inspect(loader);
        if (!matrix.allAvailable()) return unavailableStatus(matrix);

        AthenaIdentityAdapter.Preparation preparation = adapterPreparer.prepare(matrix);
        if (preparation == null || !preparation.isReady()) {
            return "FAILED_FAIL_OPEN:ACCESSOR_PREPARE="
                    + (preparation == null ? "NULL" : preparation.status().name());
        }

        ArrayList<Method> terminalMethods = new ArrayList<>();
        for (AthenaTerminalRuntime.Capability capability
                : AthenaTerminalRuntime.Capability.values()) {
            Method method = validatedMethod(matrix, capability);
            if (method == null) {
                return "FAILED_FAIL_OPEN:METHOD_UNAVAILABLE=" + capability.name();
            }
            terminalMethods.add(method);
        }

        ArrayList<Method> callerMethods = new ArrayList<>();
        for (AthenaTerminalRuntime.DirectCaller caller
                : AthenaTerminalRuntime.DirectCaller.values()) {
            Method method = validatedCaller(matrix, caller);
            if (method == null) {
                return "FAILED_FAIL_OPEN:CALLER_UNAVAILABLE=" + caller.name();
            }
            callerMethods.add(method);
        }

        Activation candidate = new Activation(preparation.preparedOrNull(), false);
        activation = candidate;
        try {
            AthenaTerminalRuntime.Capability[] capabilities =
                    AthenaTerminalRuntime.Capability.values();
            for (int index = 0; index < capabilities.length; index++) {
                AthenaTerminalRuntime.Capability capability = capabilities[index];
                int count = hookInstaller.hookMatching(
                        capability.owner(),
                        loader,
                        terminalMethods.get(index)::equals,
                        hookerFor(capability, candidate));
                if (count != 1) {
                    return "FAILED_FAIL_OPEN:PARTIAL=" + capability.name()
                            + ",count=" + count;
                }
            }
        } catch (LinkageError | RuntimeException error) {
            return "FAILED_FAIL_OPEN:INSTALL_EXCEPTION="
                    + error.getClass().getSimpleName();
        }

        AthenaTerminalRuntime.DirectCaller[] callers =
                AthenaTerminalRuntime.DirectCaller.values();
        for (int index = 0; index < callers.length; index++) {
            AthenaTerminalRuntime.DirectCaller caller = callers[index];
            try {
                if (!methodDeoptimizer.deoptimize(callerMethods.get(index))) {
                    return "FAILED_FAIL_OPEN:DEOPTIMIZE=" + caller.name();
                }
            } catch (Throwable error) {
                return "FAILED_FAIL_OPEN:DEOPTIMIZATION=" + caller.name();
            }
        }
        candidate.active = true;
        return "INSTALLED:terminals=6,deopt=11";
    }

    Object interceptPeriodicSingle(XposedInterface.Chain chain) throws Throwable {
        return interceptPeriodicSingle(chain, activation);
    }

    private Object interceptPeriodicSingle(
            XposedInterface.Chain chain, Activation current) throws Throwable {
        if (!isActive(current)) return chain.proceed();
        IdentityResolution identity;
        try {
            identity = current.adapter.resolveProcess(HookUtils.objectArg(chain, 2));
        } catch (Throwable error) {
            recordFailOpen(event(PERIODIC_APP, null));
            return chain.proceed();
        }
        return shouldBlock(identity, PERIODIC_APP)
                ? null : chain.proceed();
    }

    Object interceptPeriodicList(XposedInterface.Chain chain) throws Throwable {
        return interceptPeriodicList(chain, activation);
    }

    private Object interceptPeriodicList(
            XposedInterface.Chain chain, Activation current) throws Throwable {
        if (!isActive(current)) return chain.proceed();
        List<?> source = listArg(chain, 0);
        if (source == null) {
            recordFailOpen(event(PERIODIC_SYSTEM, null));
            return chain.proceed();
        }
        ArrayList<Object> filtered = defensiveCopy(source, PERIODIC_SYSTEM);
        if (filtered == null) return chain.proceed();
        Object[] nextArgs = replacementArgs(chain, 0, filtered, PERIODIC_SYSTEM);
        if (nextArgs == null) return chain.proceed();
        filtered.removeIf(candidate -> shouldFilterCandidate(
                current.adapter.resolveProcess(candidate), PERIODIC_SYSTEM));
        return chain.proceed(nextArgs);
    }

    Object interceptTournament(XposedInterface.Chain chain) throws Throwable {
        return interceptTournament(chain, activation);
    }

    private Object interceptTournament(
            XposedInterface.Chain chain, Activation current) throws Throwable {
        if (!isActive(current)) return chain.proceed();
        int stopType;
        int code;
        try {
            stopType = HookUtils.intArg(chain, 3, -1);
            code = HookUtils.intArg(chain, 4, -1);
        } catch (Throwable error) {
            recordFailOpen(event(TOURNAMENT, null));
            return chain.proceed();
        }
        if ((stopType != 1 && stopType != 2)
                || code != TOURNAMENT_CODE) {
            return chain.proceed();
        }
        IdentityResolution identity;
        try {
            identity = current.adapter.resolveProcess(HookUtils.objectArg(chain, 0));
        } catch (Throwable error) {
            recordFailOpen(event(TOURNAMENT, null));
            return chain.proceed();
        }
        return shouldBlock(identity, TOURNAMENT) ? null : chain.proceed();
    }

    Object interceptMemorySingle(XposedInterface.Chain chain) throws Throwable {
        return interceptMemorySingle(chain, activation);
    }

    private Object interceptMemorySingle(
            XposedInterface.Chain chain, Activation current) throws Throwable {
        if (!isActive(current)) return chain.proceed();
        ActionProfile profile = actionProfile(current, chain);
        if (profile == null) return chain.proceed();
        IdentityResolution identity;
        try {
            identity = current.adapter.resolveCandidate(HookUtils.objectArg(chain, 0));
        } catch (Throwable error) {
            recordFailOpen(event(profile, null));
            return chain.proceed();
        }
        return shouldBlock(identity, profile) ? null : chain.proceed();
    }

    Object interceptMemoryGroup(XposedInterface.Chain chain) throws Throwable {
        return interceptMemoryGroup(chain, activation);
    }

    private Object interceptMemoryGroup(
            XposedInterface.Chain chain, Activation current) throws Throwable {
        if (!isActive(current)) return chain.proceed();
        ActionProfile profile = actionProfile(current, chain);
        if (profile == null) return chain.proceed();
        IdentityResolution identity;
        try {
            identity = current.adapter.resolveGroup(HookUtils.objectArg(chain, 0));
        } catch (Throwable error) {
            recordFailOpen(event(profile, null));
            return chain.proceed();
        }
        return shouldBlock(identity, profile) ? null : chain.proceed();
    }

    Object interceptMemoryPrerelease(XposedInterface.Chain chain) throws Throwable {
        return interceptMemoryPrerelease(chain, activation);
    }

    private Object interceptMemoryPrerelease(
            XposedInterface.Chain chain, Activation current) throws Throwable {
        if (!isActive(current)) return chain.proceed();
        ActionProfile profile = actionProfile(current, chain);
        if (profile == null) return chain.proceed();
        List<?> source = listArg(chain, 0);
        if (source == null) {
            recordFailOpen(event(profile, null));
            return chain.proceed();
        }
        ArrayList<Object> filtered = defensiveCopy(source, profile);
        if (filtered == null) return chain.proceed();
        Object[] nextArgs = replacementArgs(chain, 0, filtered, profile);
        if (nextArgs == null) return chain.proceed();
        filtered.removeIf(candidate -> shouldFilterCandidate(
                current.adapter.resolveCandidate(candidate), profile));
        return chain.proceed(nextArgs);
    }

    private ActionProfile actionProfile(
            Activation current, XposedInterface.Chain chain) {
        ActionCodeResolution resolution;
        try {
            resolution = current.adapter.resolveActionCode(
                    chain == null ? null : chain.getThisObject());
        } catch (Throwable error) {
            recordFailOpen(event(unknownActionProfile(-1), null));
            return null;
        }
        if (!resolution.isResolved()) {
            recordFailOpen(event(unknownActionProfile(-1), null));
            return null;
        }
        ActionProfile profile = profileForActionCode(resolution.code());
        if (profile == null) {
            recordFailOpen(event(unknownActionProfile(resolution.code()), null));
        }
        return profile;
    }

    private boolean shouldBlock(
            IdentityResolution resolution, ActionProfile profile) {
        KillEvaluation evaluation = evaluate(resolution, profile);
        if (evaluation == null) return false;
        recordDecision(evaluation.event, evaluation.decision);
        return evaluation.decision.blocksAction();
    }

    private boolean shouldFilterCandidate(
            IdentityResolution resolution, ActionProfile profile) {
        KillEvaluation evaluation = evaluate(resolution, profile);
        if (evaluation == null) return false;
        if (evaluation.decision.blocksAction()) {
            recordCandidateFiltered(evaluation.event);
            return true;
        }
        recordDecision(evaluation.event, evaluation.decision);
        return false;
    }

    private KillEvaluation evaluate(
            IdentityResolution resolution, ActionProfile profile) {
        Identity identity = resolution == null ? null : resolution.identityOrNull();
        HookEvent event = event(profile, identity);
        if (identity == null) {
            recordFailOpen(event);
            return null;
        }

        KillDecision decision;
        try {
            decision = decisionResolver.decide(identity.target(), profile);
        } catch (Throwable error) {
            recordFailOpen(event);
            return null;
        }
        if (decision == null) {
            recordFailOpen(event);
            return null;
        }
        return new KillEvaluation(event, decision);
    }

    private ArrayList<Object> defensiveCopy(
            List<?> source, ActionProfile profile) {
        try {
            ArrayList<Object> copy = new ArrayList<>(source.size());
            copy.addAll(source);
            return copy;
        } catch (Throwable error) {
            recordFailOpen(event(profile, null));
            return null;
        }
    }

    private static List<?> listArg(XposedInterface.Chain chain, int index) {
        try {
            Object value = HookUtils.objectArg(chain, index);
            return value instanceof List ? (List<?>) value : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private Object[] replacementArgs(
            XposedInterface.Chain chain,
            int index,
            Object replacement,
            ActionProfile profile) {
        try {
            List<Object> args = chain.getArgs();
            if (args == null || index < 0 || index >= args.size()) {
                recordFailOpen(event(profile, null));
                return null;
            }
            Object[] nextArgs = args.toArray(new Object[0]);
            nextArgs[index] = replacement;
            return nextArgs;
        } catch (Throwable error) {
            recordFailOpen(event(profile, null));
            return null;
        }
    }

    private static KillDecision decide(
            ResidencyTarget target, ActionProfile profile) {
        ResidencyTarget uidTarget = new ResidencyTarget(
                null, target.uid(), target.pid());
        KillContext context = new KillContext(
                uidTarget,
                profile.path,
                profile.featureId,
                profile.hookTargetId);
        return profile.featureKey.isEmpty()
                ? HookResidencyRuntime.decideKill(context)
                : HookResidencyRuntime.decideKill(
                        context,
                        profile.featureKey,
                        ModuleSettings.defaultFor(profile.featureKey));
    }

    static ActionProfile profileForActionCode(int code) {
        switch (code) {
            case 2000:
            case 2003:
            case 2006:
                return fastProfile(
                        FeatureIds.FAST_CAMERA_START,
                        ModuleSettings.KEY_FAST_CAMERA_START,
                        code,
                        "fast_camera_start");
            case 2001:
            case 2002:
            case 2004:
            case 2005:
            case 2007:
            case 2008:
                return fastProfile(
                        FeatureIds.FAST_CAMERA_OPERATION,
                        ModuleSettings.KEY_FAST_CAMERA_OPERATION,
                        code,
                        "fast_camera_operation");
            case 2009:
                return fastProfile(
                        FeatureIds.FAST_GAME_START,
                        ModuleSettings.KEY_FAST_GAME_START,
                        code,
                        "fast_game_start");
            case 2010:
            case 2011:
                return fastProfile(
                        FeatureIds.FAST_AI,
                        ModuleSettings.KEY_FAST_AI,
                        code,
                        "fast_ai");
            case 4004:
            case 4005:
            case 4006:
            case 4007:
                return fastProfile(
                        FeatureIds.FAST_CLEAR_OTHER,
                        "",
                        code,
                        "fast_clear");
            case 4012:
                return memoryProfile(
                        FeatureIds.MEMORY_FORCE,
                        ModuleSettings.KEY_MEMORY_FORCE,
                        code,
                        "memory_force");
            case 4013:
                return memoryProfile(
                        FeatureIds.MEMORY_OVER,
                        ModuleSettings.KEY_MEMORY_OVER,
                        code,
                        "memory_over");
            case 392:
            case 4001:
            case 4002:
            case 4003:
            case 4008:
            case 4009:
            case 4010:
            case 4011:
            case 4014:
                return memoryProfile(
                        FeatureIds.MEMORY_GUARD_OTHER,
                        "",
                        code,
                        "memory_guard");
            default:
                return null;
        }
    }

    private static ActionProfile fastProfile(
            String featureId, String featureKey, int code, String scene) {
        return new ActionProfile(
                KillContext.Path.FAST_CLEAR,
                featureId,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                featureKey,
                code,
                scene);
    }

    private static ActionProfile memoryProfile(
            String featureId, String featureKey, int code, String scene) {
        return new ActionProfile(
                KillContext.Path.MEMORY_GUARD,
                featureId,
                HookTargetIds.MEMORY_GUARD_DISPATCH,
                featureKey,
                code,
                scene);
    }

    static ActionProfile unknownActionProfile(int code) {
        return new ActionProfile(
                KillContext.Path.MEMORY_GUARD,
                FeatureIds.ATHENA_OTHER,
                HookTargetIds.MEMORY_GUARD_DISPATCH,
                "",
                code,
                "memory_guard_unknown");
    }

    private static HookEvent event(ActionProfile profile, Identity identity) {
        return new HookEvent(
                profile.featureId,
                profile.code,
                identity == null ? ResidencyTarget.UNKNOWN_UID : identity.uid(),
                identity == null ? "" : identity.packageName(),
                identity == null ? "" : identity.processName(),
                profile.scene);
    }

    private void recordDecision(HookEvent event, KillDecision decision) {
        try {
            diagnosticsRecorder.recordDecision(event, decision);
        } catch (Throwable ignored) {
            // Diagnostics are never allowed to alter a terminal decision.
        }
    }

    private void recordCandidateFiltered(HookEvent event) {
        try {
            diagnosticsRecorder.recordCandidateFiltered(event);
        } catch (Throwable ignored) {
            // Candidate diagnostics are never allowed to alter list filtering.
        }
    }

    private void recordFailOpen(HookEvent event) {
        try {
            diagnosticsRecorder.recordFailOpen(event);
        } catch (Throwable ignored) {
            // Failure diagnostics are fail-open too.
        }
    }

    private static boolean isActive(Activation activation) {
        return activation != null && activation.active;
    }

    private XposedInterface.Hooker hookerFor(
            AthenaTerminalRuntime.Capability capability, Activation candidate) {
        switch (capability) {
            case PERIODIC_SINGLE:
                return chain -> interceptPeriodicSingle(chain, candidate);
            case PERIODIC_LIST:
                return chain -> interceptPeriodicList(chain, candidate);
            case TOURNAMENT_SINGLE:
                return chain -> interceptTournament(chain, candidate);
            case MEMORY_SINGLE:
                return chain -> interceptMemorySingle(chain, candidate);
            case MEMORY_GROUP:
                return chain -> interceptMemoryGroup(chain, candidate);
            case MEMORY_PRERELEASE:
                return chain -> interceptMemoryPrerelease(chain, candidate);
            default:
                throw new AssertionError(capability);
        }
    }

    private static Method validatedMethod(
            AthenaTerminalRuntime.CapabilityMatrix matrix,
            AthenaTerminalRuntime.Capability capability) {
        Method method = matrix.methodOrNull(capability);
        return method != null && AthenaTerminalRuntime.matches(capability, method)
                ? method : null;
    }

    private static Method validatedCaller(
            AthenaTerminalRuntime.CapabilityMatrix matrix,
            AthenaTerminalRuntime.DirectCaller caller) {
        Method method = matrix.callerMethodOrNull(caller);
        return method != null && AthenaTerminalRuntime.matches(caller, method)
                ? method : null;
    }

    private static String unavailableStatus(
            AthenaTerminalRuntime.CapabilityMatrix matrix) {
        boolean lookupFailed = false;
        for (AthenaTerminalRuntime.Capability capability
                : AthenaTerminalRuntime.Capability.values()) {
            if (matrix.status(capability) == AthenaTerminalRuntime.Status.LOOKUP_FAILED) {
                lookupFailed = true;
                break;
            }
        }
        if (!lookupFailed) {
            for (AthenaTerminalRuntime.DirectCaller caller
                    : AthenaTerminalRuntime.DirectCaller.values()) {
                if (matrix.callerStatus(caller)
                        == AthenaTerminalRuntime.Status.LOOKUP_FAILED) {
                    lookupFailed = true;
                    break;
                }
            }
        }
        return (lookupFailed ? "FAILED_FAIL_OPEN:" : "UNSUPPORTED_") + matrix.summary();
    }

    private static int installHook(
            String className,
            ClassLoader loader,
            HookUtils.MethodMatcher matcher,
            XposedInterface.Hooker hooker) {
        return HookUtils.hookMatching(className, loader, matcher, hooker);
    }

    interface DecisionResolver {
        KillDecision decide(ResidencyTarget target, ActionProfile profile);
    }

    interface DiagnosticsRecorder {
        void recordDecision(HookEvent event, KillDecision decision);
        void recordCandidateFiltered(HookEvent event);
        void recordFailOpen(HookEvent event);
    }

    interface HookInstaller {
        int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker);
    }

    interface AdapterPreparer {
        AthenaIdentityAdapter.Preparation prepare(
                AthenaTerminalRuntime.CapabilityMatrix matrix);
    }

    interface MethodDeoptimizer {
        boolean deoptimize(Method method);
    }

    static final class ActionProfile {
        private final KillContext.Path path;
        private final String featureId;
        private final String hookTargetId;
        private final String featureKey;
        private final int code;
        private final String scene;

        private ActionProfile(
                KillContext.Path path,
                String featureId,
                String hookTargetId,
                String featureKey,
                int code,
                String scene) {
            this.path = Objects.requireNonNull(path, "path");
            this.featureId = Objects.requireNonNull(featureId, "featureId");
            this.hookTargetId = Objects.requireNonNull(hookTargetId, "hookTargetId");
            this.featureKey = Objects.requireNonNull(featureKey, "featureKey");
            this.code = code;
            this.scene = Objects.requireNonNull(scene, "scene");
        }

        KillContext.Path path() { return path; }
        String featureId() { return featureId; }
        String hookTargetId() { return hookTargetId; }
        String featureKey() { return featureKey; }
        int code() { return code; }
        String scene() { return scene; }
    }

    private static final class Activation {
        private final AthenaIdentityAdapter.Prepared adapter;
        private volatile boolean active;

        private Activation(AthenaIdentityAdapter.Prepared adapter, boolean active) {
            this.adapter = Objects.requireNonNull(adapter, "adapter");
            this.active = active;
        }
    }

    private static final class KillEvaluation {
        private final HookEvent event;
        private final KillDecision decision;

        private KillEvaluation(HookEvent event, KillDecision decision) {
            this.event = Objects.requireNonNull(event, "event");
            this.decision = Objects.requireNonNull(decision, "decision");
        }
    }

    private static final class DefaultDiagnosticsRecorder implements DiagnosticsRecorder {
        @Override
        public void recordDecision(HookEvent event, KillDecision decision) {
            ResidencyDecisionReporter.recordKill(event, decision);
        }

        @Override
        public void recordCandidateFiltered(HookEvent event) {
            HookDiagnostics.recordEvent(event, DiagnosticsResult.CANDIDATE_FILTERED);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            HookDiagnostics.recordEvent(event, "fail_open");
        }
    }

    private static final class TargetHook implements FeatureHook {
        private final AthenaTerminalGate gate;
        private final String hookTargetId;
        private final String prefKey;

        private TargetHook(AthenaTerminalGate gate, String hookTargetId, String prefKey) {
            this.gate = gate;
            this.hookTargetId = hookTargetId;
            this.prefKey = prefKey;
        }

        @Override public String hookTargetId() { return hookTargetId; }
        @Override public String prefKey() { return prefKey; }
        @Override public String install(ClassLoader loader) { return gate.install(loader); }
    }
}
