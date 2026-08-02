package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.states.action.Action;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.hooks.hans.HansActionAdapter;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.hooks.hans.HansResourceRuntime;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.Test;

public final class HansResourceHookTest {
    private static final String PACKAGE = "com.example.resource.target";
    private static final String SHARED_PACKAGE = "com.example.resource.shared";
    private static final int UID = 10_811;
    private static final Object ORIGINAL = new Object();

    @BeforeClass
    public static void preparePackageAccessors() {
        assertTrue(HansActionAdapter.prepare(Action.class, OplusHansPackage.class));
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
    }

    @Test
    public void actionCapabilitiesMapOneToOneToEveryResource() {
        EnumMap<HansResourceRuntime.Capability, ResourceType> expected =
                new EnumMap<>(HansResourceRuntime.Capability.class);
        expected.put(HansResourceRuntime.Capability.PROXY_SERVICE, ResourceType.SERVICE);
        expected.put(
                HansResourceRuntime.Capability.PROXY_BROADCAST, ResourceType.BROADCAST);
        expected.put(HansResourceRuntime.Capability.PROXY_JOB, ResourceType.JOB);
        expected.put(HansResourceRuntime.Capability.PROXY_SENSOR, ResourceType.SENSOR);
        expected.put(HansResourceRuntime.Capability.PROXY_BINDER, ResourceType.BINDER);
        expected.put(HansResourceRuntime.Capability.MANAGE_ALARM, ResourceType.ALARM);
        expected.put(
                HansResourceRuntime.Capability.PROXY_WAKE_LOCK, ResourceType.WAKE_LOCK);
        expected.put(HansResourceRuntime.Capability.PROXY_GPS, ResourceType.GPS);
        expected.put(HansResourceRuntime.Capability.PROXY_AUDIO, ResourceType.AUDIO);
        expected.put(
                HansResourceRuntime.Capability.PROXY_BT_SCAN,
                ResourceType.BLUETOOTH_SCAN);

        EnumSet<ResourceType> actualTypes = EnumSet.noneOf(ResourceType.class);
        for (Map.Entry<HansResourceRuntime.Capability, ResourceType> entry
                : expected.entrySet()) {
            ResourceType actual = HansResourceHook.resourceTypeFor(entry.getKey());
            assertEquals(entry.getValue(), actual);
            assertTrue(actualTypes.add(actual));
        }

        assertEquals(EnumSet.allOf(ResourceType.class), actualTypes);
        assertNull(HansResourceHook.resourceTypeFor(
                HansResourceRuntime.Capability.FIREWALL));
    }

    @Test
    public void diagnosticCodesAreExplicitStableAndUnique() {
        Set<Integer> codes = new HashSet<>();
        int expected = 1;
        for (ResourceType type : ResourceType.values()) {
            int code = HansResourceHook.actionCodeFor(type);
            assertEquals(expected++, code);
            assertTrue(codes.add(code));
        }
        assertEquals(100, HansResourceHook.NETWORK_ACTION_CODE);
        assertTrue(codes.add(HansResourceHook.NETWORK_ACTION_CODE));
        assertEquals(-1, HansResourceHook.actionCodeFor(null));
        assertFalse(codes.contains(HansResourceHook.UNKNOWN_ACTION_CODE));
    }

    @Test
    public void everyKeptActionShortCircuitsBeforeVendorSideEffects() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = hook(
                allKeptPolicy(), false, packageUids(), diagnostics);
        ConcreteAction action = action();

        for (ResourceType type : ResourceType.values()) {
            FakeChain chain = FakeChain.returning(ORIGINAL, action);

            assertNull(hook.interceptAction(chain, type));
            assertEquals(0, chain.proceedCalls());
        }

