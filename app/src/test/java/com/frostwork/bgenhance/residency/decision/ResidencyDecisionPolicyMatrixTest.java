package com.frostwork.bgenhance.residency.decision;

import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.MANAGED_PACKAGE;
import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.engine;
import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.managedTarget;
import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.policy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

public final class ResidencyDecisionPolicyMatrixTest {
    @Test
    public void mapsEveryKillPathToItsOwnPolicyFlag() {
        for (KillContext.Path protectedPath : KillContext.Path.values()) {
            ResidencyDecisionEngine engine = engine(policy(
                    MANAGED_PACKAGE,
                    true,
                    ResidencyMode.CUSTOM,
                    killPolicyFor(protectedPath),
                    FreezePolicy.followSystem(),
                    WakePolicy.allowAll(),
                    ResourcePolicy.followSystem(),
                    RefreezePolicy.followSystem()));

            for (KillContext.Path candidate : KillContext.Path.values()) {
                KillDecision decision = engine.decideKill(new KillContext(
                        managedTarget(), candidate));
                assertEquals(
                        protectedPath + " -> " + candidate,
                        candidate == protectedPath,
                        decision.blocksAction());
            }
        }
    }

    @Test
    public void fullExemptionBlocksEveryFreezeSource() {
        FreezePolicy fullExemption = new FreezePolicy(
                true, true, true, true, true, 12_000L, 34_000L, true, false, false);
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                fullExemption,
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem()));

        for (FreezeSource source : FreezeSource.values()) {
            FreezeDecision decision = engine.decideFreeze(new FreezeContext(
                    managedTarget(), source));
            assertEquals(FreezeDecision.Action.BLOCK_FREEZE, decision.effectiveAction());
            assertEquals(DecisionReason.FULL_EXEMPTION, decision.trace().reason());
        }
    }

    @Test
    public void sourceBlockTakesPriorityOverCustomTiming() {
        FreezePolicy freeze = new FreezePolicy(
                true, false, true, true, true, 12_000L, 34_000L, false, false, false);
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                freeze,
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem()));

        FreezeDecision blocked = engine.decideFreeze(new FreezeContext(
                managedTarget(), FreezeSource.FAST));
        FreezeDecision timed = engine.decideFreeze(new FreezeContext(
                managedTarget(), FreezeSource.NORMAL));

        assertEquals(FreezeDecision.Action.BLOCK_FREEZE, blocked.effectiveAction());
        assertEquals(DecisionReason.POLICY_BLOCKS_FREEZE, blocked.trace().reason());
        assertEquals(FreezeDecision.Action.APPLY_CUSTOM_TIMING, timed.effectiveAction());
        assertEquals(12_000L, timed.rToMDelayMs());
        assertEquals(34_000L, timed.mToFDelayMs());
        assertEquals(DecisionDisposition.ENFORCED, timed.trace().disposition());
    }

    @Test
    public void packetAndAlarmReturnTypedThrottleWithCooldown() {
        WakePolicy wake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 61_000L),
                new WakeChannelPolicy(WakeMode.THROTTLE, 901_000L),
                Collections.emptySet());
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                wake,
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem()));

        WakeDecision packet = engine.decideWake(new WakeContext(
                managedTarget(), WakeSource.PACKET));
        WakeDecision alarm = engine.decideWake(new WakeContext(
                managedTarget(), WakeSource.ALARM));

        assertEquals(WakeDecision.Action.THROTTLE_WAKE, packet.effectiveAction());
        assertEquals(61_000L, packet.cooldownMs());
        assertEquals(WakeDecision.Action.THROTTLE_WAKE, alarm.effectiveAction());
        assertEquals(901_000L, alarm.cooldownMs());
    }

    @Test
    public void safetyCriticalWakeSourcesAlwaysFollowSystem() {
        WakePolicy blockEverything = new WakePolicy(
                new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                EnumSet.allOf(WakeSource.class));
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                blockEverything,
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem()));
        WakeSource[] safeSources = {
                WakeSource.INTERNAL_OPERATION,
                WakeSource.ACTIVITY_INPUT,
                WakeSource.SYNC_BINDER,
                WakeSource.PROVIDER,
                WakeSource.SYSTEM_SCENE,
                WakeSource.OTHER
        };

        for (WakeSource source : safeSources) {
            WakeDecision decision = engine.decideWake(new WakeContext(
                    managedTarget(), source));
            assertEquals(source.name(), WakeDecision.Action.ALLOW_SYSTEM,
                    decision.effectiveAction());
            assertEquals(source.name(), DecisionReason.SAFE_WAKE_SOURCE,
                    decision.trace().reason());
        }
        assertEquals(
                WakeDecision.Action.BLOCK_WAKE,
                engine.decideWake(new WakeContext(
                        managedTarget(), WakeSource.ASYNC_BINDER)).effectiveAction());
    }

    @Test
    public void fullExemptionForcesWakeAllowOverBlockAndThrottle() {
        FreezePolicy fullExemption = new FreezePolicy(
                false, false, false, false, false, 0L, 0L, true, false, false);
        WakePolicy blocked = new WakePolicy(
                new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                new WakeChannelPolicy(WakeMode.THROTTLE, 9_000L),
                EnumSet.allOf(WakeSource.class));
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                fullExemption,
                blocked,
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem()));

        for (WakeSource source : WakeSource.values()) {
            WakeDecision decision = engine.decideWake(new WakeContext(
                    managedTarget(), source));
            assertEquals(source.name(), WakeDecision.Action.ALLOW_WAKE,
                    decision.effectiveAction());
            assertEquals(source.name(), DecisionReason.FULL_EXEMPTION,
                    decision.trace().reason());
        }
    }

    @Test
    public void keepsOnlyExplicitNetworkAndResourceSelections() {
        ResourcePolicy resources = new ResourcePolicy(
                true, EnumSet.of(ResourceType.AUDIO, ResourceType.JOB));
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                WakePolicy.allowAll(),
                resources,
                RefreezePolicy.followSystem()));

        assertTrue(engine.decideResource(ResourceContext.network(
                managedTarget())).keepsResource());
        assertTrue(engine.decideResource(ResourceContext.resource(
                managedTarget(), ResourceType.AUDIO)).keepsResource());
        assertTrue(engine.decideResource(ResourceContext.resource(
                managedTarget(), ResourceType.JOB)).keepsResource());
        assertFalse(engine.decideResource(ResourceContext.resource(
                managedTarget(), ResourceType.SERVICE)).keepsResource());
    }

    @Test
    public void refreezeUsesSourceSpecificAndDefaultDelays() {
        RefreezePolicy refreeze = new RefreezePolicy(
                RefreezeMode.AFTER_WAKE,
                10_000L,
                20_000L,
                30_000L,
                40_000L,
                50_000L,
                60_000L,
                70_000L);
        ResidencyDecisionEngine engine = engine(customPolicy(
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                refreeze));

        assertRefreezeDelay(engine, WakeSource.PACKET, 20_000L);
        assertRefreezeDelay(engine, WakeSource.ALARM, 30_000L);
        assertRefreezeDelay(engine, WakeSource.TRANSACTION_BINDER, 40_000L);
        assertRefreezeDelay(engine, WakeSource.SERVICE, 50_000L);
        assertRefreezeDelay(engine, WakeSource.BROADCAST, 60_000L);
        assertRefreezeDelay(engine, WakeSource.JOB_SYNC, 70_000L);
        assertRefreezeDelay(engine, WakeSource.CONNECTIVITY, 10_000L);
    }

    @Test
    public void disabledRefreezeAndFullExemptionBothPreventScheduling() {
        RefreezePolicy disabled = new RefreezePolicy(
                RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        ResidencyDecisionEngine disabledEngine = engine(customPolicy(
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                disabled));
        FreezePolicy fullExemption = new FreezePolicy(
                false, false, false, false, false, 0L, 0L, true, false, false);
        ResidencyDecisionEngine exemptEngine = engine(customPolicy(
                KillPolicy.none(),
                fullExemption,
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem()));

        assertEquals(
                RefreezeDecision.Action.DISABLE_REFREEZE,
                disabledEngine.decideRefreeze(new RefreezeContext(
                        managedTarget(), WakeSource.PACKET)).effectiveAction());
        RefreezeDecision exempt = exemptEngine.decideRefreeze(new RefreezeContext(
                managedTarget(), WakeSource.PACKET));
        assertEquals(RefreezeDecision.Action.DISABLE_REFREEZE, exempt.effectiveAction());
        assertEquals(DecisionReason.FULL_EXEMPTION, exempt.trace().reason());
    }

    private static AppResidencyPolicy customPolicy(
            KillPolicy kill,
            FreezePolicy freeze,
            WakePolicy wake,
            ResourcePolicy resources,
            RefreezePolicy refreeze) {
        return policy(
                MANAGED_PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                kill,
                freeze,
                wake,
                resources,
                refreeze);
    }

    private static KillPolicy killPolicyFor(KillContext.Path path) {
        return new KillPolicy(
                path == KillContext.Path.OGUARD_KILL,
                path == KillContext.Path.OGUARD_FORCE_STOP,
                path == KillContext.Path.ATHENA_PERIODIC,
                path == KillContext.Path.FAST_CLEAR,
                path == KillContext.Path.TOURNAMENT,
                path == KillContext.Path.MEMORY_GUARD,
                path == KillContext.Path.NIRVANA,
                path == KillContext.Path.OSENSE_PER_PROCESS);
    }

    private static void assertRefreezeDelay(
            ResidencyDecisionEngine engine,
            WakeSource source,
            long expectedDelayMs) {
        RefreezeDecision decision = engine.decideRefreeze(new RefreezeContext(
                managedTarget(), source));
        assertEquals(RefreezeDecision.Action.SCHEDULE_REFREEZE, decision.effectiveAction());
        assertEquals(expectedDelayMs, decision.delayMs());
    }
}
