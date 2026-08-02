package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookConfig;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.hooks.hans.HansSourceClassifier;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** HANS freeze gate with an object-level terminal hook and old-ROM fallbacks. */
public final class HansFreezeHook implements FeatureHook {
    private static final String CGROUP_CLASS = "com.android.server.hans.freeze.HansCGroup";
    private static final String MANAGER_CLASS = "com.android.server.am.OplusHansManager";
    private static final Object INSTALLED_SOURCES_LOCK = new Object();
    private static volatile InstalledFreezeSources sInstalledSources =
            new InstalledFreezeSources(0L, Collections.emptySet());
    private static long sInstallationGeneration;
    static final int UNKNOWN_SOURCE_CODE = -1;

    private final FreezeDecisionResolver decisionResolver;
    private final HookInstaller hookInstaller;
    private final ThreadLocal<Boolean> managerFallbackActive = new ThreadLocal<>();

    public HansFreezeHook() {
        this(HansFreezeHook::decide, HansFreezeHook::installHook);
    }

    HansFreezeHook(FreezeDecisionResolver decisionResolver) {
        this(decisionResolver, HansFreezeHook::installHook);
    }

    HansFreezeHook(
            FreezeDecisionResolver decisionResolver,
            HookInstaller hookInstaller) {
        this.decisionResolver = Objects.requireNonNull(decisionResolver, "decisionResolver");
        this.hookInstaller = Objects.requireNonNull(hookInstaller, "hookInstaller");
    }

    @Override public String hookTargetId() { return HookTargetIds.HANS_FREEZE_DISPATCH; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        long installationGeneration = beginInstallation();
        HansRuntime.CapabilityMatrix matrix = HansRuntime.inspect(loader);
        HansRuntime.Status objectStatus = matrix.status(HansRuntime.Capability.FREEZE_GATE);
        Method objectMethod = matrix.methodOrNull(HansRuntime.Capability.FREEZE_GATE);
        String objectFailure = objectStatus == HansRuntime.Status.AVAILABLE
                ? "FAILED_FAIL_OPEN:OBJECT_METHOD_UNAVAILABLE"
                : capabilityStatus(objectStatus);

        if (objectStatus == HansRuntime.Status.AVAILABLE
                && objectMethod != null
                && HansRuntime.matches(HansRuntime.Capability.FREEZE_GATE, objectMethod)) {
            int objectCount = hookInstaller.hookMatching(
                    CGROUP_CLASS,
                    loader,
                    method -> objectMethod.equals(method),
                    this::interceptObject);
            if (objectCount == 1) {
                String status = installedStatus(1, 0, matrix.status(
                        HansRuntime.Capability.FAST_FREEZE_V2_PATH));
                publishInstalledSources(
                        installationGeneration,
                        EnumSet.of(
                                FreezeSource.NORMAL,
                                FreezeSource.SUPER,
                                FreezeSource.PRELOAD));
                return status;
            }
            objectFailure = objectCount < 0
                    ? "FAILED_FAIL_OPEN:OBJECT_CLASS_LOOKUP"
                    : "FAILED_FAIL_OPEN:OBJECT_HOOK_COUNT=" + objectCount;
        }

        // Older firmware may not expose the package-object ABI. Keep its known
        // manager entry points as a compatibility fallback, but never layer them
        // over a successfully installed terminal object gate.
        int hans = hookInstaller.hookMatching(
                MANAGER_CLASS, loader, HansFreezeHook::isHansFreeze, this::interceptHans);
        int superFreeze = hookInstaller.hookMatching(
                MANAGER_CLASS, loader, HansFreezeHook::isSuperFreeze,
                this::interceptSuperFreeze);
        if (hans < 0 && superFreeze < 0) {
            return objectFailure;
        }
        if (hans <= 0 && superFreeze <= 0) {
            return objectFailure.startsWith("FAILED")
                    ? objectFailure
                    : "UNSUPPORTED_SIGNATURE";
        }
        String status = installedStatus(0,
                Math.max(hans, 0) + Math.max(superFreeze, 0),
                matrix.status(HansRuntime.Capability.FAST_FREEZE_V2_PATH));
        EnumSet<FreezeSource> installedSources = EnumSet.noneOf(FreezeSource.class);
        if (hans > 0) {
            installedSources.add(FreezeSource.NORMAL);
            installedSources.add(FreezeSource.PRELOAD);
        }
        if (superFreeze > 0) installedSources.add(FreezeSource.SUPER);
        publishInstalledSources(installationGeneration, installedSources);
        return status;
    }

