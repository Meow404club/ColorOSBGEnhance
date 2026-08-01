package com.frostwork.bgenhance.residency.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.testing.ResidencyTestFixtures;
import org.junit.Test;

public final class ResidencyPresetsTest {
    @Test
    public void systemModeLeavesEveryBehaviorToTheSystem() {
        AppResidencyPolicy policy = create(ResidencyMode.SYSTEM);

        assertFalse(policy.killPolicy().protectOguardKill());
        assertFalse(policy.killPolicy().protectOsensePerProcess());
        assertAllFreezeSources(policy, true);
        assertEquals(WakeMode.ALLOW, policy.wakePolicy().modeFor(WakeSource.PACKET));
        assertEquals(RefreezeMode.SYSTEM, policy.refreezePolicy().mode());
        assertFalse(policy.resourcePolicy().keepNetwork());
    }

    @Test
    public void balancedModeProtectsResidencyButKeepsSystemFreezeBehavior() {
        AppResidencyPolicy policy = create(ResidencyMode.BALANCED);

        assertAllKillPathsProtected(policy);
        assertAllFreezeSources(policy, true);
        assertEquals(0L, policy.freezePolicy().rToMDelayMs());
        assertEquals(0L, policy.freezePolicy().mToFDelayMs());
        assertEquals(WakeMode.ALLOW, policy.wakePolicy().modeFor(WakeSource.ALARM));
        assertEquals(RefreezeMode.SYSTEM, policy.refreezePolicy().mode());
    }

    @Test
    public void lowPowerModeThrottlesFrequentWakeChannels() {
        AppResidencyPolicy policy = create(ResidencyMode.LOW_POWER);

        assertAllKillPathsProtected(policy);
        assertAllFreezeSources(policy, true);
        assertEquals(WakeMode.THROTTLE, policy.wakePolicy().packet().mode());
        assertEquals(5L * 60L * 1_000L, policy.wakePolicy().packet().cooldownMs());
        assertEquals(WakeMode.THROTTLE, policy.wakePolicy().alarm().mode());
        assertEquals(15L * 60L * 1_000L, policy.wakePolicy().alarm().cooldownMs());
        assertTrue(policy.freezePolicy().customTiming());
        assertEquals(60_000L, policy.freezePolicy().rToMDelayMs());
        assertEquals(60_000L, policy.freezePolicy().mToFDelayMs());
        assertEquals(RefreezeMode.AFTER_WAKE, policy.refreezePolicy().mode());
    }

    @Test
    public void activeModePreventsKnownFreezeSources() {
        AppResidencyPolicy policy = create(ResidencyMode.ACTIVE);

        assertAllKillPathsProtected(policy);
        assertAllFreezeSources(policy, false);
        assertTrue(policy.freezePolicy().fullExemption());
        assertTrue(policy.freezePolicy().coreExemption());
        assertTrue(policy.freezePolicy().lcdOnExemption());
        assertEquals(RefreezeMode.DISABLED, policy.refreezePolicy().mode());
    }

    @Test
    public void customModeStartsFromConservativeBalancedValues() {
        AppResidencyPolicy policy = create(ResidencyMode.CUSTOM);

        assertEquals(ResidencyMode.CUSTOM, policy.mode());
        assertAllKillPathsProtected(policy);
        assertAllFreezeSources(policy, true);
        assertEquals(WakeMode.ALLOW, policy.wakePolicy().modeFor(WakeSource.OTHER));
        assertEquals(RefreezeMode.SYSTEM, policy.refreezePolicy().mode());
    }

    private static AppResidencyPolicy create(ResidencyMode mode) {
        return ResidencyPresets.create(ResidencyTestFixtures.MANAGED_PACKAGE, mode);
    }

    private static void assertAllKillPathsProtected(AppResidencyPolicy policy) {
        KillPolicy kill = policy.killPolicy();
        assertTrue(kill.protectOguardKill());
        assertTrue(kill.protectOguardForceStop());
        assertTrue(kill.protectAthenaPeriodic());
        assertTrue(kill.protectFastClear());
        assertTrue(kill.protectTournament());
        assertTrue(kill.protectMemoryGuard());
        assertTrue(kill.protectNirvana());
        assertTrue(kill.protectOsensePerProcess());
    }

    private static void assertAllFreezeSources(AppResidencyPolicy policy, boolean expected) {
        for (FreezeSource source : FreezeSource.values()) {
            assertEquals(source.name(), expected, policy.freezePolicy().allows(source));
        }
    }
}
