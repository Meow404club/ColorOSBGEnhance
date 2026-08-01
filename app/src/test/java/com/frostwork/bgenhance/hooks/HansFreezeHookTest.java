package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import com.android.server.am.OplusHansManager;
import com.frostwork.bgenhance.HookTargetIds;
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
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class HansFreezeHookTest {
    private static final String PACKAGE = "com.example.hans.freeze";
    private static final int UID = 10_901;

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

    private static FreezeDecision decisionFor(
            FreezePolicy freezePolicy,
            boolean dryRun,
            Map<String, Integer> packageUids) {
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
                FreezeSource.NORMAL,
                "hans_freeze",
                HookTargetIds.HANS_FREEZE_DISPATCH));
    }

    private static final class OriginalFailure extends Exception {}
}
