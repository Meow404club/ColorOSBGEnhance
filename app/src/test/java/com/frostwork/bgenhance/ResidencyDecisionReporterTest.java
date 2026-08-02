package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

public final class ResidencyDecisionReporterTest {
    private static final String PACKAGE = "com.example.reporter.target";
    private static final int UID = 10_501;

    @Test
    public void mapsEnforcedDryRunPassThroughAndFailOpenResults() {
        assertEquals("blocked", ResidencyDecisionReporter.resultFor(
                decision(snapshot(true, false), index(snapshot(true, false)), PACKAGE)));

        RuntimeConfigSnapshot dryRun = snapshot(true, true);
        assertEquals("observed", ResidencyDecisionReporter.resultFor(
                decision(dryRun, index(dryRun), PACKAGE)));

        RuntimeConfigSnapshot enabled = snapshot(true, false);
        assertEquals("passed", ResidencyDecisionReporter.resultFor(
                decision(enabled, index(enabled), "com.example.reporter.unmanaged")));
        assertEquals("fail_open", ResidencyDecisionReporter.resultFor(
                decision(enabled, ResidencyIdentityIndex.invalidated(), PACKAGE)));
    }

    @Test
    public void mapsFreezeResultsWithoutChangingLockedPathSemantics() {
        RuntimeConfigSnapshot passThrough = snapshot(true, false);
        RuntimeConfigSnapshot blocked = freezeSnapshot(false);
        RuntimeConfigSnapshot dryRun = freezeSnapshot(true);

        assertEquals("passed", ResidencyDecisionReporter.resultFor(
                freezeDecision(passThrough, index(passThrough), PACKAGE)));
        assertEquals("blocked", ResidencyDecisionReporter.resultFor(
                freezeDecision(blocked, index(blocked), PACKAGE)));
        assertEquals("observed", ResidencyDecisionReporter.resultFor(
                freezeDecision(dryRun, index(dryRun), PACKAGE)));
        assertEquals("fail_open", ResidencyDecisionReporter.resultFor(
                freezeDecision(blocked, ResidencyIdentityIndex.invalidated(), PACKAGE)));
    }

    @Test
    public void mapsWakePolicyThrottleRuntimeAndDryRunResultsIndependently() {
        RuntimeConfigSnapshot passed = snapshot(true, false);
        assertEquals(DiagnosticsResult.PASSED, ResidencyDecisionReporter.resultFor(
                wakeDecision(passed, index(passed), WakeSource.ASYNC_BINDER),
                ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED));

        RuntimeConfigSnapshot blocked = wakeSnapshot(blockedWakePolicy(), false);
        assertEquals(DiagnosticsResult.BLOCKED, ResidencyDecisionReporter.resultFor(
                wakeDecision(blocked, index(blocked), WakeSource.ASYNC_BINDER),
                ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED));

        RuntimeConfigSnapshot throttled = wakeSnapshot(throttledWakePolicy(), false);
        WakeDecision throttleDecision = wakeDecision(
                throttled, index(throttled), WakeSource.PACKET);
        assertEquals(DiagnosticsResult.THROTTLE_ALLOWED,
                ResidencyDecisionReporter.resultFor(throttleDecision,
                        ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_ALLOWED));
        assertEquals(DiagnosticsResult.THROTTLE_BLOCKED,
                ResidencyDecisionReporter.resultFor(throttleDecision,
                        ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_BLOCKED));
        assertEquals(DiagnosticsResult.RUNTIME_FAIL_OPEN,
                ResidencyDecisionReporter.resultFor(throttleDecision,
                        ResidencyDecisionReporter.WakeRuntimeOutcome.FAIL_OPEN));

        RuntimeConfigSnapshot dryRun = wakeSnapshot(throttledWakePolicy(), true);
        assertEquals(DiagnosticsResult.OBSERVED, ResidencyDecisionReporter.resultFor(
                wakeDecision(dryRun, index(dryRun), WakeSource.PACKET),
                ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_BLOCKED));
        assertEquals(DiagnosticsResult.FAIL_OPEN, ResidencyDecisionReporter.resultFor(
                wakeDecision(throttled, ResidencyIdentityIndex.invalidated(), WakeSource.PACKET),
                ResidencyDecisionReporter.WakeRuntimeOutcome.FAIL_OPEN));
    }

    @Test
    public void mapsResourceKeepDryRunPassThroughAndFailOpenResults() {
        RuntimeConfigSnapshot kept = resourceSnapshot(false);
        RuntimeConfigSnapshot dryRun = resourceSnapshot(true);
        RuntimeConfigSnapshot passed = snapshot(true, false);

        assertEquals(DiagnosticsResult.BLOCKED, ResidencyDecisionReporter.resultFor(
                resourceDecision(kept, index(kept))));
        assertEquals(DiagnosticsResult.OBSERVED, ResidencyDecisionReporter.resultFor(
                resourceDecision(dryRun, index(dryRun))));
        assertEquals(DiagnosticsResult.PASSED, ResidencyDecisionReporter.resultFor(
                resourceDecision(passed, index(passed))));
        assertEquals(DiagnosticsResult.FAIL_OPEN, ResidencyDecisionReporter.resultFor(
                resourceDecision(kept, ResidencyIdentityIndex.invalidated())));
    }

