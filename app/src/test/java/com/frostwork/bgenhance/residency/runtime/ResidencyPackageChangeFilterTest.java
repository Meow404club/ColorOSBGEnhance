package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.Map;
import org.junit.Test;

public final class ResidencyPackageChangeFilterTest {
    private static final String MANAGED = "com.example.receiver.managed";

    @Test
    public void everyPackageChangeInvalidatesAnActiveIdentityIndex() {
        AppResidencyPolicy policy = ResidencyPresets.create(
                MANAGED, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, Map.of(MANAGED, policy));

        assertTrue(ResidencyPackageChangeFilter.shouldInvalidate(MANAGED, snapshot));
        assertTrue(ResidencyPackageChangeFilter.shouldInvalidate(null, snapshot));
        assertTrue(ResidencyPackageChangeFilter.shouldInvalidate("", snapshot));
        assertTrue(ResidencyPackageChangeFilter.shouldInvalidate(
                "com.example.receiver.unmanaged", snapshot));
    }

    @Test
    public void packageChangesDoNotRebuildAnEmptyIdentityIndex() {
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 2L, Map.of());

        assertFalse(ResidencyPackageChangeFilter.shouldInvalidate(MANAGED, snapshot));
        assertFalse(ResidencyPackageChangeFilter.shouldInvalidate(null, snapshot));
    }
}
