package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class WakeUidPolicyScopeTest {
    private static final String FIRST = "com.example.wake.first";
    private static final String SECOND = "com.example.wake.second";
    private static final int SHARED_UID = 10_712;

    @Test
    public void comparesOnlyTheRequestedWakeSource() {
        WakePolicy firstWake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 60_000L),
                WakeChannelPolicy.allow(),
                Collections.emptySet());
        WakePolicy secondWake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 60_000L),
                new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                EnumSet.of(WakeSource.SERVICE));
        ResidencyIdentityIndex index = index(
                policy(FIRST, firstWake, FreezePolicy.followSystem()),
                policy(SECOND, secondWake, FreezePolicy.followSystem()));
        ResidencyTarget target = new ResidencyTarget(FIRST, SHARED_UID, -1);

        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(
                        target, UidPolicyScope.forWakeSource(WakeSource.PACKET)).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        target, UidPolicyScope.forWakeSource(WakeSource.ALARM)).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        target, UidPolicyScope.forWakeSource(WakeSource.SERVICE)).status());
    }

    @Test
    public void throttleCooldownIsPartOfTheUidWideBehavior() {
        WakePolicy firstWake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 60_000L),
                WakeChannelPolicy.allow(),
                Collections.emptySet());
        WakePolicy secondWake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 61_000L),
                WakeChannelPolicy.allow(),
                Collections.emptySet());
        ResidencyIdentityIndex index = index(
                policy(FIRST, firstWake, FreezePolicy.followSystem()),
                policy(SECOND, secondWake, FreezePolicy.followSystem()));

        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        ResidencyTarget.forUid(SHARED_UID),
                        UidPolicyScope.forWakeSource(WakeSource.PACKET)).status());
    }

    @Test
    public void mandatorySafeSourcesIgnoreImportedBlockFlags() {
        WakePolicy allow = WakePolicy.allowAll();
        WakePolicy importedBlock = new WakePolicy(
                WakeChannelPolicy.allow(),
                WakeChannelPolicy.allow(),
                EnumSet.of(WakeSource.SYNC_BINDER, WakeSource.PROVIDER));
        ResidencyIdentityIndex index = index(
                policy(FIRST, allow, FreezePolicy.followSystem()),
                policy(SECOND, importedBlock, FreezePolicy.followSystem()));

        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(
                        ResidencyTarget.forUid(SHARED_UID),
                        UidPolicyScope.forWakeSource(WakeSource.SYNC_BINDER)).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(
                        ResidencyTarget.forUid(SHARED_UID),
                        UidPolicyScope.forWakeSource(WakeSource.PROVIDER)).status());
    }

    @Test
    public void fullExemptionAndSystemAllowRemainDistinctActions() {
        FreezePolicy fullExemption = new FreezePolicy(
                true, true, true, true, false, 0L, 0L,
                true, false, false);
        ResidencyIdentityIndex index = index(
                policy(FIRST, WakePolicy.allowAll(), fullExemption),
                policy(SECOND, WakePolicy.allowAll(), FreezePolicy.followSystem()));

        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        ResidencyTarget.forUid(SHARED_UID),
                        UidPolicyScope.forWakeSource(WakeSource.PACKET)).status());
    }

    @Test
    public void decisionEngineFailsOpenForConflictingSharedUidWakePolicy() {
        WakePolicy firstWake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                WakeChannelPolicy.allow(),
                Collections.emptySet());
        WakePolicy secondWake = WakePolicy.allowAll();
        AppResidencyPolicy first = policy(FIRST, firstWake, FreezePolicy.followSystem());
        AppResidencyPolicy second = policy(SECOND, secondWake, FreezePolicy.followSystem());
        RuntimeConfigSnapshot snapshot = snapshot(first, second);
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(
                snapshot, ResidencyIdentityIndex.build(snapshot, packageUids()));

        WakeDecision decision = engine.decideWake(new WakeContext(
                new ResidencyTarget(FIRST, SHARED_UID, -1), WakeSource.PACKET));

        assertEquals(WakeDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.SHARED_UID_CONFLICT, decision.trace().reason());
    }

    private static ResidencyIdentityIndex index(
            AppResidencyPolicy first, AppResidencyPolicy second) {
        RuntimeConfigSnapshot snapshot = snapshot(first, second);
        return ResidencyIdentityIndex.build(snapshot, packageUids());
    }

    private static RuntimeConfigSnapshot snapshot(AppResidencyPolicy... policies) {
        Map<String, AppResidencyPolicy> values = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : policies) {
            values.put(policy.packageName(), policy);
        }
        return RuntimeConfigSnapshot.available(7L, true, true, false, 9L, values);
    }

    private static Map<String, Integer> packageUids() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(FIRST, SHARED_UID);
        values.put(SECOND, SHARED_UID);
        return values;
    }

    private static AppResidencyPolicy policy(
            String packageName, WakePolicy wakePolicy, FreezePolicy freezePolicy) {
        AppResidencyPolicy base =
                ResidencyPresets.create(packageName, ResidencyMode.CUSTOM);
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freezePolicy,
                wakePolicy,
                base.resourcePolicy(),
                base.refreezePolicy());
    }
}
