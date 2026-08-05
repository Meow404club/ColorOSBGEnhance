package com.frostwork.bgenhance.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public final class ResidencyTestFixturesTest {
    @Test
    public void fixtureIdentitiesAreAnonymousAndDistinct() {
        Set<String> packages = Set.of(
                ResidencyTestFixtures.MANAGED_PACKAGE,
                ResidencyTestFixtures.SYSTEM_MANAGED_PACKAGE,
                ResidencyTestFixtures.UNMANAGED_PACKAGE);
        Set<Integer> uids = Set.of(
                ResidencyTestFixtures.MANAGED_UID,
                ResidencyTestFixtures.SYSTEM_MANAGED_UID,
                ResidencyTestFixtures.UNMANAGED_UID);
        Set<Integer> pids = Set.of(
                ResidencyTestFixtures.MANAGED_PID,
                ResidencyTestFixtures.SYSTEM_MANAGED_PID,
                ResidencyTestFixtures.UNMANAGED_PID);

        assertEquals(3, packages.size());
        assertEquals(3, uids.size());
        assertEquals(3, pids.size());
        assertTrue(packages.stream().allMatch(name -> name.startsWith("com.example.")));
    }
}
