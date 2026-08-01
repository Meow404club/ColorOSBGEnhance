package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.Test;

public final class ResidencyIdentityIndexBuilderTest {
    private static final String FIRST = "com.example.builder.first";
    private static final String SECOND = "com.example.builder.second";
    private static final String UNMANAGED = "com.example.builder.unmanaged";
    private static final int FIRST_UID = 10_301;
    private static final int SECOND_UID = 10_302;

    @Test
    public void queriesOnlyManagedRulesAndBuildsOneCompleteIndex() {
        FakePackageUidSource source = new FakePackageUidSource();
        source.uids.put(FIRST, FIRST_UID);
        source.uids.put(SECOND, SECOND_UID);
        source.uids.put(UNMANAGED, 10_399);
        RuntimeConfigSnapshot snapshot = snapshot(51L, FIRST, SECOND);

        ResidencyIdentityIndex index = new ResidencyIdentityIndexBuilder(source).build(snapshot);

        assertEquals(List.of(FIRST, SECOND), source.queries);
        assertEquals(51L, index.policyRevision());
        assertEquals(Map.of(FIRST, FIRST_UID, SECOND, SECOND_UID), index.packageAppIds());
        assertFalse(index.packageAppIds().containsKey(UNMANAGED));
    }

    @Test
    public void skipsPackagesThatAreNotInstalled() {
        FakePackageUidSource source = new FakePackageUidSource();
        source.uids.put(FIRST, FIRST_UID);
        RuntimeConfigSnapshot snapshot = snapshot(52L, FIRST, SECOND);

        ResidencyIdentityIndex index = new ResidencyIdentityIndexBuilder(source).build(snapshot);

        assertEquals(Map.of(FIRST, FIRST_UID), index.packageAppIds());
        assertEquals(
                IdentityResolutionStatus.UNKNOWN,
                index.resolve(ResidencyTarget.forUid(SECOND_UID)).status());
    }

    @Test
    public void securityFailureAbortsBuildWithoutReturningPartialIndex() {
        FakePackageUidSource source = new FakePackageUidSource();
        source.uids.put(FIRST, FIRST_UID);
        source.failurePackage = SECOND;
        source.failure = new SecurityException("package visibility denied");
        ResidencyIdentityIndexBuilder builder = new ResidencyIdentityIndexBuilder(source);

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> builder.build(snapshot(53L, FIRST, SECOND)));

        assertEquals("package visibility denied", error.getMessage());
        assertEquals(List.of(FIRST, SECOND), source.queries);
    }

    @Test
    public void otherRuntimeFailureAlsoAbortsBuild() {
        FakePackageUidSource source = new FakePackageUidSource();
        source.failurePackage = FIRST;
        source.failure = new IllegalStateException("package manager unavailable");
        ResidencyIdentityIndexBuilder builder = new ResidencyIdentityIndexBuilder(source);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> builder.build(snapshot(54L, FIRST)));

        assertEquals("package manager unavailable", error.getMessage());
    }

    private static RuntimeConfigSnapshot snapshot(long policyRevision, String... packageNames) {
        Map<String, AppResidencyPolicy> policies = new LinkedHashMap<>();
        for (String packageName : packageNames) {
            policies.put(
                    packageName,
                    ResidencyPresets.create(packageName, ResidencyMode.BALANCED));
        }
        return RuntimeConfigSnapshot.available(
                1L, true, true, false, policyRevision, policies);
    }

    private static final class FakePackageUidSource implements PackageUidSource {
        final Map<String, Integer> uids = new LinkedHashMap<>();
        final List<String> queries = new ArrayList<>();
        String failurePackage;
        RuntimeException failure;

        @Override
        public OptionalInt findUid(String packageName) {
            queries.add(packageName);
            if (packageName.equals(failurePackage)) throw failure;
            Integer uid = uids.get(packageName);
            return uid == null ? OptionalInt.empty() : OptionalInt.of(uid);
        }
    }
}
