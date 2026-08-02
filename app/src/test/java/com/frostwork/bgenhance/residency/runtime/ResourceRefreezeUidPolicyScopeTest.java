package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class ResourceRefreezeUidPolicyScopeTest {
    private static final String FIRST = "com.example.resource.first";
    private static final String SECOND = "com.example.resource.second";
    private static final int SHARED_UID = 10_734;

    @Test
    public void everyResourceAndRefreezeSourceHasAnIndependentScope() {
        Set<UidPolicyScope> resourceScopes = new HashSet<>();
        for (ResourceType resource : ResourceType.values()) {
            UidPolicyScope scope = UidPolicyScope.forResource(resource);
            assertEquals(resource, scope.resourceTypeOrNull());
            resourceScopes.add(scope);
        }
        assertEquals(ResourceType.values().length, resourceScopes.size());

        Set<UidPolicyScope> refreezeScopes = new HashSet<>();
        for (WakeSource source : WakeSource.values()) {
            UidPolicyScope scope = UidPolicyScope.forRefreezeSource(source);
            assertEquals(source, scope.refreezeSourceOrNull());
            refreezeScopes.add(scope);
        }
        assertEquals(WakeSource.values().length, refreezeScopes.size());
    }

    @Test
    public void resourceScopesCompareOnlyTheRequestedUidWideBehavior() {
        AppResidencyPolicy first = policy(
                FIRST,
                new ResourcePolicy(true, EnumSet.of(ResourceType.AUDIO)),
                RefreezePolicy.followSystem(),
                FreezePolicy.followSystem());
        AppResidencyPolicy second = policy(
                SECOND,
                new ResourcePolicy(
                        true, EnumSet.of(ResourceType.AUDIO, ResourceType.JOB)),
                RefreezePolicy.followSystem(),
                FreezePolicy.followSystem());
        ResidencyIdentityIndex index = index(first, second);
        ResidencyTarget target = new ResidencyTarget(FIRST, SHARED_UID, -1);

        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(target, UidPolicyScope.RESOURCE_NETWORK).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(
                        target, UidPolicyScope.forResource(ResourceType.AUDIO)).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        target, UidPolicyScope.forResource(ResourceType.JOB)).status());
    }

    @Test
    public void resourceDecisionFailsOpenForConflictingSharedUidRule() {
        AppResidencyPolicy first = policy(
                FIRST,
                new ResourcePolicy(false, EnumSet.of(ResourceType.JOB)),
                RefreezePolicy.followSystem(),
                FreezePolicy.followSystem());
        AppResidencyPolicy second = policy(
                SECOND,
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem(),
                FreezePolicy.followSystem());
        ResidencyDecisionEngine engine = engine(first, second);

        ResourceDecision decision = engine.decideResource(ResourceContext.resource(
                new ResidencyTarget(FIRST, SHARED_UID, -1), ResourceType.JOB));

        assertEquals(ResourceDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.SHARED_UID_CONFLICT, decision.trace().reason());
    }

    @Test
    public void systemManagedSiblingCannotBeRepresentedByACustomRule() {
        AppResidencyPolicy custom = policy(
                FIRST,
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem(),
                FreezePolicy.followSystem());
        AppResidencyPolicy system =
                ResidencyPresets.create(SECOND, ResidencyMode.SYSTEM);
        ResidencyIdentityIndex index = index(custom, system);

        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        ResidencyTarget.forUid(SHARED_UID),
                        UidPolicyScope.RESOURCE_NETWORK).status());
    }

    @Test
    public void refreezeScopesCompareOnlyTheRequestedSourceAndDelay() {
        RefreezePolicy firstRefreeze = refreeze(10_000L, 20_000L, 30_000L);
        RefreezePolicy secondRefreeze = refreeze(11_000L, 20_000L, 31_000L);
        AppResidencyPolicy first = policy(
                FIRST, ResourcePolicy.followSystem(), firstRefreeze,
                FreezePolicy.followSystem());
        AppResidencyPolicy second = policy(
                SECOND, ResourcePolicy.followSystem(), secondRefreeze,
                FreezePolicy.followSystem());
        ResidencyIdentityIndex index = index(first, second);
        ResidencyTarget target = ResidencyTarget.forUid(SHARED_UID);

        assertEquals(IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                index.resolveUidScoped(
                        target,
                        UidPolicyScope.forRefreezeSource(WakeSource.PACKET)).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        target,
                        UidPolicyScope.forRefreezeSource(WakeSource.ALARM)).status());
        assertEquals(IdentityResolutionStatus.SHARED_UID_CONFLICT,
                index.resolveUidScoped(
                        target,
                        UidPolicyScope.forRefreezeSource(WakeSource.CONNECTIVITY)).status());
    }

    @Test
    public void equivalentDisableActionsAreSafeAcrossSharedUidRules() {
        FreezePolicy fullExemption = new FreezePolicy(
                true, true, true, true, false, 0L, 0L, true, false, false);
        RefreezePolicy disabled = new RefreezePolicy(
                RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        AppResidencyPolicy first = policy(
                FIRST, ResourcePolicy.followSystem(), RefreezePolicy.followSystem(),
                fullExemption);
        AppResidencyPolicy second = policy(
                SECOND, ResourcePolicy.followSystem(), disabled,
                FreezePolicy.followSystem());
        ResidencyDecisionEngine engine = engine(first, second);

        RefreezeDecision decision = engine.decideRefreeze(new RefreezeContext(
                ResidencyTarget.forUid(SHARED_UID), WakeSource.ALARM));

        assertEquals(RefreezeDecision.Action.DISABLE_REFREEZE,
                decision.effectiveAction());
        assertEquals(DecisionDisposition.ENFORCED, decision.trace().disposition());
    }

    @Test
    public void refreezeDecisionFailsOpenForSourceSpecificConflict() {
        AppResidencyPolicy first = policy(
                FIRST, ResourcePolicy.followSystem(),
                refreeze(10_000L, 20_000L, 30_000L), FreezePolicy.followSystem());
        AppResidencyPolicy second = policy(
                SECOND, ResourcePolicy.followSystem(),
                refreeze(10_000L, 20_000L, 31_000L), FreezePolicy.followSystem());
        ResidencyDecisionEngine engine = engine(first, second);

        RefreezeDecision decision = engine.decideRefreeze(new RefreezeContext(
                ResidencyTarget.forUid(SHARED_UID), WakeSource.ALARM));

        assertEquals(RefreezeDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.SHARED_UID_CONFLICT, decision.trace().reason());
    }

    private static RefreezePolicy refreeze(
            long defaultDelay, long packetDelay, long alarmDelay) {
        return new RefreezePolicy(
                RefreezeMode.AFTER_WAKE,
                defaultDelay,
                packetDelay,
                alarmDelay,
                defaultDelay,
                defaultDelay,
                defaultDelay,
                defaultDelay);
    }

    private static ResidencyDecisionEngine engine(AppResidencyPolicy... policies) {
        RuntimeConfigSnapshot snapshot = snapshot(policies);
        return new ResidencyDecisionEngine(
                snapshot, ResidencyIdentityIndex.build(snapshot, packageUids()));
    }

    private static ResidencyIdentityIndex index(AppResidencyPolicy... policies) {
        RuntimeConfigSnapshot snapshot = snapshot(policies);
        return ResidencyIdentityIndex.build(snapshot, packageUids());
    }

    private static RuntimeConfigSnapshot snapshot(AppResidencyPolicy... policies) {
        Map<String, AppResidencyPolicy> values = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : policies) {
            values.put(policy.packageName(), policy);
        }
        return RuntimeConfigSnapshot.available(8L, true, true, false, 12L, values);
    }

    private static Map<String, Integer> packageUids() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(FIRST, SHARED_UID);
        values.put(SECOND, SHARED_UID);
        return values;
    }

    private static AppResidencyPolicy policy(
            String packageName,
            ResourcePolicy resources,
            RefreezePolicy refreeze,
            FreezePolicy freeze) {
        AppResidencyPolicy base =
                ResidencyPresets.create(packageName, ResidencyMode.CUSTOM);
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freeze,
                base.wakePolicy(),
                resources,
                refreeze);
    }
}
