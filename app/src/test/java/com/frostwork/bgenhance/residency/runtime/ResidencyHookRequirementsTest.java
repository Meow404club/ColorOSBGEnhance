package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class ResidencyHookRequirementsTest {
    @Test
    public void disabledAndSystemPoliciesRequireNoHooks() {
        assertTrue(ResidencyHookRequirements.forDocument(new ResidencyPolicyDocument(
                1, 1L, false, ResidencyMode.BALANCED,
                Collections.singletonList(policy(true, ResidencyMode.CUSTOM,
                        KillPolicy.all(), FreezePolicy.followSystem(),
                        WakePolicy.allowAll(), ResourcePolicy.followSystem(),
                        RefreezePolicy.followSystem())))).isEmpty());
        assertTrue(ResidencyHookRequirements.forPolicy(policy(
                true, ResidencyMode.SYSTEM, KillPolicy.all(),
                ResidencySectionPresets.freezePolicy(
                        ResidencySectionPresets.FreezePreset.NEVER_FREEZE),
                ResidencySectionPresets.wakePolicy(
                        ResidencySectionPresets.WakePreset.STRICT_POWER_SAVING),
                ResidencySectionPresets.resourcePolicy(
                        ResidencySectionPresets.ResourcePreset.MESSAGE_SYNC),
                ResidencySectionPresets.refreezePolicy(
                        ResidencySectionPresets.RefreezePreset.AFTER_WAKE))).isEmpty());
    }

    @Test
    public void currentCustomBoundaryOnlyRequiresSelectedCapabilities() {
        Set<String> targets = ResidencyHookRequirements.forPolicy(policy(
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.all(),
                new FreezePolicy(
                        true, true, true, true, true,
                        2_000L, 5_000L, false, false, false),
                ResidencySectionPresets.wakePolicy(
                        ResidencySectionPresets.WakePreset.STRICT_POWER_SAVING),
                ResourcePolicy.followSystem(),
                ResidencySectionPresets.refreezePolicy(
                        ResidencySectionPresets.RefreezePreset.AFTER_WAKE)));

        assertTrue(targets.containsAll(Set.of(
                HookTargetIds.OGUARD_KILL,
                HookTargetIds.OGUARD_FORCE_STOP,
                HookTargetIds.ATHENA_PERIODIC,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                HookTargetIds.TOURNAMENT,
                HookTargetIds.MEMORY_GUARD_DISPATCH,
                HookTargetIds.OSENSE_GROUP_CLEAN,
                HookTargetIds.OSENSE_PROCESS_KILL,
                HookTargetIds.HANS_R_TO_M_TIMING,
                HookTargetIds.HANS_M_TO_F_TIMING,
                HookTargetIds.HANS_WAKE_DISPATCH,
                HookTargetIds.ALARM_WAKE_DELIVERY)));
        assertFalse(targets.contains(HookTargetIds.HANS_CORE_EXEMPTION));
        assertFalse(targets.contains(HookTargetIds.HANS_LCD_ON_EXEMPTION));
        assertFalse(targets.contains(HookTargetIds.HANS_RESOURCE_DISPATCH));
        assertFalse(targets.contains(HookTargetIds.HANS_FREEZE_DISPATCH));
        assertFalse(targets.contains(HookTargetIds.HANS_FAST_FREEZE_DISPATCH));
        assertEquals(12, targets.size());
    }

    @Test
    public void selectedAdvancedCapabilitiesRestoreTheirHealthRequirements() {
        FreezePolicy freeze = new FreezePolicy(
                true, true, true, true, false,
                0L, 0L, false, true, true);
        ResourcePolicy resources = ResidencySectionPresets.resourcePolicy(
                ResidencySectionPresets.ResourcePreset.MESSAGE_SYNC);
        Set<String> targets = ResidencyHookRequirements.forPolicy(policy(
                true, ResidencyMode.CUSTOM, KillPolicy.none(), freeze,
                WakePolicy.allowAll(), resources, RefreezePolicy.followSystem()));

        assertTrue(targets.contains(HookTargetIds.HANS_CORE_EXEMPTION));
        assertTrue(targets.contains(HookTargetIds.HANS_LCD_ON_EXEMPTION));
        assertTrue(targets.contains(HookTargetIds.HANS_RESOURCE_DISPATCH));
        assertTrue(targets.contains(HookTargetIds.HANS_ACTIVE_RECOVERY));
    }

    @Test
    public void fullExemptionRequiresEveryPhysicalFreezeEnforcementPath() {
        FreezePolicy freeze = new FreezePolicy(
                false, false, false, false, true,
                1_000L, 1_000L, true, true, false);
        Set<String> targets = ResidencyHookRequirements.forPolicy(policy(
                true, ResidencyMode.CUSTOM, KillPolicy.none(), freeze,
                ResidencySectionPresets.wakePolicy(
                        ResidencySectionPresets.WakePreset.STRICT_POWER_SAVING),
                ResourcePolicy.followSystem(), RefreezePolicy.followSystem()));

        assertEquals(Set.of(
                HookTargetIds.HANS_FREEZE_DISPATCH,
                HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
                HookTargetIds.HANS_LCD_ON_EXEMPTION,
                HookTargetIds.HANS_ACTIVE_RECOVERY,
                HookTargetIds.HANS_M_TO_F_TIMING), targets);
    }

    @Test
    public void ignoredWakeSourcesDoNotCreateHookRequirements() {
        WakePolicy ignored = new WakePolicy(
                WakeChannelPolicy.allow(),
                WakeChannelPolicy.allow(),
                EnumSet.of(
                        WakeSource.PACKET,
                        WakeSource.ALARM,
                        WakeSource.ACTIVITY_INPUT,
                        WakeSource.INTERNAL_OPERATION));
        Set<String> targets = ResidencyHookRequirements.forPolicy(policy(
                true, ResidencyMode.CUSTOM, KillPolicy.none(),
                FreezePolicy.followSystem(), ignored,
                ResourcePolicy.followSystem(), RefreezePolicy.followSystem()));

        assertFalse(targets.contains(HookTargetIds.HANS_WAKE_DISPATCH));
        assertFalse(targets.contains(HookTargetIds.ALARM_WAKE_DELIVERY));
    }

    @Test
    public void documentCombinesEnabledRulesOnly() {
        AppResidencyPolicy active = policy(
                true, ResidencyMode.CUSTOM, new KillPolicy(
                        true, false, false, false, false, false, false, false),
                FreezePolicy.followSystem(), WakePolicy.allowAll(),
                ResourcePolicy.followSystem(), RefreezePolicy.followSystem());
        AppResidencyPolicy disabled = policy(
                false, ResidencyMode.CUSTOM, KillPolicy.all(),
                FreezePolicy.followSystem(), WakePolicy.allowAll(),
                ResourcePolicy.followSystem(), RefreezePolicy.followSystem());
        ResidencyPolicyDocument document = new ResidencyPolicyDocument(
                1, 2L, true, ResidencyMode.BALANCED, List.of(active, disabled));

        assertEquals(Collections.singleton(HookTargetIds.OGUARD_KILL),
                ResidencyHookRequirements.forDocument(document));
    }

    private static AppResidencyPolicy policy(
            boolean enabled,
            ResidencyMode mode,
            KillPolicy kill,
            FreezePolicy freeze,
            WakePolicy wake,
            ResourcePolicy resources,
            RefreezePolicy refreeze) {
        return new AppResidencyPolicy(
                "com.example.target", enabled, mode,
                kill, freeze, wake, resources, refreeze);
    }
}