    @Test
    public void mapsRefreezeDisabledDryRunSystemAndFailOpenResults() {
        RuntimeConfigSnapshot disabled = refreezeSnapshot(RefreezeMode.DISABLED, false);
        RuntimeConfigSnapshot dryRun = refreezeSnapshot(RefreezeMode.DISABLED, true);
        RuntimeConfigSnapshot system = refreezeSnapshot(RefreezeMode.SYSTEM, false);

        assertEquals(DiagnosticsResult.BLOCKED, ResidencyDecisionReporter.resultFor(
                refreezeDecision(disabled, index(disabled))));
        assertEquals(DiagnosticsResult.OBSERVED, ResidencyDecisionReporter.resultFor(
                refreezeDecision(dryRun, index(dryRun))));
        assertEquals(DiagnosticsResult.PASSED, ResidencyDecisionReporter.resultFor(
                refreezeDecision(system, index(system))));
        assertEquals(DiagnosticsResult.FAIL_OPEN, ResidencyDecisionReporter.resultFor(
                refreezeDecision(disabled, ResidencyIdentityIndex.invalidated())));
    }

    private static KillDecision decision(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            String packageName) {
        return new ResidencyDecisionEngine(snapshot, index).decideKill(new KillContext(
                new ResidencyTarget(packageName, UID, -1),
                KillContext.Path.OGUARD_KILL,
                FeatureIds.OGUARD_KILL,
                HookTargetIds.OGUARD_KILL));
    }

    private static ResidencyIdentityIndex index(RuntimeConfigSnapshot snapshot) {
        return ResidencyIdentityIndex.build(snapshot, Map.of(PACKAGE, UID));
    }

    private static FreezeDecision freezeDecision(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            String packageName) {
        return new ResidencyDecisionEngine(snapshot, index).decideFreeze(new FreezeContext(
                new ResidencyTarget(packageName, UID, -1),
                FreezeSource.NORMAL,
                FeatureIds.HANS,
                HookTargetIds.HANS_FREEZE_DISPATCH));
    }

    private static WakeDecision wakeDecision(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            WakeSource source) {
        return new ResidencyDecisionEngine(snapshot, index).decideWake(new WakeContext(
                new ResidencyTarget(PACKAGE, UID, -1),
                source,
                FeatureIds.HANS_WAKE,
                HookTargetIds.HANS_WAKE_DISPATCH));
    }

    private static ResourceDecision resourceDecision(
            RuntimeConfigSnapshot snapshot, ResidencyIdentityIndex index) {
        return new ResidencyDecisionEngine(snapshot, index).decideResource(
                ResourceContext.resource(
                        new ResidencyTarget(PACKAGE, UID, -1),
                        ResourceType.AUDIO,
                        FeatureIds.HANS_RESOURCE,
                        HookTargetIds.HANS_RESOURCE_DISPATCH));
    }

    private static RefreezeDecision refreezeDecision(
            RuntimeConfigSnapshot snapshot, ResidencyIdentityIndex index) {
        return new ResidencyDecisionEngine(snapshot, index).decideRefreeze(
                new RefreezeContext(
                        new ResidencyTarget(PACKAGE, UID, -1),
                        WakeSource.PACKET,
                        FeatureIds.HANS_REFREEZE,
                        HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static RuntimeConfigSnapshot snapshot(boolean master, boolean dryRun) {
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE, ResidencyMode.BALANCED);
        return RuntimeConfigSnapshot.available(
                1L, master, true, dryRun, 2L, Map.of(PACKAGE, policy));
    }

    private static RuntimeConfigSnapshot freezeSnapshot(boolean dryRun) {
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE, ResidencyMode.ACTIVE);
        return RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
    }

    private static RuntimeConfigSnapshot wakeSnapshot(
            WakePolicy wakePolicy, boolean dryRun) {
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                base.freezePolicy(),
                wakePolicy,
                base.resourcePolicy(),
                base.refreezePolicy());
        return RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
    }

    private static RuntimeConfigSnapshot resourceSnapshot(boolean dryRun) {
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                base.freezePolicy(),
                base.wakePolicy(),
                new ResourcePolicy(false, EnumSet.of(ResourceType.AUDIO)),
                base.refreezePolicy());
        return RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
    }

    private static RuntimeConfigSnapshot refreezeSnapshot(
            RefreezeMode mode, boolean dryRun) {
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                base.freezePolicy(),
                base.wakePolicy(),
                base.resourcePolicy(),
                new RefreezePolicy(mode, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
        return RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
    }

    private static WakePolicy blockedWakePolicy() {
        return new WakePolicy(
                WakeChannelPolicy.allow(),
                WakeChannelPolicy.allow(),
                EnumSet.of(WakeSource.ASYNC_BINDER));
    }

    private static WakePolicy throttledWakePolicy() {
        return new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 60_000L),
                WakeChannelPolicy.allow(),
                EnumSet.noneOf(WakeSource.class));
    }
}
