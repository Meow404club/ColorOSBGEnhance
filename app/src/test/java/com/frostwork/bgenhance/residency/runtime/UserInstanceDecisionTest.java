package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.decision.FreezeTimingContext;
import com.frostwork.bgenhance.residency.decision.FreezeTimingDecision;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.Test;

public final class UserInstanceDecisionTest {
    private static final String PACKAGE = "com.example.user.instance";
    private static final int PRIMARY_UID = 10_501;
    private static final int CLONE_UID = 999 * 100_000 + PRIMARY_UID;

    @Test
    public void primaryAndCloneDecisionsStayOnTheirOwnRules() {
        AppResidencyPolicy primary = new AppResidencyPolicy(
                PACKAGE,
                0,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem());
        AppResidencyPolicy clone = new AppResidencyPolicy(
                PACKAGE,
                999,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.all(),
                new FreezePolicy(false, false, false, false, true, 1_500L, 2_500L,
                        false, false, false),
                new WakePolicy(
                        new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                        WakeChannelPolicy.allow(),
                        Collections.emptySet()),
                new ResourcePolicy(true, Collections.emptySet()),
                new RefreezePolicy(RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
        ResidencyDecisionEngine engine = engine(primary, clone);

        ResidencyTarget primaryTarget = new ResidencyTarget(PACKAGE, PRIMARY_UID, 11);
        ResidencyTarget cloneTarget = new ResidencyTarget(PACKAGE, CLONE_UID, 12);

        assertEquals(
                KillDecision.Action.ALLOW_SYSTEM,
                engine.decideKill(new KillContext(
                        primaryTarget, KillContext.Path.ATHENA_PERIODIC)).effectiveAction());
        assertEquals(
                KillDecision.Action.BLOCK_ACTION,
                engine.decideKill(new KillContext(
                        cloneTarget, KillContext.Path.ATHENA_PERIODIC)).effectiveAction());
        assertEquals(
                KillDecision.Action.BLOCK_ACTION,
                engine.decideKill(new KillContext(
                        ResidencyTarget.forUid(CLONE_UID),
                        KillContext.Path.ATHENA_PERIODIC)).effectiveAction());

        assertEquals(
                FreezeDecision.Action.ALLOW_SYSTEM,
                engine.decideFreeze(new FreezeContext(
                        primaryTarget, FreezeSource.NORMAL)).effectiveAction());
        assertEquals(
                FreezeDecision.Action.BLOCK_FREEZE,
                engine.decideFreeze(new FreezeContext(
                        cloneTarget, FreezeSource.NORMAL)).effectiveAction());

        assertEquals(
                FreezeTimingDecision.Action.FOLLOW_SYSTEM,
                engine.decideFreezeTiming(new FreezeTimingContext(
                        primaryTarget, FreezeTimingContext.Transition.R_TO_M)).effectiveAction());
        FreezeTimingDecision cloneTiming = engine.decideFreezeTiming(new FreezeTimingContext(
                cloneTarget, FreezeTimingContext.Transition.R_TO_M));
        assertEquals(FreezeTimingDecision.Action.APPLY_CUSTOM_TIMING, cloneTiming.effectiveAction());
        assertEquals(1_500L, cloneTiming.delayMs());

        assertEquals(
                WakeDecision.Action.ALLOW_SYSTEM,
                engine.decideWake(new WakeContext(
                        primaryTarget, WakeSource.PACKET)).effectiveAction());
        assertEquals(
                WakeDecision.Action.BLOCK_WAKE,
                engine.decideWake(new WakeContext(
                        cloneTarget, WakeSource.PACKET)).effectiveAction());

        assertEquals(
                ResourceDecision.Action.ALLOW_SYSTEM,
                engine.decideResource(ResourceContext.network(primaryTarget)).effectiveAction());
        assertEquals(
                ResourceDecision.Action.KEEP_RESOURCE,
                engine.decideResource(ResourceContext.network(cloneTarget)).effectiveAction());

        assertEquals(
                RefreezeDecision.Action.ALLOW_SYSTEM,
                engine.decideRefreeze(new RefreezeContext(
                        primaryTarget, WakeSource.PACKET)).effectiveAction());
        assertEquals(
                RefreezeDecision.Action.DISABLE_REFREEZE,
                engine.decideRefreeze(new RefreezeContext(
                        cloneTarget, WakeSource.PACKET)).effectiveAction());
    }

    @Test
    public void missingUidDoesNotSelectThePrimaryRule() {
        AppResidencyPolicy primary = ResidencyPresets.create(PACKAGE, ResidencyMode.BALANCED);
        ResidencyDecisionEngine engine = engine(primary);
        KillDecision decision = engine.decideKill(new KillContext(
                ResidencyTarget.forPackage(PACKAGE), KillContext.Path.OGUARD_KILL));
        assertEquals(KillDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
    }

    private static ResidencyDecisionEngine engine(AppResidencyPolicy... policies) {
        Map<String, AppResidencyPolicy> encoded = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : policies) {
            encoded.put(policy.instanceKey().encode(), policy);
        }
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, false, 3L, encoded);
        PackageUidSource source = new PackageUidSource() {
            @Override
            public boolean hasCompleteUidEnumeration() {
                return false;
            }

            @Override
            public OptionalInt findUid(String packageName) {
                return PACKAGE.equals(packageName)
                        ? OptionalInt.of(PRIMARY_UID) : OptionalInt.empty();
            }

            @Override
            public OptionalInt findUid(String packageName, int userId) {
                if (!PACKAGE.equals(packageName)) return OptionalInt.empty();
                if (userId == 0) return OptionalInt.of(PRIMARY_UID);
                if (userId == 999) return OptionalInt.of(CLONE_UID);
                return OptionalInt.empty();
            }

            @Override
            public Set<String> findPackagesForUid(int uid) {
                return Collections.emptySet();
            }
        };
        ResidencyIdentityIndex index = new ResidencyIdentityIndexBuilder(source).build(snapshot);
        return new ResidencyDecisionEngine(snapshot, index);
    }
}
