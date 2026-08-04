package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import org.junit.Test;

public final class ResidencyPolicyFormValidatorTest {
    @Test
    public void optionalBlankAndZeroRemainDisabled() {
        assertEquals(0L, ResidencyPolicyFormValidator.parseSeconds("", false));
        assertEquals(0L, ResidencyPolicyFormValidator.parseSeconds("0", false));
    }

    @Test
    public void inactiveHiddenInputIsIgnoredEvenWhenItsStaleTextIsInvalid() {
        assertEquals(0L,
                ResidencyPolicyFormValidator.parseSecondsWhenActive("not-a-number", false));
        assertEquals(1_500L,
                ResidencyPolicyFormValidator.parseSecondsWhenActive("1.5", true));
    }

    @Test
    public void acceptedRangeIsOneSecondThroughTwentyFourHours() {
        assertEquals(1_000L, ResidencyPolicyFormValidator.parseSeconds("1", true));
        assertEquals(1_500L, ResidencyPolicyFormValidator.parseSeconds("1.5", true));
        assertEquals(1_001L, ResidencyPolicyFormValidator.parseSeconds("1.001", true));
        assertEquals(86_400_000L,
                ResidencyPolicyFormValidator.parseSeconds("86400", true));
    }

    @Test
    public void millisecondValuesRoundTripThroughSecondsTextWithoutTruncation() {
        for (long milliseconds : new long[] {1_000L, 1_001L, 1_500L, 86_400_000L}) {
            String rendered = ResidencyPolicyActivity.formatSeconds(milliseconds);
            assertEquals(milliseconds, ResidencyPolicyFormValidator.parseSeconds(rendered, true));
        }
        assertEquals("", ResidencyPolicyActivity.formatSeconds(0L));
    }

    @Test
    public void requiredInvalidAndOutOfRangeValuesHaveStableFailures() {
        assertFailure("", true, ResidencyPolicyFormValidator.InputFailure.REQUIRED);
        assertFailure("abc", false, ResidencyPolicyFormValidator.InputFailure.NOT_A_NUMBER);
        assertFailure("1.0001", true,
                ResidencyPolicyFormValidator.InputFailure.NOT_A_NUMBER);
        assertFailure("-1", false, ResidencyPolicyFormValidator.InputFailure.OUT_OF_RANGE);
        assertFailure("86401", true, ResidencyPolicyFormValidator.InputFailure.OUT_OF_RANGE);
        assertFailure("999999999999999999999", true,
                ResidencyPolicyFormValidator.InputFailure.OUT_OF_RANGE);
    }

    @Test
    public void hiddenWakeSourcesSurviveAnOrdinaryCustomEdit() {
        java.util.EnumSet<com.frostwork.bgenhance.residency.model.WakeSource> original =
                java.util.EnumSet.of(
                        com.frostwork.bgenhance.residency.model.WakeSource.PROVIDER,
                        com.frostwork.bgenhance.residency.model.WakeSource.SYSTEM_SCENE,
                        com.frostwork.bgenhance.residency.model.WakeSource.SERVICE);
        java.util.EnumSet<com.frostwork.bgenhance.residency.model.WakeSource> preserved =
                ResidencyPolicyActivity.preserveUnmanagedWakeSources(original);
        assertTrue(preserved.contains(
                com.frostwork.bgenhance.residency.model.WakeSource.PROVIDER));
        assertTrue(preserved.contains(
                com.frostwork.bgenhance.residency.model.WakeSource.SYSTEM_SCENE));
        assertFalse(preserved.contains(
                com.frostwork.bgenhance.residency.model.WakeSource.SERVICE));
    }

    @Test
    public void completePresetDraftPassesPolicyValidation() {
        assertTrue(ResidencyPolicyFormValidator.isValid(
                "com.example.valid",
                ResidencyPolicyDraft.from(ResidencyPresets.create(
                        "com.example.valid", ResidencyMode.LOW_POWER))));
    }

    @Test
    public void invalidCustomTimingIsRejectedBeforePublication() {
        AppResidencyPolicy base = ResidencyPresets.create(
                "com.example.invalid", ResidencyMode.CUSTOM);
        ResidencyPolicyDraft invalid = new ResidencyPolicyDraft(
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                new FreezePolicy(true, true, true, true, true, 0L, 0L,
                        false, false, false),
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());

        assertFalse(ResidencyPolicyFormValidator.isValid("com.example.invalid", invalid));
    }

    private static void assertFailure(
            String value,
            boolean required,
            ResidencyPolicyFormValidator.InputFailure expected) {
        ResidencyPolicyFormValidator.InputException exception = assertThrows(
                ResidencyPolicyFormValidator.InputException.class,
                () -> ResidencyPolicyFormValidator.parseSeconds(value, required));
        assertEquals(expected, exception.failure());
    }
}