        assertEquals(ResourceType.values().length, diagnostics.decisions.size());
        for (int index = 0; index < ResourceType.values().length; index++) {
            ResourceType type = ResourceType.values()[index];
            DecisionRecord record = diagnostics.decisions.get(index);
            assertEquals(FeatureIds.HANS_RESOURCE, record.event.feature);
            assertEquals(HansResourceHook.actionCodeFor(type), record.event.code);
            assertEquals(UID, record.event.uid);
            assertEquals(PACKAGE, record.event.packageName);
            assertEquals(ResourceDecision.Action.KEEP_RESOURCE,
                    record.decision.effectiveAction());
            assertEquals(FeatureIds.HANS_RESOURCE,
                    record.decision.trace().featureId());
            assertEquals(HookTargetIds.HANS_RESOURCE_DISPATCH,
                    record.decision.trace().hookTargetId());
        }
        assertTrue(diagnostics.failures.isEmpty());
    }

    @Test
    public void followSystemAndDryRunCallActionOriginalExactlyOnce() throws Throwable {
        RecordingDiagnostics passedDiagnostics = new RecordingDiagnostics();
        HansResourceHook passed = hook(
                ResourcePolicy.followSystem(), false, packageUids(), passedDiagnostics);
        FakeChain passedChain = FakeChain.returning(ORIGINAL, action());

        assertSame(ORIGINAL, passed.interceptAction(passedChain, ResourceType.AUDIO));
        assertEquals(1, passedChain.proceedCalls());
        assertEquals(ResourceDecision.Action.ALLOW_SYSTEM,
                passedDiagnostics.decisions.get(0).decision.effectiveAction());

        RecordingDiagnostics dryRunDiagnostics = new RecordingDiagnostics();
        HansResourceHook dryRun = hook(
                allKeptPolicy(), true, packageUids(), dryRunDiagnostics);
        FakeChain dryRunChain = FakeChain.returning(ORIGINAL, action());

        assertSame(ORIGINAL, dryRun.interceptAction(dryRunChain, ResourceType.AUDIO));
        assertEquals(1, dryRunChain.proceedCalls());
        ResourceDecision dryRunDecision = dryRunDiagnostics.decisions.get(0).decision;
        assertEquals(DecisionDisposition.DRY_RUN, dryRunDecision.trace().disposition());
        assertEquals(ResourceDecision.Action.KEEP_RESOURCE, dryRunDecision.policyAction());
        assertEquals(ResourceDecision.Action.ALLOW_SYSTEM, dryRunDecision.effectiveAction());
    }

    @Test
    public void inactiveHookPassesWithoutResolvingOrReporting() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    throw new AssertionError("inactive hook evaluated policy");
                },
                diagnostics,
                false);
        FakeChain chain = FakeChain.returning(ORIGINAL, new Object());

        assertSame(ORIGINAL, hook.interceptAction(chain, ResourceType.SERVICE));

        assertEquals(1, chain.proceedCalls());
        assertEquals(0, decisions.get());
        assertTrue(diagnostics.decisions.isEmpty());
        assertTrue(diagnostics.failures.isEmpty());
    }

    @Test
    public void actionIdentityAndDecisionFailuresAreFailOpen() throws Throwable {
        RecordingDiagnostics identityDiagnostics = new RecordingDiagnostics();
        HansResourceHook identityHook = new HansResourceHook(
                (target, kind, type) -> {
                    throw new AssertionError("invalid identity reached policy");
                },
                identityDiagnostics);
        FakeChain identityChain = FakeChain.returning(ORIGINAL, new Object());

        assertSame(ORIGINAL,
                identityHook.interceptAction(identityChain, ResourceType.SENSOR));
        assertEquals(1, identityChain.proceedCalls());
        assertEquals(1, identityDiagnostics.failures.size());
        assertEquals(ResidencyTarget.UNKNOWN_UID,
                identityDiagnostics.failures.get(0).uid);

        RecordingDiagnostics decisionDiagnostics = new RecordingDiagnostics();
        HansResourceHook decisionHook = new HansResourceHook(
                (target, kind, type) -> { throw new IllegalStateException("decision"); },
                decisionDiagnostics);
        FakeChain decisionChain = FakeChain.returning(ORIGINAL, action());

        assertSame(ORIGINAL,
                decisionHook.interceptAction(decisionChain, ResourceType.SENSOR));
        assertEquals(1, decisionChain.proceedCalls());
        assertEquals(1, decisionDiagnostics.failures.size());

        RecordingDiagnostics nullDiagnostics = new RecordingDiagnostics();
        HansResourceHook nullHook = new HansResourceHook(
                (target, kind, type) -> null, nullDiagnostics);
        FakeChain nullChain = FakeChain.returning(ORIGINAL, action());

        assertSame(ORIGINAL,
                nullHook.interceptAction(nullChain, ResourceType.SENSOR));
        assertEquals(1, nullChain.proceedCalls());
        assertEquals(1, nullDiagnostics.failures.size());
    }

    @Test
    public void directWakeLockManagerKeepReturnsTrueAndPassPreservesVendorResult()
            throws Throwable {
        RecordingDiagnostics keptDiagnostics = new RecordingDiagnostics();
        HansResourceHook kept = hook(
                allKeptPolicy(), false, packageUids(), keptDiagnostics);
        FakeChain keptChain = FakeChain.returning(Boolean.FALSE, null, hansPackage());

        assertEquals(Boolean.TRUE, kept.interceptWakeLockManager(keptChain));
        assertEquals(0, keptChain.proceedCalls());
        assertEquals(HansResourceHook.actionCodeFor(ResourceType.WAKE_LOCK),
                keptDiagnostics.decisions.get(0).event.code);

        RecordingDiagnostics passedDiagnostics = new RecordingDiagnostics();
        HansResourceHook passed = hook(
                ResourcePolicy.followSystem(), false, packageUids(), passedDiagnostics);
        FakeChain passedChain = FakeChain.returning(Boolean.FALSE, null, hansPackage());

        assertEquals(Boolean.FALSE, passed.interceptWakeLockManager(passedChain));
        assertEquals(1, passedChain.proceedCalls());
        assertEquals(1, passedDiagnostics.decisions.size());
    }

    @Test
    public void actionToManagerWakeLockChainDecidesAndReportsOnlyOnce() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            ResourcePolicy.followSystem(),
                            false,
                            packageUids());
                },
                diagnostics);
        OplusHansPackage hansPackage = hansPackage();
        FakeChain manager = FakeChain.returning(Boolean.FALSE, null, hansPackage);
        FakeChain action = FakeChain.calling(
                new ConcreteAction(hansPackage),
                () -> hook.interceptWakeLockManager(manager));

        assertEquals(Boolean.FALSE,
                hook.interceptAction(action, ResourceType.WAKE_LOCK));

        assertEquals(1, action.proceedCalls());
        assertEquals(1, manager.proceedCalls());
        assertEquals(1, decisions.get());
        assertEquals(1, diagnostics.decisions.size());
        assertTrue(diagnostics.failures.isEmpty());
    }

    @Test
    public void unknownWakeLockActionKeepsNestedManagerFailOpenWithoutDuplicateReport()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            allKeptPolicy(),
                            false,
                            packageUids());
                },
                diagnostics);
        FakeChain manager = FakeChain.returning(Boolean.FALSE, null, hansPackage());
        FakeChain action = FakeChain.calling(
                new Object(), () -> hook.interceptWakeLockManager(manager));

        assertEquals(Boolean.FALSE,
                hook.interceptAction(action, ResourceType.WAKE_LOCK));

        assertEquals(1, action.proceedCalls());
        assertEquals(1, manager.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(1, diagnostics.failures.size());
        assertTrue(diagnostics.decisions.isEmpty());
    }

    @Test
    public void wakeLockActionDecisionFailureKeepsNestedManagerFailOpenOnce()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    throw new IllegalStateException("decision");
                },
                diagnostics);
        OplusHansPackage hansPackage = hansPackage();
        FakeChain manager = FakeChain.returning(Boolean.FALSE, null, hansPackage);
        FakeChain action = FakeChain.calling(
                new ConcreteAction(hansPackage),
                () -> hook.interceptWakeLockManager(manager));

        assertEquals(Boolean.FALSE,
                hook.interceptAction(action, ResourceType.WAKE_LOCK));

        assertEquals(1, decisions.get());
        assertEquals(1, diagnostics.failures.size());
        assertTrue(diagnostics.decisions.isEmpty());
        assertEquals(1, action.proceedCalls());
        assertEquals(1, manager.proceedCalls());
    }

    @Test
    public void sameUidDifferentWakeLockObjectIsNotMistakenForNestedCall()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            ResourcePolicy.followSystem(),
                            false,
                            packageUids());
                },
                diagnostics);
        OplusHansPackage actionPackage = hansPackage();
        OplusHansPackage independentPackage = hansPackage();
        FakeChain manager = FakeChain.returning(
                Boolean.FALSE, null, independentPackage);
        FakeChain action = FakeChain.calling(
                new ConcreteAction(actionPackage),
                () -> hook.interceptWakeLockManager(manager));

        assertEquals(Boolean.FALSE,
                hook.interceptAction(action, ResourceType.WAKE_LOCK));

        assertEquals(2, decisions.get());
        assertEquals(2, diagnostics.decisions.size());
        assertEquals(1, action.proceedCalls());
        assertEquals(1, manager.proceedCalls());
    }

    @Test
    public void nestedWakeLockActionsRestoreOuterObjectIdentityFrame() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            ResourcePolicy.followSystem(),
                            false,
                            packageUids());
                },
                diagnostics);
        OplusHansPackage outerPackage = hansPackage();
        OplusHansPackage innerPackage = hansPackage();
        FakeChain outerManager = FakeChain.returning(
                Boolean.FALSE, null, outerPackage);
        FakeChain innerManager = FakeChain.returning(
                Boolean.FALSE, null, innerPackage);
        FakeChain innerAction = FakeChain.calling(
                new ConcreteAction(innerPackage),
                () -> hook.interceptWakeLockManager(innerManager));
        FakeChain outerAction = FakeChain.calling(
                new ConcreteAction(outerPackage),
                () -> {
                    assertEquals(Boolean.FALSE, hook.interceptAction(
                            innerAction, ResourceType.WAKE_LOCK));
                    return hook.interceptWakeLockManager(outerManager);
                });

        assertEquals(Boolean.FALSE,
                hook.interceptAction(outerAction, ResourceType.WAKE_LOCK));

        assertEquals(2, decisions.get());
        assertEquals(2, diagnostics.decisions.size());
        assertEquals(1, outerAction.proceedCalls());
        assertEquals(1, innerAction.proceedCalls());
        assertEquals(1, outerManager.proceedCalls());
        assertEquals(1, innerManager.proceedCalls());
    }

    @Test
    public void wakeLockFrameIsRestoredWhenActionOriginalThrows() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            ResourcePolicy.followSystem(),
                            false,
                            packageUids());
                },
                diagnostics);
        OriginalFailure failure = new OriginalFailure();
        FakeChain action = FakeChain.calling(action(), () -> { throw failure; });

        assertSame(failure, assertThrows(
                OriginalFailure.class,
                () -> hook.interceptAction(action, ResourceType.WAKE_LOCK)));

        FakeChain manager = FakeChain.returning(Boolean.FALSE, null, hansPackage());
        assertEquals(Boolean.FALSE, hook.interceptWakeLockManager(manager));
        assertEquals(2, decisions.get());
        assertEquals(2, diagnostics.decisions.size());
        assertEquals(1, manager.proceedCalls());
    }

    @Test
    public void firewallAlwaysPassesAllowAndOnlyBlocksVerifiedDeny() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            allKeptPolicy(),
                            false,
                            packageUids());
                },
                diagnostics);

        FakeChain allow = FakeChain.returning(
                ORIGINAL, null, null, Boolean.TRUE, 1, 2);
        assertSame(ORIGINAL, hook.interceptFirewall(allow));
        assertEquals(1, allow.proceedCalls());
        assertEquals(0, decisions.get());
        assertTrue(diagnostics.decisions.isEmpty());
        assertTrue(diagnostics.failures.isEmpty());

        FakeChain deny = FakeChain.returning(
                ORIGINAL, null, hansPackage(), Boolean.FALSE, 1, 2);
        assertNull(hook.interceptFirewall(deny));
        assertEquals(0, deny.proceedCalls());
        assertEquals(1, decisions.get());
        assertEquals(HansResourceHook.NETWORK_ACTION_CODE,
                diagnostics.decisions.get(0).event.code);
    }

    @Test
    public void firewallMalformedAllowAndPackageResolutionFailuresAreFailOpen()
            throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = hook(
                allKeptPolicy(), false, packageUids(), diagnostics);

        FakeChain malformed = FakeChain.returning(
                ORIGINAL, null, hansPackage(), "false", 1, 2);
        assertSame(ORIGINAL, hook.interceptFirewall(malformed));
        assertEquals(1, malformed.proceedCalls());

        FakeChain missingPackage = FakeChain.returning(
                ORIGINAL, null, null, Boolean.FALSE, 1, 2);
        assertSame(ORIGINAL, hook.interceptFirewall(missingPackage));
        assertEquals(1, missingPackage.proceedCalls());

        assertEquals(2, diagnostics.failures.size());
        assertTrue(diagnostics.decisions.isEmpty());
    }

    @Test
    public void networkPassThroughPreservesOriginalExceptionAndCallsOnce() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = hook(
                ResourcePolicy.followSystem(), false, packageUids(), diagnostics);
        OriginalFailure firewallFailure = new OriginalFailure();
        FakeChain firewall = new FakeChain(
                null,
                () -> { throw firewallFailure; },
                hansPackage(), Boolean.FALSE, 1, 2);

        assertSame(firewallFailure, assertThrows(
                OriginalFailure.class, () -> hook.interceptFirewall(firewall)));
        assertEquals(1, firewall.proceedCalls());

        OriginalFailure rstateFailure = new OriginalFailure();
        FakeChain rstate = new FakeChain(
                null,
                () -> { throw rstateFailure; },
                "audio", UID, PACKAGE);

        assertSame(rstateFailure, assertThrows(
                OriginalFailure.class, () -> hook.interceptRstateNetwork(rstate)));
        assertEquals(1, rstate.proceedCalls());
        assertEquals(2, diagnostics.decisions.size());
    }

    @Test
    public void rstateKeepReturnsFalseAndPassPreservesVendorBoolean() throws Throwable {
        RecordingDiagnostics keptDiagnostics = new RecordingDiagnostics();
        HansResourceHook kept = hook(
                allKeptPolicy(), false, packageUids(), keptDiagnostics);
        FakeChain keptChain = FakeChain.returning(
                Boolean.TRUE, null, "audio", UID, PACKAGE);

        assertEquals(Boolean.FALSE, kept.interceptRstateNetwork(keptChain));
        assertEquals(0, keptChain.proceedCalls());
        assertEquals(HansResourceHook.NETWORK_ACTION_CODE,
                keptDiagnostics.decisions.get(0).event.code);

        RecordingDiagnostics passedDiagnostics = new RecordingDiagnostics();
        HansResourceHook passed = hook(
                ResourcePolicy.followSystem(), false, packageUids(), passedDiagnostics);
        FakeChain passedChain = FakeChain.returning(
                Boolean.TRUE, null, "audio", UID, PACKAGE);

        assertEquals(Boolean.TRUE, passed.interceptRstateNetwork(passedChain));
        assertEquals(1, passedChain.proceedCalls());
        assertEquals(1, passedDiagnostics.decisions.size());
    }

    @Test
    public void rstateUsesUidWhenPackageHintIsNullAndRejectsMalformedIdentity()
            throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = hook(
                allKeptPolicy(), false, packageUids(), diagnostics);

        FakeChain uidOnly = FakeChain.returning(
                Boolean.TRUE, null, "audio", UID, null);
        assertEquals(Boolean.FALSE, hook.interceptRstateNetwork(uidOnly));
        assertEquals(0, uidOnly.proceedCalls());
        assertEquals(UID, diagnostics.decisions.get(0).event.uid);

        FakeChain invalidUid = FakeChain.returning(
                Boolean.TRUE, null, "audio", -1, PACKAGE);
        assertEquals(Boolean.TRUE, hook.interceptRstateNetwork(invalidUid));
        assertEquals(1, invalidUid.proceedCalls());

        FakeChain wrongUidType = FakeChain.returning(
                Boolean.TRUE, null, "audio", Long.valueOf(UID), PACKAGE);
        assertEquals(Boolean.TRUE, hook.interceptRstateNetwork(wrongUidType));
        assertEquals(1, wrongUidType.proceedCalls());
        assertEquals(2, diagnostics.failures.size());
    }

    @Test
    public void sharedUidResourceConflictFailsOpenAtHookBoundary() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        Map<String, AppResidencyPolicy> policies = Map.of(
                PACKAGE, policy(PACKAGE, allKeptPolicy()),
                SHARED_PACKAGE, policy(SHARED_PACKAGE, ResourcePolicy.followSystem()));
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, policies);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(PACKAGE, UID, SHARED_PACKAGE, UID));
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> decide(snapshot, index, target, kind, type),
                diagnostics);
        FakeChain chain = FakeChain.returning(ORIGINAL, action());

        assertSame(ORIGINAL, hook.interceptAction(chain, ResourceType.AUDIO));

        assertEquals(1, chain.proceedCalls());
        ResourceDecision decision = diagnostics.decisions.get(0).decision;
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(ResourceDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
    }

    @Test
    public void unrelatedSharedUidResourceDifferenceDoesNotCreateFalseConflict()
            throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        Map<String, AppResidencyPolicy> policies = Map.of(
                PACKAGE,
                policy(PACKAGE, new ResourcePolicy(
                        false, EnumSet.of(ResourceType.AUDIO))),
                SHARED_PACKAGE,
                policy(SHARED_PACKAGE, new ResourcePolicy(
                        false, EnumSet.of(ResourceType.AUDIO, ResourceType.GPS))));
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, policies);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(PACKAGE, UID, SHARED_PACKAGE, UID));
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> decide(snapshot, index, target, kind, type),
                diagnostics);
        FakeChain chain = FakeChain.returning(ORIGINAL, action());

        assertNull(hook.interceptAction(chain, ResourceType.AUDIO));

        assertEquals(0, chain.proceedCalls());
        assertEquals(ResourceDecision.Action.KEEP_RESOURCE,
                diagnostics.decisions.get(0).decision.effectiveAction());
    }

    @Test
    public void rstateUidPackageMismatchFailsOpenAndPreservesVendorResult()
            throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        Map<String, AppResidencyPolicy> policies = Map.of(
                PACKAGE, policy(PACKAGE, allKeptPolicy()),
                SHARED_PACKAGE, policy(SHARED_PACKAGE, allKeptPolicy()));
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, policies);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(PACKAGE, UID, SHARED_PACKAGE, UID + 1));
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> decide(snapshot, index, target, kind, type),
                diagnostics);
        FakeChain chain = FakeChain.returning(
                Boolean.TRUE, null, "audio", UID, SHARED_PACKAGE);

        assertEquals(Boolean.TRUE, hook.interceptRstateNetwork(chain));

        assertEquals(1, chain.proceedCalls());
        assertEquals(DecisionDisposition.FAIL_OPEN,
                diagnostics.decisions.get(0).decision.trace().disposition());
    }

    @Test
    public void diagnosticsFailuresNeverChangeResourceOutcome() throws Throwable {
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> decision(
                        target,
                        kind,
                        type,
                        allKeptPolicy(),
                        false,
                        packageUids()),
                new HansResourceHook.ResourceDiagnosticsRecorder() {
                    @Override
                    public void recordDecision(HookEvent event, ResourceDecision decision) {
                        throw new AssertionError("diagnostics unavailable");
                    }

                    @Override
                    public void recordFailOpen(HookEvent event) {
                        throw new AssertionError("diagnostics unavailable");
                    }
                });
        FakeChain chain = FakeChain.returning(ORIGINAL, action());

        assertNull(hook.interceptAction(chain, ResourceType.JOB));
        assertEquals(0, chain.proceedCalls());
    }

    @Test
    public void installReportsUnsupportedWithoutAttemptingPartialActivation() {
        FakeInstaller installer = new FakeInstaller(Integer.MAX_VALUE, 1);
        ClassLoader loader = new FilteringClassLoader(
                Action.class.getClassLoader(),
                Set.of("com.android.server.hans.states.action.Action"));
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> null,
                new RecordingDiagnostics(),
                installer);

        String status = hook.install(loader);

        assertTrue(status, status.startsWith("UNSUPPORTED_"));
        assertTrue(status, status.contains("action_access=MISSING_CLASS"));
        assertTrue(status, status.contains("proxy_service=MISSING_CLASS"));
        assertEquals(0, installer.calls.get());
    }

    @Test
    public void allThirteenTargetsMustInstallBeforeActivation() throws Throwable {
        RecordingDiagnostics installedDiagnostics = new RecordingDiagnostics();
        FakeInstaller successfulInstaller = new FakeInstaller(Integer.MAX_VALUE, 1);
        HansResourceHook installed = new HansResourceHook(
                (target, kind, type) -> decision(
                        target,
                        kind,
                        type,
                        allKeptPolicy(),
                        false,
                        packageUids()),
                installedDiagnostics,
                successfulInstaller);

        assertEquals("INSTALLED:action=10,manager=3",
                installed.install(Action.class.getClassLoader()));
        assertEquals(13, successfulInstaller.calls.get());
        FakeChain installedChain = FakeChain.returning(ORIGINAL, action());
        assertNull(installed.interceptAction(installedChain, ResourceType.SERVICE));
        assertEquals(0, installedChain.proceedCalls());
        assertEquals(1, installedDiagnostics.decisions.size());

        for (int failingCall : new int[] {1, 6, 10, 11, 12, 13}) {
            for (int failingCount : new int[] {-1, 0, 2}) {
                AtomicInteger decisions = new AtomicInteger();
                RecordingDiagnostics diagnostics = new RecordingDiagnostics();
                FakeInstaller installer = new FakeInstaller(failingCall, failingCount);
                HansResourceHook partial = new HansResourceHook(
                        (target, kind, type) -> {
                            decisions.incrementAndGet();
                            return decision(
                                    target,
                                    kind,
                                    type,
                                    allKeptPolicy(),
                                    false,
                                    packageUids());
                        },
                        diagnostics,
                        installer);

                String status = partial.install(Action.class.getClassLoader());
                assertTrue(status, status.startsWith("FAILED_FAIL_OPEN:PARTIAL="));
                assertEquals(failingCall, installer.calls.get());

                FakeChain chain = FakeChain.returning(ORIGINAL, action());
                assertSame(ORIGINAL,
                        partial.interceptAction(chain, ResourceType.SERVICE));
                assertEquals(1, chain.proceedCalls());
                assertEquals(0, decisions.get());
                assertTrue(diagnostics.decisions.isEmpty());
                assertTrue(diagnostics.failures.isEmpty());
            }
        }
    }

    @Test
    public void installerExceptionLeavesEveryInstalledCallbackInactive() throws Throwable {
        AtomicInteger installCalls = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            allKeptPolicy(),
                            false,
                            packageUids());
                },
                diagnostics,
                (className, loader, matcher, hooker) -> {
                    if (installCalls.incrementAndGet() == 5) {
                        throw new IllegalStateException("installer");
                    }
                    return 1;
                });

        assertThrows(
                IllegalStateException.class,
                () -> hook.install(Action.class.getClassLoader()));

        FakeChain chain = FakeChain.returning(ORIGINAL, action());
        assertSame(ORIGINAL, hook.interceptAction(chain, ResourceType.SERVICE));
        assertEquals(1, chain.proceedCalls());
        assertEquals(0, decisions.get());
        assertTrue(diagnostics.decisions.isEmpty());
        assertTrue(diagnostics.failures.isEmpty());
    }

    @Test
    public void partialInstallationKeepsAllManagerInterceptorsInactive() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansResourceHook hook = new HansResourceHook(
                (target, kind, type) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            kind,
                            type,
                            allKeptPolicy(),
                            false,
                            packageUids());
                },
                diagnostics,
                new FakeInstaller(12, 0));
        assertTrue(hook.install(Action.class.getClassLoader())
                .startsWith("FAILED_FAIL_OPEN:PARTIAL="));

        FakeChain wakeLock = FakeChain.returning(Boolean.FALSE, null, hansPackage());
        assertEquals(Boolean.FALSE, hook.interceptWakeLockManager(wakeLock));
        assertEquals(1, wakeLock.proceedCalls());

        FakeChain firewall = FakeChain.returning(
                ORIGINAL, null, hansPackage(), Boolean.FALSE, 1, 2);
        assertSame(ORIGINAL, hook.interceptFirewall(firewall));
        assertEquals(1, firewall.proceedCalls());

        FakeChain rstate = FakeChain.returning(
                Boolean.TRUE, null, "audio", UID, PACKAGE);
        assertEquals(Boolean.TRUE, hook.interceptRstateNetwork(rstate));
        assertEquals(1, rstate.proceedCalls());

        assertEquals(0, decisions.get());
        assertTrue(diagnostics.decisions.isEmpty());
        assertTrue(diagnostics.failures.isEmpty());
    }

    private static HansResourceHook hook(
            ResourcePolicy resourcePolicy,
            boolean dryRun,
            Map<String, Integer> packageUids,
            RecordingDiagnostics diagnostics) {
        return new HansResourceHook(
                (target, kind, type) -> decision(
                        target, kind, type, resourcePolicy, dryRun, packageUids),
                diagnostics);
    }

    private static ResourceDecision decision(
            ResidencyTarget target,
            ResourceContext.Kind kind,
            ResourceType type,
            ResourcePolicy resourcePolicy,
            boolean dryRun,
            Map<String, Integer> packageUids) {
        AppResidencyPolicy policy = policy(PACKAGE, resourcePolicy);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);
        return decide(snapshot, index, target, kind, type);
    }

    private static ResourceDecision decide(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            ResidencyTarget target,
            ResourceContext.Kind kind,
            ResourceType type) {
        ResourceContext context = kind == ResourceContext.Kind.NETWORK
                ? ResourceContext.network(
                        target,
                        FeatureIds.HANS_RESOURCE,
                        HookTargetIds.HANS_RESOURCE_DISPATCH)
                : ResourceContext.resource(
                        target,
                        type,
                        FeatureIds.HANS_RESOURCE,
                        HookTargetIds.HANS_RESOURCE_DISPATCH);
        return new ResidencyDecisionEngine(snapshot, index).decideResource(context);
    }

    private static AppResidencyPolicy policy(
            String packageName, ResourcePolicy resourcePolicy) {
        AppResidencyPolicy base = ResidencyPresets.create(
                packageName, ResidencyMode.CUSTOM);
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                base.freezePolicy(),
                base.wakePolicy(),
                resourcePolicy,
                base.refreezePolicy());
    }

    private static ResourcePolicy allKeptPolicy() {
        return new ResourcePolicy(true, EnumSet.allOf(ResourceType.class));
    }

    private static Map<String, Integer> packageUids() {
        return Map.of(PACKAGE, UID);
    }

    private static OplusHansPackage hansPackage() {
        return new OplusHansPackage(UID, PACKAGE, true, false, new Object());
    }

    private static ConcreteAction action() {
        return new ConcreteAction(hansPackage());
    }

    private static final class ConcreteAction extends Action {
        private ConcreteAction(OplusHansPackage hansPackage) {
            super(hansPackage);
        }
    }

    private static final class RecordingDiagnostics
            implements HansResourceHook.ResourceDiagnosticsRecorder {
        private final List<DecisionRecord> decisions = new ArrayList<>();
        private final List<HookEvent> failures = new ArrayList<>();

        @Override
        public void recordDecision(HookEvent event, ResourceDecision decision) {
            decisions.add(new DecisionRecord(event, decision));
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            failures.add(event);
        }
    }

    private static final class DecisionRecord {
        private final HookEvent event;
        private final ResourceDecision decision;

        private DecisionRecord(HookEvent event, ResourceDecision decision) {
            this.event = event;
            this.decision = decision;
        }
    }

    private static final class FakeInstaller implements HansResourceHook.HookInstaller {
        private final int failingCall;
        private final int failingCount;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeInstaller(int failingCall, int failingCount) {
            this.failingCall = failingCall;
            this.failingCount = failingCount;
        }

        @Override
        public int hookMatching(
                String className,
                ClassLoader loader,
                com.frostwork.bgenhance.HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker) {
            int call = calls.incrementAndGet();
            return call == failingCall ? failingCount : 1;
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

        private static FakeChain returning(
                Object result, Object thisObject, Object... args) {
            return new FakeChain(thisObject, () -> result, args);
        }

        private static FakeChain calling(Object thisObject, OriginalCall originalCall) {
            return new FakeChain(thisObject, originalCall);
        }

        private int proceedCalls() { return proceedCalls.get(); }

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

    private static final class FilteringClassLoader extends ClassLoader {
        private final Set<String> blockedNames;

        private FilteringClassLoader(ClassLoader parent, Set<String> blockedNames) {
            super(parent);
            this.blockedNames = blockedNames;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (blockedNames.contains(name)) {
                throw new ClassNotFoundException("blocked test fixture: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