    /** Returns the immutable, atomically published capabilities of the latest install. */
    public static Set<FreezeSource> installedFreezeSources() {
        return sInstalledSources.sources;
    }

    private static long beginInstallation() {
        synchronized (INSTALLED_SOURCES_LOCK) {
            long generation = ++sInstallationGeneration;
            sInstalledSources = new InstalledFreezeSources(
                    generation, Collections.emptySet());
            return generation;
        }
    }

    private static void publishInstalledSources(
            long installationGeneration,
            Set<FreezeSource> sources) {
        Set<FreezeSource> snapshot = sources.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(sources));
        synchronized (INSTALLED_SOURCES_LOCK) {
            if (sInstalledSources.generation != installationGeneration) return;
            sInstalledSources = new InstalledFreezeSources(
                    installationGeneration, snapshot);
        }
    }

    static void resetInstalledFreezeSourcesForTests() {
        beginInstallation();
    }

    private static int installHook(
            String className,
            ClassLoader loader,
            HookUtils.MethodMatcher matcher,
            XposedInterface.Hooker hooker) {
        return HookUtils.hookMatching(className, loader, matcher, hooker);
    }

    private static String installedStatus(
            int objectCount, int fallbackCount, HansRuntime.Status fastStatus) {
        return "INSTALLED:object=" + objectCount
                + ",fallback=" + fallbackCount
                + ",fast=" + fastStatus.name();
    }

    Object interceptObject(XposedInterface.Chain chain) throws Throwable {
        Object packageObject = HookUtils.objectArg(chain, 0);
        Object sourceValue = HookUtils.objectArg(chain, 1);
        String sourceMarker = sourceValue instanceof String ? (String) sourceValue : null;

        HansObjectAdapter.Resolution resolution;
        try {
            resolution = HansObjectAdapter.resolve(packageObject);
        } catch (Throwable error) {
            recordFailure(sourceMarker);
            return chain.proceed();
        }
        if (!resolution.isResolved()) {
            recordFailure(sourceMarker);
            return chain.proceed();
        }

        HansObjectAdapter.Target hansTarget = resolution.targetOrNull();
        FreezeSource source;
        try {
            HansSourceClassifier.Resolution classified = HansSourceClassifier.classify(
                    sourceMarker, hansTarget.superFreezeMode());
            if (!classified.isResolved()) {
                recordFailure(hansTarget, sourceMarker);
                return chain.proceed();
            }
            source = classified.sourceOrNull();
        } catch (Throwable error) {
            recordFailure(hansTarget, sourceMarker);
            return chain.proceed();
        }

        HookEvent event = event(hansTarget, source, sourceMarker);
        if (hansTarget.frozen()) {
            recordPassed(event);
            return chain.proceed();
        }

        FreezeDecision decision;
        try {
            decision = decisionResolver.decide(new ResidencyTarget(
                    hansTarget.packageName(),
                    hansTarget.uid(),
                    ResidencyTarget.UNKNOWN_PID),
                    source);
        } catch (Throwable error) {
            recordFailure(event);
            return chain.proceed();
        }

        if (shouldUseLegacyGlobal(decision)) {
            return completeLegacy(
                    chain::proceed, event, legacyPrefKey(source), Boolean.FALSE);
        }

        ResidencyDecisionReporter.recordFreezeFromLockedPath(event, decision);
        return complete(chain::proceed, decision, Boolean.FALSE);
    }

    Object interceptHans(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(managerFallbackActive.get())) {
            return chain.proceed();
        }
        managerFallbackActive.set(Boolean.TRUE);
        try {
            return interceptHansTopLevel(chain);
        } finally {
            managerFallbackActive.remove();
        }
    }

    private Object interceptHansTopLevel(XposedInterface.Chain chain) throws Throwable {
        Object sourceValue = HookUtils.objectArg(chain, 1);
        String sourceMarker = sourceValue instanceof String ? (String) sourceValue : null;
        HansSourceClassifier.Resolution classified;
        try {
            classified = HansSourceClassifier.classifyManager(sourceMarker);
        } catch (Throwable error) {
            recordFailure(managerFailureEvent(chain, sourceMarker));
            return chain.proceed();
        }
        if (!classified.isResolved()) {
            recordFailure(managerFailureEvent(chain, sourceMarker));
            return chain.proceed();
        }
        return interceptManager(chain, classified.sourceOrNull(), false, sourceMarker);
    }

    Object interceptSuperFreeze(XposedInterface.Chain chain) throws Throwable {
        return interceptManager(chain, FreezeSource.SUPER, true, "Super_F");
    }

    private Object interceptManager(
            XposedInterface.Chain chain,
            FreezeSource source,
            boolean voidReturn,
            String scene) throws Throwable {
        int uid = HookUtils.intArg(chain, 0, ResidencyTarget.UNKNOWN_UID);
        HookEvent event = new HookEvent(
                featureFor(source),
                source.ordinal(),
                uid,
                "",
                "",
                scene);
        if (uid < 0) {
            recordFailure(event);
            return chain.proceed();
        }

        FreezeDecision decision;
        try {
            decision = decisionResolver.decide(
                    new ResidencyTarget(null, uid, ResidencyTarget.UNKNOWN_PID), source);
        } catch (Throwable error) {
            recordFailure(event);
            return chain.proceed();
        }
        if (shouldUseLegacyGlobal(decision)) {
            return completeLegacy(
                    chain::proceed,
                    event,
                    legacyPrefKey(source),
                    voidReturn ? null : Boolean.FALSE);
        }

        ResidencyDecisionReporter.recordFreezeFromLockedPath(event, decision);
        return complete(chain::proceed, decision, voidReturn ? null : Boolean.FALSE);
    }

    private static FreezeDecision decide(ResidencyTarget target, FreezeSource source) {
        return HookResidencyRuntime.decideFreeze(new FreezeContext(
                target,
                source,
                featureFor(source),
                HookTargetIds.HANS_FREEZE_DISPATCH));
    }

    /** Calls the vendor method exactly once unless an explicit freeze block is effective. */
    static Object complete(
            OriginalCall originalCall,
            FreezeDecision decision,
            Object blockedResult) throws Throwable {
        if (decision != null
                && decision.effectiveAction() == FreezeDecision.Action.BLOCK_FREEZE) {
            return blockedResult;
        }
        return originalCall.proceed();
    }

    static boolean shouldUseLegacyGlobal(FreezeDecision decision) {
        if (decision == null) return false;
        if (decision.trace().disposition() == DecisionDisposition.FAIL_OPEN) return false;
        return !decision.trace().identity().hasManagedPolicy();
    }

    private static Object completeLegacy(
            OriginalCall originalCall,
            HookEvent event,
            String prefKey,
            Object blockedResult) throws Throwable {
        try {
            Decision.LockedResult outcome = Decision.evaluateFromLockedPath(prefKey);
            recordLegacy(event, outcome);
            if (outcome.blocked()) return blockedResult;
        } catch (Throwable ignored) {
            recordFailure(event);
        }
        return originalCall.proceed();
    }

    static boolean isHansFreeze(Method method) {
        if (method == null
                || !"hansFreeze".equals(method.getName())
                || method.getReturnType() != boolean.class
                || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return (parameters.length == 2 || parameters.length == 3)
                && parameters[0] == int.class
                && parameters[1] == String.class
                && (parameters.length == 2 || parameters[2] == boolean.class);
    }

    static boolean isSuperFreeze(Method method) {
        if (method == null
                || !"enterSuperFreeze".equals(method.getName())
                || method.getReturnType() != void.class
                || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 1 && parameters[0] == int.class;
    }

    static String capabilityStatus(HansRuntime.Status status) {
        if (status == HansRuntime.Status.LOOKUP_FAILED) {
            return "FAILED_FAIL_OPEN:" + status.name();
        }
        return "UNSUPPORTED_" + status.name();
    }

    static String featureFor(FreezeSource source) {
        return source == FreezeSource.SUPER
                ? FeatureIds.SUPER_FREEZE
                : FeatureIds.HANS;
    }

    static String legacyPrefKey(FreezeSource source) {
        return source == FreezeSource.SUPER
                ? ModuleSettings.KEY_SUPER_FREEZE
                : ModuleSettings.KEY_HANS;
    }

    private static HookEvent event(
            HansObjectAdapter.Target target, FreezeSource source, String marker) {
        return new HookEvent(
                featureFor(source),
                source.ordinal(),
                target.uid(),
                target.packageName(),
                "",
                marker == null ? "" : marker);
    }

    static HookEvent failureEvent(
            HansObjectAdapter.Target target, String marker) {
        return new HookEvent(
                target != null && target.superFreezeMode()
                        ? FeatureIds.SUPER_FREEZE
                        : FeatureIds.HANS,
                UNKNOWN_SOURCE_CODE,
                target == null ? ResidencyTarget.UNKNOWN_UID : target.uid(),
                target == null ? "" : target.packageName(),
                "",
                marker == null ? "" : marker);
    }

    private static HookEvent managerFailureEvent(
            XposedInterface.Chain chain, String marker) {
        return new HookEvent(
                FeatureIds.HANS,
                UNKNOWN_SOURCE_CODE,
                HookUtils.intArg(chain, 0, ResidencyTarget.UNKNOWN_UID),
                "",
                "",
                marker == null ? "" : marker);
    }

    private static void recordPassed(HookEvent event) {
        try {
            HookDiagnostics.recordEventFromLockedPath(event, "passed");
        } catch (Throwable ignored) {
            // Diagnostics must never affect the vendor callback.
        }
    }

    private static void recordLegacy(HookEvent event, Decision.LockedResult outcome) {
        try {
            HookDiagnostics.recordEventFromLockedPath(
                    event,
                    outcome == null ? "fail_open" : outcome.wireValue());
        } catch (Throwable ignored) {
            // Diagnostics must never affect the vendor callback.
        }
    }

    private static void recordFailure(String marker) {
        recordFailure(failureEvent(null, marker));
    }

    private static void recordFailure(
            HansObjectAdapter.Target target, String marker) {
        recordFailure(failureEvent(target, marker));
    }

    private static void recordFailure(HookEvent event) {
        try {
            HookDiagnostics.recordEventFromLockedPath(event, "fail_open");
        } catch (Throwable ignored) {
            // Diagnostics must never affect the vendor callback.
        }
    }

    interface OriginalCall {
        Object proceed() throws Throwable;
    }

    interface FreezeDecisionResolver {
        FreezeDecision decide(ResidencyTarget target, FreezeSource source);
    }

    interface HookInstaller {
        int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker);
    }

    private static final class InstalledFreezeSources {
        private final long generation;
        private final Set<FreezeSource> sources;

        private InstalledFreezeSources(long generation, Set<FreezeSource> sources) {
            this.generation = generation;
            this.sources = sources;
        }
    }
}
