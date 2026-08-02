package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import org.junit.Test;

public final class ResidencyAppSafetyTest {
    private static final String MODULE = "com.frostwork.bgenhance";

    @Test
    public void ordinaryApplicationIsConfigurable() {
        ResidencyAppSafety.Result result = ResidencyAppSafety.classify(
                "com.example.reader", 10_123, 0, MODULE);

        assertFalse(result.isRestricted());
        assertEquals(ResidencyAppSafety.Restriction.NONE, result.restriction());
    }

    @Test
    public void moduleCorePersistentAndCriticalPackagesAreRestricted() {
        assertEquals(
                ResidencyAppSafety.Restriction.MODULE,
                ResidencyAppSafety.classify(MODULE, 10_321, 0, MODULE).restriction());
        assertEquals(
                ResidencyAppSafety.Restriction.CORE_SYSTEM,
                ResidencyAppSafety.classify("com.example.core", 1_000, 0, MODULE)
                        .restriction());
        assertEquals(
                ResidencyAppSafety.Restriction.PERSISTENT,
                ResidencyAppSafety.classify(
                        "com.example.persistent",
                        10_456,
                        ApplicationInfo.FLAG_PERSISTENT,
                        MODULE).restriction());
        assertEquals(
                ResidencyAppSafety.Restriction.CRITICAL_PACKAGE,
                ResidencyAppSafety.classify("com.android.settings", 10_789, 0, MODULE)
                        .restriction());
    }

    @Test
    public void coreAppIdIsDetectedForSecondaryUsers() {
        ResidencyAppSafety.Result result = ResidencyAppSafety.classify(
                "com.example.secondary.core", 1_001_000, 0, MODULE);

        assertTrue(result.isRestricted());
        assertEquals(ResidencyAppSafety.Restriction.CORE_SYSTEM, result.restriction());
    }
}
