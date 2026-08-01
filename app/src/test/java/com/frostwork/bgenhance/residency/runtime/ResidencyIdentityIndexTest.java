package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
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
    public void resolvesUidWithOneManagedRule() {
        AppResidencyPolicy first = ResidencyPresets.create(FIRST, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = snapshot(8L, first);
        Map<String, Integer> packageUids = packageUids(FIRST_UID, SHARED_UID);
        packageUids.put(UNMANAGED, FIRST_UID);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);

        ResidencyIdentityResolution result = index.resolve(ResidencyTarget.forUid(FIRST_UID));

        assertEquals(IdentityResolutionStatus.UID_SINGLE_RULE, result.status());
        assertSame(first, result.policy().orElse(null));
        assertTrue(result.candidatePackages().contains(UNMANAGED));
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
        Map<String, Integer> source = packageUids(FIRST_UID, SHARED_UID);
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, source);
        source.clear();

        assertEquals(42L, index.policyRevision());
        assertEquals(Integer.valueOf(FIRST_UID), index.packageUids().get(FIRST));
        assertThrows(UnsupportedOperationException.class, () -> index.packageUids().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> index.uidPackages().get(FIRST_UID).add(UNMANAGED));
    }

    private static RuntimeConfigSnapshot snapshot(
            long policyRevision,
            AppResidencyPolicy... policies) {
        Map<String, AppResidencyPolicy> map = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : policies) map.put(policy.packageName(), policy);
        return RuntimeConfigSnapshot.available(
                1L, true, true, false, policyRevision, map);
    }

    private static Map<String, Integer> packageUids(int firstUid, int secondUid) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(FIRST, firstUid);
        result.put(SECOND, secondUid);
        return result;
    }
}
