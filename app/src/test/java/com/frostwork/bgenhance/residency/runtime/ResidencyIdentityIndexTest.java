package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class ResidencyIdentityIndexTest {
    private static final String FIRST = "com.example.identity.first";
    private static final String SECOND = "com.example.identity.second";
    private static final String UNMANAGED = "com.example.identity.unmanaged";
    private static final int FIRST_UID = 10_201;
    private static final int SHARED_UID = 10_202;
    private static final int USER_TEN_FIRST_UID = 1_010_201;
    private static final int USER_TEN_SHARED_UID = 1_010_202;

    @Test
    public void resolvesExactPackageBeforeSharedUidPolicyComparison() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));

        ResidencyIdentityResolution result = index.resolve(
                new ResidencyTarget(FIRST, SHARED_UID, 20_201));

        assertEquals(IdentityResolutionStatus.EXACT_PACKAGE, result.status());
        assertSame(first, result.policy().orElse(null));
        assertEquals(FIRST, result.matchedPackageName().orElse(null));
    }

    @Test
    public void uidScopedResolutionReportsConflictDespiteExactPackageHint() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, SHARED_UID, 20_201));

        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT, result.status());
        assertFalse(result.hasManagedPolicy());
        assertFalse(result.matchedPackageName().isPresent());
        assertEquals(2, result.candidatePackages().size());
    }

    @Test
    public void uidScopedResolutionAcceptsSharedUidWithSameEffectivePolicy() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.LOW_POWER);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, SHARED_UID, 20_201));

        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY, result.status());
        assertTrue(result.hasManagedPolicy());
        assertEquals(2, result.candidatePackages().size());
    }

    @Test
    public void uidScopedResolutionFindsSingleManagedRule() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(FIRST, FIRST_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, FIRST_UID, 20_201));

        assertEquals(IdentityResolutionStatus.UID_SINGLE_RULE, result.status());
        assertSame(first, result.policy().orElse(null));
        assertEquals(FIRST, result.matchedPackageName().orElse(null));
        assertEquals(1, result.candidatePackages().size());
    }

    @Test
    public void uidScopedResolutionFailsOpenForAnUnverifiedUserUid() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(FIRST, FIRST_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, USER_TEN_FIRST_UID, 20_201));

        assertEquals(IdentityResolutionStatus.UNKNOWN, result.status());
        assertFalse(result.hasManagedPolicy());
        assertEquals(USER_TEN_FIRST_UID, result.target().uid());
    }

    @Test
    public void uidScopedResolutionRejectsPackageHintUidMismatch() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(FIRST_UID, SHARED_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, SHARED_UID, 20_201));

        assertEquals(IdentityResolutionStatus.UID_MISMATCH, result.status());
        assertFalse(result.hasManagedPolicy());
    }

    @Test
    public void uidScopedResolutionRejectsHintOutsideVerifiedUserUidMembers() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.BALANCED);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot(7L, first, second),
                packageUids(FIRST_UID, USER_TEN_FIRST_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(SECOND, FIRST_UID, 20_201));

        assertEquals(IdentityResolutionStatus.UID_MISMATCH, result.status());
        assertFalse(result.hasManagedPolicy());
    }

    @Test
    public void uidScopedResolutionRejectsSystemUid() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(FIRST, 1_000));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, 101_000, 201));

        assertEquals(IdentityResolutionStatus.SYSTEM_UID, result.status());
        assertFalse(result.hasManagedPolicy());
    }

    @Test
    public void uidScopedResolutionRequiresUid() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(FIRST, FIRST_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                ResidencyTarget.forPackage(FIRST));

        assertEquals(IdentityResolutionStatus.UNKNOWN, result.status());
        assertFalse(result.hasManagedPolicy());
    }

    @Test
    public void uidScopedResolutionRejectsManagedHintWithoutIdentityBinding() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.ACTIVE);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(SECOND, SHARED_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(FIRST, SHARED_UID, 20_201));

        assertEquals(IdentityResolutionStatus.UNKNOWN, result.status());
        assertFalse(result.hasManagedPolicy());
        assertEquals(1, result.candidatePackages().size());
        assertTrue(result.candidatePackages().contains(FIRST));
    }

    @Test
    public void uidScopedResolutionPassesThroughUnmanagedPackageHint() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(7L, first);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(FIRST, FIRST_UID));

        ResidencyIdentityResolution result = index.resolveUidScoped(
                new ResidencyTarget(UNMANAGED, FIRST_UID, 20_201));

        assertEquals(IdentityResolutionStatus.UNMANAGED, result.status());
        assertFalse(result.hasManagedPolicy());
    }

    @Test
    public void uidScopedResolutionComparesOnlyRequestedBehaviorProjection() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = new AppResidencyPolicy(
                SECOND,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                first.freezePolicy(),
                first.wakePolicy(),
                first.resourcePolicy(),
                first.refreezePolicy());
        RuntimeConfigSnapshot snapshot = snapshot(7L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));
        ResidencyTarget target = new ResidencyTarget(FIRST, SHARED_UID, 20_201);

        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(target, UidPolicyScope.FULL).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.NORMAL_FREEZE).status());
    }

    @Test
    public void timingProjectionIgnoresUnrelatedFreezeSourceFlags() {
        FreezePolicy firstFreeze = new FreezePolicy(
                true, true, true, true, true, 60_000L, 120_000L,
                false, false, false);
        FreezePolicy secondFreeze = new FreezePolicy(
                false, false, false, false, true, 60_000L, 180_000L,
                false, false, false);
        AppResidencyPolicy first = policyWithFreeze(FIRST, firstFreeze);
        AppResidencyPolicy second = policyWithFreeze(SECOND, secondFreeze);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot(7L, first, second), packageUids(SHARED_UID, SHARED_UID));
        ResidencyTarget target = new ResidencyTarget(FIRST, SHARED_UID, 20_201);

        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(target, UidPolicyScope.NORMAL_FREEZE).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.R_TO_M_TIMING).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(target, UidPolicyScope.M_TO_F_TIMING).status());
    }

    @Test
    public void exemptionProjectionsCompareOnlyTheirEffectiveResult() {
        FreezePolicy firstFreeze = new FreezePolicy(
                true, true, true, true, false, 0L, 0L,
                false, true, false);
        FreezePolicy secondFreeze = new FreezePolicy(
                false, false, false, false, false, 0L, 0L,
                true, false, false);
        AppResidencyPolicy first = policyWithFreeze(FIRST, firstFreeze);
        AppResidencyPolicy second = policyWithFreeze(SECOND, secondFreeze);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot(7L, first, second), packageUids(SHARED_UID, SHARED_UID));
        ResidencyTarget target = new ResidencyTarget(FIRST, SHARED_UID, 20_201);

        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.CORE_EXEMPTION).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(target, UidPolicyScope.LCD_ON_EXEMPTION).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.R_TO_M_TIMING).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.M_TO_F_TIMING).status());
    }

    @Test
    public void freezeProjectionComparesOnlyTheRequestedSource() {
        FreezePolicy firstFreeze = new FreezePolicy(
                true, true, true, true, false, 0L, 0L,
                false, false, false);
        FreezePolicy secondFreeze = new FreezePolicy(
                true, false, true, true, false, 0L, 0L,
                false, false, false);
        AppResidencyPolicy first = policyWithFreeze(FIRST, firstFreeze);
        AppResidencyPolicy second = policyWithFreeze(SECOND, secondFreeze);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot(7L, first, second), packageUids(SHARED_UID, SHARED_UID));
        ResidencyTarget target = new ResidencyTarget(FIRST, SHARED_UID, 20_201);

        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.NORMAL_FREEZE).status());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(target, UidPolicyScope.FAST_FREEZE).status());
    }

    @Test
    public void invalidatedIndexDoesNotAttemptUidScopedResolution() {
        ResidencyTarget target = new ResidencyTarget(FIRST, FIRST_UID, 20_201);

        ResidencyIdentityResolution result =
                ResidencyIdentityIndex.invalidated().resolveUidScoped(target);

        assertEquals(IdentityResolutionStatus.NOT_EVALUATED, result.status());
        assertFalse(result.hasManagedPolicy());
    }

    @Test
    public void reportsConflictWhenManagedRuleSharesUidWithUnmanagedPackage() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(8L, first);
        Map<String, Integer> packageUids = packageUids(FIRST_UID, SHARED_UID);
        packageUids.put(UNMANAGED, FIRST_UID);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);

        ResidencyIdentityResolution result = index.resolve(ResidencyTarget.forUid(FIRST_UID));

        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT, result.status());
        assertFalse(result.hasManagedPolicy());
        assertTrue(result.candidatePackages().contains(UNMANAGED));
    }

    @Test
    public void packageHintsNormalizeAppIdsButUidOnlyResolutionRequiresFullUid() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(8L, first);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(FIRST, FIRST_UID));

        ResidencyTarget target = new ResidencyTarget(FIRST, USER_TEN_FIRST_UID, 20_201);
        ResidencyIdentityResolution packageResult = index.resolve(target);
        ResidencyIdentityResolution uidResult = index.resolve(
                ResidencyTarget.forUid(USER_TEN_FIRST_UID));

        assertEquals(IdentityResolutionStatus.EXACT_PACKAGE, packageResult.status());
        assertEquals(USER_TEN_FIRST_UID, packageResult.target().uid());
        assertEquals(IdentityResolutionStatus.UNKNOWN, uidResult.status());
        assertEquals(Integer.valueOf(FIRST_UID), index.packageAppIds().get(FIRST));

        KillDecision decision = new ResidencyDecisionEngine(snapshot, index).decideKill(
                new KillContext(
                        ResidencyTarget.forUid(USER_TEN_FIRST_UID),
                        KillContext.Path.OGUARD_KILL));
        assertEquals(KillDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.IDENTITY_UNKNOWN, decision.trace().reason());
    }

    @Test
    public void resolvesSharedUidWhenAllManagedRulesHaveSameEffectivePolicy() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.LOW_POWER);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(9L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));

        ResidencyIdentityResolution result = index.resolve(ResidencyTarget.forUid(SHARED_UID));

        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY, result.status());
        assertTrue(result.hasManagedPolicy());
        assertEquals(2, result.candidatePackages().size());
    }

    @Test
    public void ignoresDisplayModeWhenConcreteSharedUidPoliciesAreEqual() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = new AppResidencyPolicy(
                SECOND,
                true,
                ResidencyMode.CUSTOM,
                first.killPolicy(),
                first.freezePolicy(),
                first.wakePolicy(),
                first.resourcePolicy(),
                first.refreezePolicy());
        RuntimeConfigSnapshot snapshot = snapshot(9L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));

        assertEquals(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolve(ResidencyTarget.forUid(SHARED_UID)).status());
    }

    @Test
    public void reportsSharedUidConflictWithoutChoosingPolicy() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(10L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));

        ResidencyIdentityResolution result = index.resolve(ResidencyTarget.forUid(SHARED_UID));

        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT, result.status());
        assertFalse(result.hasManagedPolicy());
        assertFalse(result.matchedPackageName().isPresent());
    }

    @Test
    public void distinguishesUnmanagedUnknownSystemAndUidMismatch() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(11L, first);
        Map<String, Integer> packageUids = new LinkedHashMap<>();
        packageUids.put(FIRST, FIRST_UID);
        packageUids.put(UNMANAGED, 10_299);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);

        assertEquals(
                IdentityResolutionStatus.UNMANAGED,
                index.resolve(ResidencyTarget.forPackage(UNMANAGED)).status());
        assertEquals(
                IdentityResolutionStatus.UNKNOWN,
                index.resolve(ResidencyTarget.forUid(19_999)).status());
        assertEquals(
                IdentityResolutionStatus.SYSTEM_UID,
                index.resolve(ResidencyTarget.forUid(1_000)).status());
        assertEquals(
                IdentityResolutionStatus.SYSTEM_UID,
                index.resolve(ResidencyTarget.forUid(101_000)).status());
        assertEquals(
                IdentityResolutionStatus.UID_MISMATCH,
                index.resolve(new ResidencyTarget(FIRST, 10_299, -1)).status());
    }

    @Test
    public void indexIsRevisionAwareAndItsMapsAreImmutable() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(42L, first);
        Map<String, Integer> source = packageUids(USER_TEN_FIRST_UID, SHARED_UID);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, source);
        source.clear();

        assertEquals(42L, index.policyRevision());
        assertEquals(Integer.valueOf(FIRST_UID), index.packageAppIds().get(FIRST));
        assertThrows(UnsupportedOperationException.class, () -> index.packageAppIds().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> index.appIdPackages().get(FIRST_UID).add(UNMANAGED));
        assertThrows(
                UnsupportedOperationException.class,
                () -> index.uidPackages().get(USER_TEN_FIRST_UID).add(UNMANAGED));
    }

    @Test
    public void invalidatedIndexForcesDecisionLayerToFailOpen() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(43L, first);
        ResidencyIdentityIndex invalidated = ResidencyIdentityIndex.invalidated();
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(snapshot, invalidated);

        KillDecision decision = engine.decideKill(new KillContext(
                new ResidencyTarget(FIRST, FIRST_UID, -1),
                KillContext.Path.OGUARD_KILL));

        assertTrue(invalidated.isInvalidated());
        assertEquals(-1L, invalidated.policyRevision());
        assertEquals(KillDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.STALE_IDENTITY_INDEX, decision.trace().reason());
        assertEquals(
                IdentityResolutionStatus.NOT_EVALUATED,
                decision.trace().identity().status());
    }

    @Test
    public void freezeDecisionFailsOpenForConflictingSharedUidDespitePackageHint() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.ACTIVE);
        AppResidencyPolicy second = ResidencyPresets.create(SECOND, ResidencyMode.LOW_POWER);
        RuntimeConfigSnapshot snapshot = snapshot(44L, first, second);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, packageUids(SHARED_UID, SHARED_UID));
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(snapshot, index);

        FreezeDecision decision = engine.decideFreeze(new FreezeContext(
                new ResidencyTarget(FIRST, SHARED_UID, 20_201), FreezeSource.NORMAL));

        assertEquals(FreezeDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(FreezeDecision.Action.ALLOW_SYSTEM, decision.policyAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.SHARED_UID_CONFLICT, decision.trace().reason());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                decision.trace().identity().status());
        assertFalse(decision.trace().changesSystemBehavior());
    }

    private static RuntimeConfigSnapshot snapshot(
            long policyRevision,
            AppResidencyPolicy... policies) {
        Map<String, AppResidencyPolicy> map = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : policies) map.put(policy.packageName(), policy);
        return RuntimeConfigSnapshot.available(
                1L, true, true, false, policyRevision, map);
    }

    private static AppResidencyPolicy policyWithFreeze(
            String packageName, FreezePolicy freezePolicy) {
        AppResidencyPolicy base =
                ResidencyPresets.create(packageName, ResidencyMode.CUSTOM);
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freezePolicy,
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());
    }

    private static Map<String, Integer> packageUids(int firstUid, int secondUid) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(FIRST, firstUid);
        result.put(SECOND, secondUid);
        return result;
    }
}
