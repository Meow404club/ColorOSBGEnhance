package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
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
}
