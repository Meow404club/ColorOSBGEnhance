package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ModuleRuntimeStatusTest {
    @Test
    public void roundTripsPositiveVersionCodes() {
        String status = ModuleRuntimeStatus.featuresInstalled(57);

        assertEquals("FEATURES_INSTALLED_V57", status);
        assertEquals(57, ModuleRuntimeStatus.loadedVersionCode(status));
        assertTrue(ModuleRuntimeStatus.isFeaturesInstalled(status));
        assertTrue(ModuleRuntimeStatus.isCurrentVersion(status, 57));
        assertFalse(ModuleRuntimeStatus.isCurrentVersion(status, 58));
    }

    @Test
    public void rejectsLegacyMalformedAndOverflowStages() {
        String[] rejected = {
                null,
                "",
                "FEATURES_INSTALLED",
                "FEATURES_INSTALLED_V",
                "FEATURES_INSTALLED_V0",
                "FEATURES_INSTALLED_V-1",
                "FEATURES_INSTALLED_V1x",
                "FEATURES_INSTALLED_V2147483648",
                "FEATURES_INSTALLED_V00000000001"
        };
        for (String status : rejected) {
            assertEquals(String.valueOf(status), -1,
                    ModuleRuntimeStatus.loadedVersionCode(status));
            assertFalse(String.valueOf(status),
                    ModuleRuntimeStatus.isFeaturesInstalled(status));
        }
    }
}
