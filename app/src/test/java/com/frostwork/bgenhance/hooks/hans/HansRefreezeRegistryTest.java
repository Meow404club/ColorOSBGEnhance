package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import org.junit.Test;

public final class HansRefreezeRegistryTest {
    private static final ResidencyTarget TARGET =
            new ResidencyTarget("com.example.target", 10_851, ResidencyTarget.UNKNOWN_PID);

    @Test
    public void armActivateReadAndRemoveUseObjectIdentity() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(2);
        EqualKey first = new EqualKey();
        EqualKey second = new EqualKey();

        assertEquals(HansRefreezeRegistry.ArmResult.ADDED,
                registry.arm(first, TARGET, WakeSource.PACKET));
        assertNull(registry.activeSourceOrNull(first));
        assertTrue(registry.activate(first));
        assertEquals(WakeSource.PACKET, registry.activeSourceOrNull(first));
        assertNull(registry.activeSourceOrNull(second));

        registry.remove(first);
        assertNull(registry.activeSourceOrNull(first));
        assertEquals(0, registry.sizeForTest());
    }

    @Test
    public void rearmingReplacesSourceWithoutConsumingCapacity() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object stateMachine = new Object();

        assertEquals(HansRefreezeRegistry.ArmResult.ADDED,
                registry.arm(stateMachine, TARGET, WakeSource.PACKET));
        assertEquals(HansRefreezeRegistry.ArmResult.REPLACED,
                registry.arm(stateMachine, TARGET, WakeSource.ALARM));
        assertEquals(1, registry.sizeForTest());
        assertTrue(registry.activate(stateMachine));
        assertEquals(WakeSource.ALARM, registry.activeSourceOrNull(stateMachine));
    }

    @Test
    public void capacityNeverEvictsAnActiveCycle() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object active = new Object();
        Object newcomer = new Object();

        registry.arm(active, TARGET, WakeSource.PACKET);
        assertTrue(registry.activate(active));
        assertEquals(HansRefreezeRegistry.ArmResult.FULL,
                registry.arm(newcomer, TARGET, WakeSource.ALARM));
        assertEquals(WakeSource.PACKET, registry.activeSourceOrNull(active));
        assertNull(registry.activeSourceOrNull(newcomer));
    }

    @Test
    public void enterWithoutAnArmedCycleClearsStaleActiveState() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object stateMachine = new Object();
        registry.arm(stateMachine, TARGET, WakeSource.PACKET);
        assertTrue(registry.activate(stateMachine));

        assertFalse(registry.activate(stateMachine));
        assertNull(registry.activeSourceOrNull(stateMachine));
    }

    @Test
    public void collectedWeakKeysRecoverCapacityDeterministically() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object first = new Object();
        Object second = new Object();
        registry.arm(first, TARGET, WakeSource.PACKET);

        assertTrue(registry.enqueueKeyForTest(first));
        assertEquals(0, registry.sizeForTest());
        assertEquals(HansRefreezeRegistry.ArmResult.ADDED,
                registry.arm(second, TARGET, WakeSource.ALARM));
    }

    @Test
    public void blockedRecoveryUsesGenerationAndNeverEvictsOwnership() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object stateMachine = new Object();
        registry.arm(stateMachine, TARGET, WakeSource.PACKET);
        assertTrue(registry.activate(stateMachine));
        assertTrue(registry.markFastFreezeOwned(stateMachine));
        assertTrue(registry.ownsFastFreeze(stateMachine));
        long generation = registry.activeCycleOrNull(stateMachine).generation();
        assertTrue(registry.markBlocked(stateMachine, generation, 7L));

        HansRefreezeRegistry.BlockedCycle cycle = registry.blockedCycles().get(0);
        assertEquals(TARGET, cycle.target());
        assertEquals(WakeSource.PACKET, cycle.source());
        assertTrue(registry.prepareRecovery(stateMachine, cycle.generation(), 9L));
        assertTrue(registry.isRecoveryQueued(stateMachine, cycle.generation(), 9L));
        assertFalse(registry.claimRecovery(stateMachine, cycle.generation(), 8L));
        assertTrue(registry.blockedCycles().isEmpty());

        registry.recoveryFailed(stateMachine, cycle.generation(), 9L);
        assertEquals(1, registry.blockedCycles().size());
        registry.arm(stateMachine, TARGET, WakeSource.ALARM);
        assertFalse(registry.prepareRecovery(stateMachine, cycle.generation(), 9L));
    }

    @Test
    public void sourceRebindInvalidatesOldRecoveryAndPreservesOwnership() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object stateMachine = new Object();
        Object middleState = new Object();
        registry.arm(stateMachine, TARGET, WakeSource.PACKET);
        assertTrue(registry.bindState(stateMachine, middleState));
        assertTrue(registry.activate(stateMachine));
        assertTrue(registry.markFastFreezeOwned(stateMachine));
        HansRefreezeRegistry.ActiveCycle packet =
                registry.activeCycleOrNull(stateMachine);
        assertTrue(registry.markBlocked(stateMachine, packet.generation(), 7L));
        assertTrue(registry.prepareRecovery(
                stateMachine, packet.generation(), 8L));

        HansRefreezeRegistry.ActiveCycle alarm = registry.rebindSource(
                stateMachine, packet.generation(), TARGET, WakeSource.ALARM);

        assertEquals(WakeSource.ALARM, alarm.source());
        assertTrue(alarm.fastFreezeOwned());
        assertFalse(packet.generation() == alarm.generation());
        assertFalse(registry.claimRecovery(
                stateMachine, packet.generation(), 8L));
        assertTrue(registry.clearFastFreezeOwnedForState(middleState));
        assertFalse(registry.ownsFastFreeze(stateMachine));
    }

    @Test
    public void retryTokenMustMatchTheCurrentAttempt() {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object stateMachine = new Object();
        registry.arm(stateMachine, TARGET, WakeSource.PACKET);
        assertTrue(registry.activate(stateMachine));
        long generation = registry.activeCycleOrNull(stateMachine).generation();
        assertTrue(registry.markBlocked(stateMachine, generation, 7L));
        assertTrue(registry.prepareRecovery(stateMachine, generation, 8L));
        assertEquals(1, registry.recoveryFailed(stateMachine, generation, 8L));
        assertTrue(registry.prepareRetry(stateMachine, generation, 1));
        assertFalse(registry.retryReady(stateMachine, generation, 2));
        assertTrue(registry.retryReady(stateMachine, generation, 1));
        assertEquals(1, registry.blockedCycles().size());
    }

    private static final class EqualKey {
        @Override public boolean equals(Object ignored) { return true; }
        @Override public int hashCode() { return 1; }
    }
}
