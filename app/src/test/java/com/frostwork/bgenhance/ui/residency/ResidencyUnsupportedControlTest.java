package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ResidencyUnsupportedControlTest {
    @Test
    public void unsupportedActiveProtectionCanBeTurnedOffButNotEnabledAgain() {
        assertTrue(ResidencyPolicyDetailActivity.isUnsupportedSwitchEditable(
                true, true, true));
        assertFalse(ResidencyPolicyDetailActivity.isUnsupportedSwitchEditable(
                true, true, false));
    }

    @Test
    public void parentRestrictionStillWinsAndSupportedControlRemainsEditable() {
        assertFalse(ResidencyPolicyDetailActivity.isUnsupportedSwitchEditable(
                false, false, true));
        assertTrue(ResidencyPolicyDetailActivity.isUnsupportedSwitchEditable(
                true, false, false));
    }
}
