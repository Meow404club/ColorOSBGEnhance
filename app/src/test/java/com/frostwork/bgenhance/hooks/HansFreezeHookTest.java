package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import com.android.server.am.OplusHansManager;
import com.android.server.hans.OplusHansPackage;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class HansFreezeHookTest {
    private static final String PACKAGE = "com.example.hans.freeze";
    private static final int UID = 10_901;

    @Before
    public void clearInstalledSourcesBeforeTest() {
        HansFreezeHook.resetInstalledFreezeSourcesForTests();
    }

    @After
    public void clearInstalledSourcesAfterTest() {
        HansFreezeHook.resetInstalledFreezeSourcesForTests();
    }

    @Test
    public void explicitBlockSkipsOriginalAndReturnsFalse() throws Throwable {
        AtomicInteger originalCalls = new AtomicInteger();
        FreezeDecision decision = decision(false, false, false, false);

        Object result = HansFreezeHook.complete(
                () -> {
                    originalCalls.incrementAndGet();
                    return Boolean.TRUE;
                },
                decision,
                Boolean.FALSE);

        assertEquals(Boolean.FALSE, result);
        assertEquals(0, originalCalls.get());
    }

    @Test
    public void objectInterceptorClassifiesEverySupportedSourceBeforeBlocking() throws Throwable {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
        FreezeSource[] sources = FreezeSource.values();
        String[] markers = {"", "enterFF", "Super_F", "Preload_F"};

        for (int index = 0; index < sources.length; index++) {
            FreezeSource expected = sources[index];
            AtomicReference<ResidencyTarget> capturedTarget = new AtomicReference<>();
            AtomicReference<FreezeSource> capturedSource = new AtomicReference<>();
            HansFreezeHook hook = new HansFreezeHook((target, source) -> {
                capturedTarget.set(target);
                capturedSource.set(source);
                return blockedDecision(source);
            });
            FakeChain chain = new FakeChain(
                    Boolean.TRUE,
                    new OplusHansPackage(
                            UID,
                            PACKAGE,
                            false,
                            expected == FreezeSource.SUPER,
                            new Object()),
                    markers[index]);

            assertEquals(Boolean.FALSE, hook.interceptObject(chain));
            assertEquals(0, chain.proceedCalls());
            assertEquals(expected, capturedSource.get());
            assertEquals(UID, capturedTarget.get().uid());
            assertEquals(PACKAGE, capturedTarget.get().packageName());
        }
    }

    @Test
    public void objectInterceptorFailsOpenForUnknownConflictingAndInvalidObjects() throws Throwable {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
        AtomicInteger decisions = new AtomicInteger();
        HansFreezeHook hook = new HansFreezeHook((target, source) -> {
            decisions.incrementAndGet();
            return blockedDecision(source);
        });

        FakeChain unknown = new FakeChain(
                Boolean.TRUE,
                new OplusHansPackage(UID, PACKAGE, false, false, new Object()),
                "future_freeze_source");
        FakeChain conflict = new FakeChain(
                Boolean.TRUE,
                new OplusHansPackage(UID, PACKAGE, false, true, new Object()),
                "enterFF");
        FakeChain invalid = new FakeChain(Boolean.TRUE, new Object(), "");

        assertEquals(Boolean.TRUE, hook.interceptObject(unknown));
        assertEquals(Boolean.TRUE, hook.interceptObject(conflict));
        assertEquals(Boolean.TRUE, hook.interceptObject(invalid));
        assertEquals(1, unknown.proceedCalls());
        assertEquals(1, conflict.proceedCalls());
        assertEquals(1, invalid.proceedCalls());
        assertEquals(0, decisions.get());
    }

    @Test
    public void objectInterceptorPreservesFrozenVendorIdempotency() throws Throwable {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
        AtomicInteger decisions = new AtomicInteger();
        HansFreezeHook hook = new HansFreezeHook((target, source) -> {
            decisions.incrementAndGet();
            return blockedDecision(source);
        });
        FakeChain chain = new FakeChain(
                Boolean.TRUE,
                new OplusHansPackage(UID, PACKAGE, true, false, new Object()),
                "");

        assertEquals(Boolean.TRUE, hook.interceptObject(chain));
        assertEquals(1, chain.proceedCalls());
        assertEquals(0, decisions.get());
    }

    @Test
    public void managerFallbackClassifiesKnownMarkersAndFailsOpenForUnknownSources()
            throws Throwable {
        FreezeSource[] sources = FreezeSource.values();
        String[] markers = {"", "enterFF", "Super_F", "Preload_F"};

        for (int index = 0; index < sources.length; index++) {
            AtomicReference<FreezeSource> captured = new AtomicReference<>();
            HansFreezeHook hook = new HansFreezeHook((target, source) -> {
                captured.set(source);
                return blockedDecision(source);
            });
            FakeChain chain = new FakeChain(Boolean.TRUE, UID, markers[index], false);

            assertEquals(Boolean.FALSE, hook.interceptHans(chain));
            assertEquals(0, chain.proceedCalls());
            assertEquals(sources[index], captured.get());
        }

        AtomicInteger decisions = new AtomicInteger();
        HansFreezeHook hook = new HansFreezeHook((target, source) -> {
            decisions.incrementAndGet();
            return blockedDecision(source);
        });
        FakeChain unknown = new FakeChain(Boolean.TRUE, UID, "oguard");
        FakeChain nullMarker = new FakeChain(Boolean.TRUE, UID, null);

        assertEquals(Boolean.TRUE, hook.interceptHans(unknown));
        assertEquals(Boolean.TRUE, hook.interceptHans(nullMarker));
        assertEquals(1, unknown.proceedCalls());
        assertEquals(1, nullMarker.proceedCalls());
        assertEquals(0, decisions.get());
    }

    @Test
    public void managerFallbackEvaluatesRealTwoToThreeArgumentDelegationOnlyOnce()
            throws Exception {
        AtomicInteger decisions = new AtomicInteger();
        HansFreezeHook hook = new HansFreezeHook((target, source) -> {
            decisions.incrementAndGet();
            return allowedDecision(source);
        });
        HookedHansManager manager = new HookedHansManager(hook);

        assertTrue(manager.hansFreeze(UID, ""));
        assertEquals(1, decisions.get());
        assertEquals(1, manager.twoArgumentProceedCalls());
        assertEquals(1, manager.threeArgumentProceedCalls());
    }

    @Test
    public void managerFallbackClearsNestedGuardAfterVendorFailure() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        HansFreezeHook hook = new HansFreezeHook((target, source) -> {
            decisions.incrementAndGet();
            return allowedDecision(source);
        });
        OriginalFailure failure = new OriginalFailure();
        FakeChain failed = FakeChain.calling(() -> {
            throw failure;
        }, UID, "");

        assertSame(failure, assertThrows(
                OriginalFailure.class, () -> hook.interceptHans(failed)));
        FakeChain retry = new FakeChain(Boolean.TRUE, UID, "");
        assertEquals(Boolean.TRUE, hook.interceptHans(retry));
        assertEquals(2, decisions.get());
        assertEquals(1, retry.proceedCalls());
    }

    @Test
    public void superFallbackReturnsVoidWithoutCallingOriginalWhenBlocked() throws Throwable {
        AtomicReference<FreezeSource> captured = new AtomicReference<>();
        HansFreezeHook hook = new HansFreezeHook((target, source) -> {
            captured.set(source);
            return blockedDecision(source);
        });
        FakeChain chain = new FakeChain(null, UID);

        assertNull(hook.interceptSuperFreeze(chain));
        assertEquals(0, chain.proceedCalls());
        assertEquals(FreezeSource.SUPER, captured.get());
    }

    @Test
    public void unclassifiedFailuresKeepIdentityWithoutPretendingToBeNormal() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
        HansObjectAdapter.Target normalTarget = HansObjectAdapter.resolve(
                new OplusHansPackage(UID, PACKAGE, false, false, new Object())).targetOrNull();
        HansObjectAdapter.Target superTarget = HansObjectAdapter.resolve(
                new OplusHansPackage(UID, PACKAGE, false, true, new Object())).targetOrNull();

        HookEvent normal = HansFreezeHook.failureEvent(normalTarget, "future");
        HookEvent superFreeze = HansFreezeHook.failureEvent(superTarget, "enterFF");
        HookEvent unresolved = HansFreezeHook.failureEvent(null, null);

        assertEquals(HansFreezeHook.UNKNOWN_SOURCE_CODE, normal.code);
        assertEquals(FeatureIds.HANS, normal.feature);
        assertEquals(UID, normal.uid);
        assertEquals(PACKAGE, normal.packageName);
        assertEquals(HansFreezeHook.UNKNOWN_SOURCE_CODE, superFreeze.code);
        assertEquals(FeatureIds.SUPER_FREEZE, superFreeze.feature);
        assertEquals(HansFreezeHook.UNKNOWN_SOURCE_CODE, unresolved.code);
        assertEquals(ResidencyTarget.UNKNOWN_UID, unresolved.uid);
    }

    @Test
    public void dryRunAndFollowSystemCallOriginalExactlyOnce() throws Throwable {
        AtomicInteger originalCalls = new AtomicInteger();
        FreezeDecision dryRun = decision(false, true, false, false);
        FreezeDecision followSystem = decision(true, false, false, false);

        assertEquals(Boolean.TRUE, HansFreezeHook.complete(
                () -> {
                    originalCalls.incrementAndGet();
                    return Boolean.TRUE;
                },
                dryRun,
                Boolean.FALSE));
        assertEquals(Boolean.TRUE, HansFreezeHook.complete(
                () -> {
                    originalCalls.incrementAndGet();
                    return Boolean.TRUE;
                },
                followSystem,
                Boolean.FALSE));
        assertEquals(2, originalCalls.get());
    }

    @Test
    public void originalFailurePropagatesWithoutASecondCall() {
        AtomicInteger originalCalls = new AtomicInteger();
        OriginalFailure failure = new OriginalFailure();
        FreezeDecision followSystem = decision(true, false, false, false);

        OriginalFailure actual = assertThrows(OriginalFailure.class, () ->
                HansFreezeHook.complete(
                        () -> {
                            originalCalls.incrementAndGet();
                            throw failure;
                        },
                        followSystem,
                        Boolean.FALSE));

        assertSame(failure, actual);
        assertEquals(1, originalCalls.get());
    }

    @Test
    public void matchesOnlyTheKnownManagerFallbackSignatures() throws Exception {
        int hansMatches = 0;
        int superMatches = 0;
        for (Method method : OplusHansManager.class.getDeclaredMethods()) {
            if (HansFreezeHook.isHansFreeze(method)) hansMatches++;
            if (HansFreezeHook.isSuperFreeze(method)) superMatches++;
        }
        assertEquals(2, hansMatches);
        assertEquals(1, superMatches);

        Method wrongHans = OplusHansManager.class.getDeclaredMethod(
                "isHansCoreApp", Integer.class, String.class);
        assertFalse(HansFreezeHook.isHansFreeze(wrongHans));
        Method wrongSuper = OplusHansManager.class.getDeclaredMethod(
                "isHansCoreApp", int.class, String.class);
        assertFalse(HansFreezeHook.isSuperFreeze(wrongSuper));
    }

    @Test
    public void fallbackUsesIndependentSourceFeatureAndPreferenceProjections() {
        assertEquals("hans_freeze", HansFreezeHook.featureFor(FreezeSource.NORMAL));
        assertEquals("hans_freeze", HansFreezeHook.featureFor(FreezeSource.FAST));
        assertEquals("hans_freeze", HansFreezeHook.featureFor(FreezeSource.PRELOAD));
        assertEquals("super_freeze", HansFreezeHook.featureFor(FreezeSource.SUPER));
        assertEquals(
                com.frostwork.bgenhance.ModuleSettings.KEY_HANS,
                HansFreezeHook.legacyPrefKey(FreezeSource.NORMAL));
        assertEquals(
                com.frostwork.bgenhance.ModuleSettings.KEY_SUPER_FREEZE,
                HansFreezeHook.legacyPrefKey(FreezeSource.SUPER));
    }

    @Test
    public void unmanagedTargetsMayUseLegacyGlobalButManagedRulesTakePrecedence() {
        FreezeDecision unmanaged = decisionFor(
                FreezePolicy.followSystem(), false, Map.of());
        FreezeDecision managed = decision(false, false, false, false);

        assertTrue(HansFreezeHook.shouldUseLegacyGlobal(unmanaged));
        assertFalse(HansFreezeHook.shouldUseLegacyGlobal(managed));
    }

    @Test
    public void capabilityStatusRemainsFailOpen() {
        assertEquals(
                "UNSUPPORTED_MISSING_CLASS",
                HansFreezeHook.capabilityStatus(com.frostwork.bgenhance.hooks.hans.HansRuntime.Status.MISSING_CLASS));
        assertEquals(
                "FAILED_FAIL_OPEN:LOOKUP_FAILED",
                HansFreezeHook.capabilityStatus(com.frostwork.bgenhance.hooks.hans.HansRuntime.Status.LOOKUP_FAILED));
        assertEquals(HookTargetIds.HANS_FREEZE_DISPATCH,
                new HansFreezeHook().hookTargetId());
    }

    @Test
    public void objectGatePublishesOnlySourcesCoveredByTheTerminalGate() {
        HansFreezeHook hook = new HansFreezeHook(
                (target, source) -> null,
                installerWithCounts(1, 0, 0));

        String status = hook.install(OplusHansPackage.class.getClassLoader());

        assertTrue(status, status.startsWith("INSTALLED:object=1,fallback=0"));
        Set<FreezeSource> sources = HansFreezeHook.installedFreezeSources();
        assertEquals(
                Set.of(FreezeSource.NORMAL, FreezeSource.SUPER, FreezeSource.PRELOAD),
                sources);
        assertFalse(sources.contains(FreezeSource.FAST));
        assertThrows(
                UnsupportedOperationException.class,
                () -> sources.add(FreezeSource.FAST));
    }

    @Test
    public void fallbackPublishesOnlyIndependentlyInstalledSourceFamilies() {
        assertFallbackSources(
                2,
                0,
                Set.of(FreezeSource.NORMAL, FreezeSource.PRELOAD));
        assertFallbackSources(
                0,
                1,
                Set.of(FreezeSource.SUPER));
        assertFallbackSources(
                2,
                1,
                Set.of(FreezeSource.NORMAL, FreezeSource.SUPER, FreezeSource.PRELOAD));
        assertFallbackSources(
                -1,
                1,
                Set.of(FreezeSource.SUPER));
        assertFallbackSources(
                2,
                -1,
                Set.of(FreezeSource.NORMAL, FreezeSource.PRELOAD));
    }

    @Test
    public void failedInstallationClearsPreviouslyPublishedSources() {
        HansFreezeHook installed = new HansFreezeHook(
                (target, source) -> null,
                installerWithCounts(1, 0, 0));
        assertTrue(installed.install(OplusHansPackage.class.getClassLoader())
                .startsWith("INSTALLED"));
        assertFalse(HansFreezeHook.installedFreezeSources().isEmpty());

        IllegalStateException failure = new IllegalStateException("installer");
        AtomicReference<Set<FreezeSource>> observedAtInstall = new AtomicReference<>();
        HansFreezeHook failing = new HansFreezeHook(
                (target, source) -> null,
                (className, loader, matcher, hooker) -> {
                    observedAtInstall.set(HansFreezeHook.installedFreezeSources());
                    throw failure;
                });

        assertSame(failure, assertThrows(
                IllegalStateException.class,
                () -> failing.install(OplusHansPackage.class.getClassLoader())));
        assertTrue(observedAtInstall.get().isEmpty());
        assertTrue(HansFreezeHook.installedFreezeSources().isEmpty());

        HansFreezeHook unsupported = new HansFreezeHook(
                (target, source) -> null,
                installerWithCounts(0, 0, 0));
        assertTrue(unsupported.install(OplusHansPackage.class.getClassLoader())
                .startsWith("FAILED_FAIL_OPEN"));
        assertTrue(HansFreezeHook.installedFreezeSources().isEmpty());
    }

    @Test
    public void olderConcurrentInstallCannotOverwriteLatestSnapshot() throws Exception {
        CountDownLatch olderInstallStarted = new CountDownLatch(1);
        CountDownLatch releaseOlderInstall = new CountDownLatch(1);
        AtomicReference<Throwable> olderFailure = new AtomicReference<>();
        AtomicInteger olderCalls = new AtomicInteger();
        HansFreezeHook older = new HansFreezeHook(
                (target, source) -> null,
                (className, loader, matcher, hooker) -> {
                    int call = olderCalls.getAndIncrement();
                    if (call == 0) {
                        olderInstallStarted.countDown();
                        try {
                            if (!releaseOlderInstall.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("older install was not released");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                        return 0;
                    }
                    return call == 1 ? 0 : 1;
                });
        Thread olderThread = new Thread(() -> {
            try {
                older.install(OplusHansPackage.class.getClassLoader());
            } catch (Throwable error) {
                olderFailure.set(error);
            }
        }, "HansFreezeHookTest-older-install");

        olderThread.start();
        assertTrue(olderInstallStarted.await(5, TimeUnit.SECONDS));
        HansFreezeHook latest = new HansFreezeHook(
                (target, source) -> null,
                installerWithCounts(1, 0, 0));
        assertTrue(latest.install(OplusHansPackage.class.getClassLoader())
                .startsWith("INSTALLED:object=1"));
        releaseOlderInstall.countDown();
        olderThread.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse("older install thread did not finish", olderThread.isAlive());
        assertNull(olderFailure.get());
        assertEquals(
                Set.of(FreezeSource.NORMAL, FreezeSource.SUPER, FreezeSource.PRELOAD),
                HansFreezeHook.installedFreezeSources());
    }

    private static void assertFallbackSources(
            int hansCount,
            int superCount,
            Set<FreezeSource> expected) {
        HansFreezeHook hook = new HansFreezeHook(
                (target, source) -> null,
                installerWithCounts(0, hansCount, superCount));

        String status = hook.install(OplusHansPackage.class.getClassLoader());

        assertTrue(status, status.startsWith("INSTALLED:object=0"));
        assertEquals(expected, HansFreezeHook.installedFreezeSources());
        assertFalse(HansFreezeHook.installedFreezeSources().contains(FreezeSource.FAST));
    }

    private static HansFreezeHook.HookInstaller installerWithCounts(
            int objectCount,
            int hansCount,
            int superCount) {
        int[] counts = {objectCount, hansCount, superCount};
        AtomicInteger call = new AtomicInteger();
        return (className, loader, matcher, hooker) -> counts[call.getAndIncrement()];
    }

    private static FreezeDecision decision(
            boolean allowNormal,
            boolean dryRun,
            boolean fullExemption,
            boolean customTiming) {
        FreezePolicy policy = new FreezePolicy(
                allowNormal,
                true,
                true,
                true,
                customTiming,
                5_000L,
                7_000L,
                fullExemption,
                false,
                false);
        return decisionFor(policy, dryRun, Map.of(PACKAGE, UID));
    }

    private static FreezeDecision blockedDecision(FreezeSource source) {
        FreezePolicy policy = new FreezePolicy(
                source != FreezeSource.NORMAL,
                source != FreezeSource.FAST,
                source != FreezeSource.SUPER,
                source != FreezeSource.PRELOAD,
                false,
                0L,
                0L,
                false,
                false,
                false);
        return decisionFor(policy, false, Map.of(PACKAGE, UID), source);
    }

    private static FreezeDecision allowedDecision(FreezeSource source) {
        return decisionFor(
                FreezePolicy.followSystem(), false, Map.of(PACKAGE, UID), source);
    }

    private static FreezeDecision decisionFor(
            FreezePolicy freezePolicy,
            boolean dryRun,
            Map<String, Integer> packageUids) {
        return decisionFor(freezePolicy, dryRun, packageUids, FreezeSource.NORMAL);
    }

    private static FreezeDecision decisionFor(
            FreezePolicy freezePolicy,
            boolean dryRun,
            Map<String, Integer> packageUids,
            FreezeSource source) {
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freezePolicy,
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());
        Map<String, AppResidencyPolicy> policies = packageUids.isEmpty()
                ? Map.of()
                : Map.of(PACKAGE, policy);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L,
                true,
                true,
                dryRun,
                2L,
                policies);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);
        return new ResidencyDecisionEngine(snapshot, index).decideFreeze(new FreezeContext(
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID),
                source,
                "hans_freeze",
                HookTargetIds.HANS_FREEZE_DISPATCH));
    }

    private static final class OriginalFailure extends Exception {}

    private static final class FakeChain implements XposedInterface.Chain {
        private final List<Object> args;
        private final HansFreezeHook.OriginalCall originalCall;
        private final Executable executable;
        private final Object thisObject;
        private final AtomicInteger proceedCalls = new AtomicInteger();

        private FakeChain(Object result, Object... args) {
            this(null, null, () -> result, args);
        }

        private FakeChain(
                Executable executable,
                Object thisObject,
                HansFreezeHook.OriginalCall originalCall,
                Object[] args) {
            this.executable = executable;
            this.thisObject = thisObject;
            this.originalCall = originalCall;
            this.args = Arrays.asList(args);
        }

        static FakeChain calling(
                HansFreezeHook.OriginalCall originalCall, Object... args) {
            return new FakeChain(null, null, originalCall, args);
        }

        static FakeChain calling(
                Executable executable,
                Object thisObject,
                HansFreezeHook.OriginalCall originalCall,
                Object... args) {
            return new FakeChain(executable, thisObject, originalCall, args);
        }

        int proceedCalls() { return proceedCalls.get(); }

        @Override public Executable getExecutable() { return executable; }
        @Override public Object getThisObject() { return thisObject; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }
        @Override public Object proceed() throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceed(Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceedWith(Object thisObject) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceedWith(Object thisObject, Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
    }

    private static final class HookedHansManager extends OplusHansManager {
        private static final Method TWO_ARGUMENTS;
        private static final Method THREE_ARGUMENTS;

        static {
            try {
                TWO_ARGUMENTS = OplusHansManager.class.getDeclaredMethod(
                        "hansFreeze", int.class, String.class);
                THREE_ARGUMENTS = OplusHansManager.class.getDeclaredMethod(
                        "hansFreeze", int.class, String.class, boolean.class);
            } catch (NoSuchMethodException error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private final HansFreezeHook hook;
        private FakeChain twoArgumentChain;
        private FakeChain threeArgumentChain;

        private HookedHansManager(HansFreezeHook hook) {
            this.hook = hook;
        }

        @Override
        public boolean hansFreeze(int uid, String reason) {
            twoArgumentChain = FakeChain.calling(
                    TWO_ARGUMENTS,
                    this,
                    () -> HookedHansManager.super.hansFreeze(uid, reason),
                    uid,
                    reason);
            return intercept(twoArgumentChain);
        }

        @Override
        public boolean hansFreeze(int uid, String reason, boolean ignoreImportance) {
            threeArgumentChain = FakeChain.calling(
                    THREE_ARGUMENTS,
                    this,
                    () -> HookedHansManager.super.hansFreeze(
                            uid, reason, ignoreImportance),
                    uid,
                    reason,
                    ignoreImportance);
            return intercept(threeArgumentChain);
        }

        int twoArgumentProceedCalls() {
            return twoArgumentChain == null ? 0 : twoArgumentChain.proceedCalls();
        }

        int threeArgumentProceedCalls() {
            return threeArgumentChain == null ? 0 : threeArgumentChain.proceedCalls();
        }

        private boolean intercept(FakeChain chain) {
            try {
                return (Boolean) hook.interceptHans(chain);
            } catch (Throwable error) {
                throw new AssertionError(error);
            }
        }
    }
}
