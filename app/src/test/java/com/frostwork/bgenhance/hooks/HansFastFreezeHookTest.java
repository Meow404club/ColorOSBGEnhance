package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.scene.HansSceneManager;
import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.BeforeClass;
import org.junit.Test;

public final class HansFastFreezeHookTest {
    private static final String PACKAGE = "com.example.fast.blocked";
    private static final String ALLOWED_PACKAGE = "com.example.fast.allowed";
    private static final String SHARED_PACKAGE = "com.example.fast.shared";
    private static final int UID = 10_941;
    private static final int ALLOWED_UID = 10_942;

    @BeforeClass
    public static void preparePackageAccessor() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
    }

    @Test
    public void installRequiresTwoUniqueHooksAndVerifiesTerminalCapability() {
        ExactInstaller installer = new ExactInstaller();
        RecordingDeoptimizer deoptimizer = new RecordingDeoptimizer(true, true);
        HansFastFreezeHook hook = new HansFastFreezeHook(
                target -> null,
                new RecordingDiagnostics(),
                installer,
                deoptimizer);

        assertEquals(
                "INSTALLED:entry=1,candidates=1,terminal=verified,deopt=2",
                hook.install(HansSceneManager.class.getClassLoader()));
        assertEquals(2, installer.calls.get());
        assertEquals(List.of(1, 1), installer.matches);
        assertEquals(2, deoptimizer.methods.size());
        assertTrue(java.lang.reflect.Modifier.isStatic(
                deoptimizer.methods.get(0).getModifiers()));
        assertEquals("enterFastFreezer", deoptimizer.methods.get(1).getName());
        assertEquals(HookTargetIds.HANS_FAST_FREEZE_DISPATCH, hook.hookTargetId());
    }

    @Test
    public void everyRequiredDeoptimizationMustSucceedBeforeHooksAreInstalled() {
        for (boolean[] results : new boolean[][] {
                {false, true}, {true, false}
        }) {
            AtomicInteger installs = new AtomicInteger();
            HansFastFreezeHook hook = new HansFastFreezeHook(
                    target -> null,
                    new RecordingDiagnostics(),
                    (className, loader, matcher, hooker) -> {
                        installs.incrementAndGet();
                        return 1;
                    },
                    new RecordingDeoptimizer(results));

            String status = hook.install(HansSceneManager.class.getClassLoader());

            assertTrue(status, status.startsWith("FAILED_FAIL_OPEN:DEOPTIMIZE_"));
            assertEquals(0, installs.get());
        }
    }

    @Test
    public void deoptimizationExceptionFailsOpenBeforeHookInstallation() {
        AtomicInteger installs = new AtomicInteger();
        HansFastFreezeHook hook = new HansFastFreezeHook(
                target -> null,
                new RecordingDiagnostics(),
                (className, loader, matcher, hooker) -> {
                    installs.incrementAndGet();
                    return 1;
                },
                method -> { throw new IllegalStateException("deoptimize"); });

        assertEquals(
                "FAILED_FAIL_OPEN:DEOPTIMIZATION",
                hook.install(HansSceneManager.class.getClassLoader()));
        assertEquals(0, installs.get());
    }

    @Test
    public void partialInstallationLeavesEveryInterceptorInactive() throws Throwable {
        for (int[] counts : new int[][] {
                {-1, 1}, {0, 1}, {2, 1}, {1, -1}, {1, 0}, {1, 2}
        }) {
            AtomicInteger decisions = new AtomicInteger();
            HansFastFreezeHook hook = new HansFastFreezeHook(
                    target -> {
                        decisions.incrementAndGet();
                        return blockedDecision(target, Map.of(PACKAGE, UID));
                    },
                    new RecordingDiagnostics(),
                    new CountInstaller(counts));

            String status = hook.install(HansSceneManager.class.getClassLoader());
            assertTrue(status, status.startsWith("FAILED_FAIL_OPEN:ENTRY="));

            Object scene = new HansSceneManager();
            ArrayList<Object> original = list(hansPackage(PACKAGE, UID, false));
            FakeChain candidates = FakeChain.returning(original, scene);
            FakeChain entry = FakeChain.calling(
                    scene, () -> hook.interceptCandidates(candidates));

            assertSame(original, hook.interceptEntry(entry));
            assertEquals(1, entry.proceedCalls());
            assertEquals(1, candidates.proceedCalls());
            assertEquals(0, decisions.get());
        }
    }

    @Test
    public void installerFailureLeavesPreviouslyAttachedInterceptorInactive()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger installs = new AtomicInteger();
        HansFastFreezeHook hook = new HansFastFreezeHook(
                target -> {
                    decisions.incrementAndGet();
                    return blockedDecision(target, Map.of(PACKAGE, UID));
                },
                new RecordingDiagnostics(),
                (className, loader, matcher, hooker) -> {
                    if (installs.incrementAndGet() == 2) {
                        throw new IllegalStateException("installer");
                    }
                    return 1;
                });

        assertThrows(
                IllegalStateException.class,
                () -> hook.install(HansSceneManager.class.getClassLoader()));

        Object scene = new HansSceneManager();
        ArrayList<Object> original = list(hansPackage(PACKAGE, UID, false));
        FakeChain candidates = FakeChain.returning(original, scene);
        FakeChain entry = FakeChain.calling(
                scene, () -> hook.interceptCandidates(candidates));
        assertSame(original, hook.interceptEntry(entry));
        assertEquals(0, decisions.get());
    }

    @Test
    public void matchingEntryFiltersOnlyExplicitlyBlockedCandidates() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansFastFreezeHook hook = activeHook(
                target -> PACKAGE.equals(target.packageName())
                        ? blockedDecision(target, Map.of(PACKAGE, UID))
                        : allowedDecision(target, Map.of(ALLOWED_PACKAGE, ALLOWED_UID)),
                diagnostics);
        Object scene = new HansSceneManager();
        OplusHansPackage blocked = hansPackage(PACKAGE, UID, false);
        OplusHansPackage allowed = hansPackage(ALLOWED_PACKAGE, ALLOWED_UID, false);
        ArrayList<Object> original = list(blocked, allowed);
        FakeChain candidates = FakeChain.returning(original, scene);
        FakeChain entry = FakeChain.calling(
                scene, () -> hook.interceptCandidates(candidates));

        Object result = hook.interceptEntry(entry);

        assertTrue(result instanceof ArrayList<?>);
        assertNotSame(original, result);
        assertEquals(List.of(allowed), result);
        assertEquals(List.of(blocked, allowed), original);
        assertEquals(1, entry.proceedCalls());
        assertEquals(1, candidates.proceedCalls());
        assertEquals(2, diagnostics.decisions.size());
        assertEquals(FeatureIds.HANS, diagnostics.decisions.get(0).event.feature);
        assertEquals(FreezeSource.FAST.ordinal(), diagnostics.decisions.get(0).event.code);
        assertEquals(HansFastFreezeHook.SCENE_MARKER,
                diagnostics.decisions.get(0).event.scene);
        assertEquals(HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
                diagnostics.decisions.get(0).decision.trace().hookTargetId());
    }

    @Test
    public void allBlockedCandidatesProduceEmptyDefensiveList() throws Throwable {
        HansFastFreezeHook hook = activeHook(
                target -> blockedDecision(target, Map.of(PACKAGE, UID)),
                new RecordingDiagnostics());
        Object scene = new HansSceneManager();
        OplusHansPackage blocked = hansPackage(PACKAGE, UID, false);
        ArrayList<Object> original = list(blocked);
        FakeChain candidates = FakeChain.returning(original, scene);
        FakeChain entry = FakeChain.calling(
                scene, () -> hook.interceptCandidates(candidates));

        Object result = hook.interceptEntry(entry);

        assertTrue(result instanceof ArrayList<?>);
        assertNotSame(original, result);
        assertTrue(((ArrayList<?>) result).isEmpty());
        assertEquals(List.of(blocked), original);
        assertEquals(1, candidates.proceedCalls());
    }

    @Test
    public void candidateFilteringRequiresTheSameSceneInstanceAndActiveFrame()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        HansFastFreezeHook hook = activeHook(target -> {
            decisions.incrementAndGet();
            return blockedDecision(target, Map.of(PACKAGE, UID));
        }, new RecordingDiagnostics());
        Object outerScene = new HansSceneManager();
        Object unrelatedScene = new HansSceneManager();
        ArrayList<Object> original = list(hansPackage(PACKAGE, UID, false));

        FakeChain outside = FakeChain.returning(original, outerScene);
        assertSame(original, hook.interceptCandidates(outside));

        FakeChain mismatched = FakeChain.returning(original, unrelatedScene);
        FakeChain entry = FakeChain.calling(
                outerScene, () -> hook.interceptCandidates(mismatched));
        assertSame(original, hook.interceptEntry(entry));

        assertEquals(0, decisions.get());
        assertEquals(1, outside.proceedCalls());
        assertEquals(1, mismatched.proceedCalls());
    }

    @Test
    public void nestedEntriesRestoreOuterSceneFrame() throws Throwable {
        HansFastFreezeHook hook = activeHook(
                target -> blockedDecision(target, Map.of(PACKAGE, UID)),
                new RecordingDiagnostics());
        Object outerScene = new HansSceneManager();
        Object innerScene = new HansSceneManager();
        ArrayList<Object> outerOriginal = list(hansPackage(PACKAGE, UID, false));
        ArrayList<Object> innerOriginal = list(hansPackage(PACKAGE, UID, false));
        AtomicReference<Object> innerResult = new AtomicReference<>();
        FakeChain outerCandidates = FakeChain.returning(outerOriginal, outerScene);
        FakeChain innerCandidates = FakeChain.returning(innerOriginal, innerScene);
        FakeChain innerEntry = FakeChain.calling(
                innerScene, () -> hook.interceptCandidates(innerCandidates));
        FakeChain outerEntry = FakeChain.calling(outerScene, () -> {
            innerResult.set(hook.interceptEntry(innerEntry));
            return hook.interceptCandidates(outerCandidates);
        });

        Object outerResult = hook.interceptEntry(outerEntry);

        assertTrue(((ArrayList<?>) innerResult.get()).isEmpty());
        assertTrue(((ArrayList<?>) outerResult).isEmpty());
        assertEquals(1, outerCandidates.proceedCalls());
        assertEquals(1, innerCandidates.proceedCalls());
    }

    @Test
    public void entryExceptionAndNullReceiverNeverLeakAFrame() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        HansFastFreezeHook hook = activeHook(target -> {
            decisions.incrementAndGet();
            return blockedDecision(target, Map.of(PACKAGE, UID));
        }, new RecordingDiagnostics());
        OriginalFailure failure = new OriginalFailure();
        FakeChain failedEntry = FakeChain.calling(
                new HansSceneManager(), () -> { throw failure; });

        assertSame(failure, assertThrows(
                OriginalFailure.class, () -> hook.interceptEntry(failedEntry)));

        ArrayList<Object> original = list(hansPackage(PACKAGE, UID, false));
        FakeChain afterFailure = FakeChain.returning(original, new HansSceneManager());
        assertSame(original, hook.interceptCandidates(afterFailure));

        FakeChain nullCandidates = FakeChain.returning(original, null);
        FakeChain nullEntry = FakeChain.calling(
                null, () -> hook.interceptCandidates(nullCandidates));
        assertSame(original, hook.interceptEntry(nullEntry));
        assertEquals(0, decisions.get());
    }

    @Test
    public void dryRunFollowSystemInvalidAndNullDecisionsRemainCandidates()
            throws Throwable {
        OplusHansPackage dryRun = hansPackage(PACKAGE, UID, false);
        OplusHansPackage followSystem = hansPackage(ALLOWED_PACKAGE, ALLOWED_UID, false);
        Object invalid = new Object();
        OplusHansPackage nullDecision = hansPackage("com.example.fast.null", 10_944, false);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansFastFreezeHook hook = activeHook(target -> {
            if (PACKAGE.equals(target.packageName())) {
                return dryRunDecision(target, Map.of(PACKAGE, UID));
            }
            if (ALLOWED_PACKAGE.equals(target.packageName())) {
                return allowedDecision(target, Map.of(ALLOWED_PACKAGE, ALLOWED_UID));
            }
            return null;
        }, diagnostics);
        ArrayList<Object> original = list(
                dryRun, followSystem, invalid, nullDecision);

        Object result = withinEntry(hook, new HansSceneManager(), original);

        assertSame(original, result);
        assertEquals(2, diagnostics.decisions.size());
        assertEquals(DecisionDisposition.DRY_RUN,
                diagnostics.decisions.get(0).decision.trace().disposition());
        assertEquals(2, diagnostics.failures.size());
    }

    @Test
    public void frozenSnapshotNeverSkipsCurrentUidPolicy() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        OplusHansPackage staleFrozenSnapshot = hansPackage(PACKAGE, UID, true);
        HansFastFreezeHook hook = activeHook(target -> {
            decisions.incrementAndGet();
            return blockedDecision(target, Map.of(PACKAGE, UID));
        }, new RecordingDiagnostics());

        Object result = withinEntry(
                hook,
                new HansSceneManager(),
                list(staleFrozenSnapshot));

        assertTrue(((ArrayList<?>) result).isEmpty());
        assertEquals(1, decisions.get());
    }

    @Test
    public void masterOffKeepsEveryCandidate() throws Throwable {
        OplusHansPackage candidate = hansPackage(PACKAGE, UID, false);
        ArrayList<Object> original = list(candidate);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansFastFreezeHook hook = activeHook(
                target -> masterOffDecision(target), diagnostics);

        assertSame(original, withinEntry(hook, new HansSceneManager(), original));
        assertEquals(1, diagnostics.decisions.size());
        assertEquals(DecisionDisposition.FAIL_OPEN,
                diagnostics.decisions.get(0).decision.trace().disposition());
        assertEquals(DecisionReason.MASTER_DISABLED,
                diagnostics.decisions.get(0).decision.trace().reason());
        assertEquals(FreezeDecision.Action.ALLOW_SYSTEM,
                diagnostics.decisions.get(0).decision.effectiveAction());
    }

    @Test
    public void unmanagedCandidateUsesLegacyGateAndFailsOpenWithoutConfig()
            throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansFastFreezeHook hook = activeHook(
                target -> unmanagedDecision(target), diagnostics);
        ArrayList<Object> original = list(hansPackage(PACKAGE, UID, false));

        assertSame(original, withinEntry(hook, new HansSceneManager(), original));
        assertEquals(1, diagnostics.legacy.size());
        assertFalse(diagnostics.legacy.get(0).blocked());
    }

    @Test
    public void sharedUidConflictAndUnmanagedSiblingFailOpenAtHookBoundary()
            throws Throwable {
        AppResidencyPolicy blocked = policy(PACKAGE, false);
        AppResidencyPolicy allowed = policy(SHARED_PACKAGE, true);
        RuntimeConfigSnapshot conflictSnapshot = RuntimeConfigSnapshot.available(
                1L,
                true,
                true,
                false,
                2L,
                Map.of(PACKAGE, blocked, SHARED_PACKAGE, allowed));
        ResidencyIdentityIndex conflictIndex = ResidencyIdentityIndex.build(
                conflictSnapshot, Map.of(PACKAGE, UID, SHARED_PACKAGE, UID));
        RecordingDiagnostics conflictDiagnostics = new RecordingDiagnostics();
        HansFastFreezeHook conflictHook = activeHook(
                target -> decide(conflictSnapshot, conflictIndex, target),
                conflictDiagnostics);
        ArrayList<Object> conflictOriginal = list(hansPackage(PACKAGE, UID, false));

        assertSame(conflictOriginal,
                withinEntry(conflictHook, new HansSceneManager(), conflictOriginal));
        assertEquals(DecisionDisposition.FAIL_OPEN,
                conflictDiagnostics.decisions.get(0).decision.trace().disposition());

        RuntimeConfigSnapshot siblingSnapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, Map.of(PACKAGE, blocked));
        ResidencyIdentityIndex siblingIndex = ResidencyIdentityIndex.build(
                siblingSnapshot, Map.of(PACKAGE, UID, SHARED_PACKAGE, UID));
        RecordingDiagnostics siblingDiagnostics = new RecordingDiagnostics();
        HansFastFreezeHook siblingHook = activeHook(
                target -> decide(siblingSnapshot, siblingIndex, target),
                siblingDiagnostics);
        ArrayList<Object> siblingOriginal = list(hansPackage(PACKAGE, UID, false));

        assertSame(siblingOriginal,
                withinEntry(siblingHook, new HansSceneManager(), siblingOriginal));
        assertEquals(DecisionDisposition.FAIL_OPEN,
                siblingDiagnostics.decisions.get(0).decision.trace().disposition());
    }

    @Test
    public void identicalSharedUidPoliciesCanStillBlockFastFreeze() throws Throwable {
        AppResidencyPolicy first = policy(PACKAGE, false);
        AppResidencyPolicy second = policy(SHARED_PACKAGE, false);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L,
                true,
                true,
                false,
                2L,
                Map.of(PACKAGE, first, SHARED_PACKAGE, second));
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(PACKAGE, UID, SHARED_PACKAGE, UID));
        HansFastFreezeHook hook = activeHook(
                target -> decide(snapshot, index, target),
                new RecordingDiagnostics());

        Object result = withinEntry(
                hook,
                new HansSceneManager(),
                list(hansPackage(PACKAGE, UID, false)));

        assertTrue(((ArrayList<?>) result).isEmpty());
    }

    @Test
    public void decisionAndDiagnosticFailuresCannotChangeVendorBehavior()
            throws Throwable {
        RecordingDiagnostics decisionDiagnostics = new RecordingDiagnostics();
        HansFastFreezeHook failedDecision = activeHook(
                target -> { throw new IllegalStateException("decision"); },
                decisionDiagnostics);
        ArrayList<Object> original = list(hansPackage(PACKAGE, UID, false));
        assertSame(original,
                withinEntry(failedDecision, new HansSceneManager(), original));
        assertEquals(1, decisionDiagnostics.failures.size());

        HansFastFreezeHook failedDiagnostics = activeHook(
                target -> blockedDecision(target, Map.of(PACKAGE, UID)),
                new ThrowingDiagnostics());
        Object result = withinEntry(
                failedDiagnostics,
                new HansSceneManager(),
                list(hansPackage(PACKAGE, UID, false)));
        assertTrue(((ArrayList<?>) result).isEmpty());
    }

    @Test
    public void candidateIterationFailureReturnsOriginalList() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansFastFreezeHook hook = activeHook(
                target -> blockedDecision(target, Map.of(PACKAGE, UID)), diagnostics);
        ArrayList<Object> original = new ThrowingSizeList();
        Object scene = new HansSceneManager();

        assertSame(original, withinEntry(hook, scene, original));
        assertEquals(1, diagnostics.failures.size());
    }

    @Test
    public void originalCandidateHelperExceptionPropagatesExactlyOnce() {
        HansFastFreezeHook hook = activeHook(
                target -> blockedDecision(target, Map.of(PACKAGE, UID)),
                new RecordingDiagnostics());
        Object scene = new HansSceneManager();
        OriginalFailure failure = new OriginalFailure();
        FakeChain candidates = FakeChain.calling(scene, () -> { throw failure; });
        FakeChain entry = FakeChain.calling(
                scene, () -> hook.interceptCandidates(candidates));

        assertSame(failure, assertThrows(
                OriginalFailure.class, () -> hook.interceptEntry(entry)));
        assertEquals(1, entry.proceedCalls());
        assertEquals(1, candidates.proceedCalls());
    }

    private static HansFastFreezeHook activeHook(
            HansFastFreezeHook.FreezeDecisionResolver resolver,
            HansFastFreezeHook.DiagnosticsRecorder diagnostics) {
        return new HansFastFreezeHook(resolver, diagnostics, true);
    }

    private static Object withinEntry(
            HansFastFreezeHook hook, Object scene, ArrayList<Object> original)
            throws Throwable {
        FakeChain candidates = FakeChain.returning(original, scene);
        FakeChain entry = FakeChain.calling(
                scene, () -> hook.interceptCandidates(candidates));
        Object result = hook.interceptEntry(entry);
        assertEquals(1, entry.proceedCalls());
        assertEquals(1, candidates.proceedCalls());
        return result;
    }

    private static FreezeDecision blockedDecision(
            ResidencyTarget target, Map<String, Integer> packageUids) {
        return decisionFor(target, Map.of(
                target.packageName(), policy(target.packageName(), false)),
                packageUids,
                false);
    }

    private static FreezeDecision allowedDecision(
            ResidencyTarget target, Map<String, Integer> packageUids) {
        return decisionFor(target, Map.of(
                target.packageName(), policy(target.packageName(), true)),
                packageUids,
                false);
    }

    private static FreezeDecision dryRunDecision(
            ResidencyTarget target, Map<String, Integer> packageUids) {
        return decisionFor(target, Map.of(
                target.packageName(), policy(target.packageName(), false)),
                packageUids,
                true);
    }

    private static FreezeDecision unmanagedDecision(ResidencyTarget target) {
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, Map.of());
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(target.packageName(), target.uid()));
        return decide(snapshot, index, target);
    }

    private static FreezeDecision masterOffDecision(ResidencyTarget target) {
        AppResidencyPolicy policy = policy(target.packageName(), false);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, false, true, false, 2L, Map.of(target.packageName(), policy));
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(target.packageName(), target.uid()));
        return decide(snapshot, index, target);
    }

    private static FreezeDecision decisionFor(
            ResidencyTarget target,
            Map<String, AppResidencyPolicy> policies,
            Map<String, Integer> packageUids,
            boolean dryRun) {
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, policies);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);
        return decide(snapshot, index, target);
    }

    private static FreezeDecision decide(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            ResidencyTarget target) {
        return new ResidencyDecisionEngine(snapshot, index).decideFreeze(
                new FreezeContext(
                        target,
                        FreezeSource.FAST,
                        FeatureIds.HANS,
                        HookTargetIds.HANS_FAST_FREEZE_DISPATCH));
    }

    private static AppResidencyPolicy policy(String packageName, boolean allowFast) {
        AppResidencyPolicy base = ResidencyPresets.create(
                packageName, ResidencyMode.CUSTOM);
        FreezePolicy follow = FreezePolicy.followSystem();
        FreezePolicy freeze = new FreezePolicy(
                follow.allowNormalFreeze(),
                allowFast,
                follow.allowSuperFreeze(),
                follow.allowPreloadFreeze(),
                follow.customTiming(),
                follow.rToMDelayMs(),
                follow.mToFDelayMs(),
                follow.fullExemption(),
                follow.coreExemption(),
                follow.lcdOnExemption());
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freeze,
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());
    }

    private static OplusHansPackage hansPackage(
            String packageName, int uid, boolean frozen) {
        return new OplusHansPackage(uid, packageName, frozen, false, new Object());
    }

    @SafeVarargs
    private static <T> ArrayList<T> list(T... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static final class RecordingDiagnostics
            implements HansFastFreezeHook.DiagnosticsRecorder {
        private final List<DecisionRecord> decisions = new ArrayList<>();
        private final List<Decision.LockedResult> legacy = new ArrayList<>();
        private final List<HookEvent> failures = new ArrayList<>();

        @Override
        public void recordDecision(HookEvent event, FreezeDecision decision) {
            decisions.add(new DecisionRecord(event, decision));
        }

        @Override
        public void recordLegacy(HookEvent event, Decision.LockedResult result) {
            legacy.add(result);
        }

        @Override
        public void recordFailure(HookEvent event) {
            failures.add(event);
        }
    }

    private static final class ThrowingDiagnostics
            implements HansFastFreezeHook.DiagnosticsRecorder {
        @Override
        public void recordDecision(HookEvent event, FreezeDecision decision) {
            throw new AssertionError("diagnostics unavailable");
        }

        @Override
        public void recordLegacy(HookEvent event, Decision.LockedResult result) {
            throw new AssertionError("diagnostics unavailable");
        }

        @Override
        public void recordFailure(HookEvent event) {
            throw new AssertionError("diagnostics unavailable");
        }
    }

    private static final class DecisionRecord {
        private final HookEvent event;
        private final FreezeDecision decision;

        private DecisionRecord(HookEvent event, FreezeDecision decision) {
            this.event = event;
            this.decision = decision;
        }
    }

    private static final class ThrowingSizeList extends ArrayList<Object> {
        @Override
        public int size() {
            throw new IllegalStateException("size unavailable");
        }
    }

    private static final class ExactInstaller implements HansFastFreezeHook.HookInstaller {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Integer> matches = new ArrayList<>();

        @Override
        public int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker) {
            calls.incrementAndGet();
            try {
                int count = 0;
                for (Method method : Class.forName(
                        className, false, loader).getDeclaredMethods()) {
                    if (matcher.matches(method)) count++;
                }
                matches.add(count);
                return count;
            } catch (ClassNotFoundException error) {
                throw new AssertionError(error);
            }
        }
    }

    private static final class CountInstaller implements HansFastFreezeHook.HookInstaller {
        private final int[] counts;
        private final AtomicInteger call = new AtomicInteger();

        private CountInstaller(int... counts) {
            this.counts = counts;
        }

        @Override
        public int hookMatching(
                String className,
                ClassLoader loader,
                HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker) {
            return counts[call.getAndIncrement()];
        }
    }

    private static final class RecordingDeoptimizer
            implements HansFastFreezeHook.MethodDeoptimizer {
        private final boolean[] results;
        private final AtomicInteger call = new AtomicInteger();
        private final List<Method> methods = new ArrayList<>();

        private RecordingDeoptimizer(boolean... results) {
            this.results = results;
        }

        @Override
        public boolean deoptimize(Method method) {
            methods.add(method);
            return results[call.getAndIncrement()];
        }
    }

    private interface OriginalCall {
        Object proceed() throws Throwable;
    }

    private static final class OriginalFailure extends Exception {}

    private static final class FakeChain implements XposedInterface.Chain {
        private final Object thisObject;
        private final OriginalCall originalCall;
        private final List<Object> args;
        private final AtomicInteger proceedCalls = new AtomicInteger();

        private FakeChain(
                Object thisObject, OriginalCall originalCall, Object... args) {
            this.thisObject = thisObject;
            this.originalCall = originalCall;
            this.args = Arrays.asList(args);
        }

        private static FakeChain returning(Object result, Object thisObject) {
            return new FakeChain(thisObject, () -> result);
        }

        private static FakeChain calling(Object thisObject, OriginalCall originalCall) {
            return new FakeChain(thisObject, originalCall);
        }

        private int proceedCalls() {
            return proceedCalls.get();
        }

        @Override public Executable getExecutable() { return null; }
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
        @Override public Object proceedWith(Object nextThisObject) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceedWith(
                Object nextThisObject, Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
    }
}
