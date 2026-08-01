package com.frostwork.bgenhance.hooks.oguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import org.junit.Test;

public final class OguardTargetAdapterTest {
    @Test
    public void keepsCompletePackageAndUidIdentity() {
        ResidencyTarget target = OguardTargetAdapter.from(
                "com.example.oguard.target", 10_601);

        assertEquals("com.example.oguard.target", target.packageName());
        assertEquals(10_601, target.uid());
    }

    @Test
    public void blankPackageFallsBackToUidOnlyResolution() {
        ResidencyTarget target = OguardTargetAdapter.from("  ", 10_601);

        assertFalse(target.hasPackageName());
        assertTrue(target.hasUid());
    }

    @Test
    public void invalidUidCannotBecomeAnExactPackageDecision() {
        ResidencyTarget target = OguardTargetAdapter.from(
                "com.example.oguard.target", -2);

        assertFalse(target.hasPackageName());
        assertEquals(ResidencyTarget.UNKNOWN_UID, target.uid());
    }
}
