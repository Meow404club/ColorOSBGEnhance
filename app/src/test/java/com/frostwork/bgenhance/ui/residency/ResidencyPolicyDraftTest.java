package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.EnumSet;
import org.junit.Test;

public final class ResidencyPolicyDraftTest {
    private static final String PACKAGE_NAME = "com.example.resident";

    @Test
    public void everyPresetModeExpandsThroughAuthoritativePresetDefinitions() {
        for (ResidencyMode mode : new ResidencyMode[] {
                ResidencyMode.SYSTEM,
                ResidencyMode.BALANCED,
                ResidencyMode.LOW_POWER,
                ResidencyMode.ACTIVE
        }) {
            AppResidencyPolicy preset = ResidencyPresets.create(PACKAGE_NAME, mode);
            ResidencyPolicyDraft draft = new ResidencyPolicyDraft(
                    false,
                    mode,
                    KillPolicy.none(),
                    FreezePolicy.followSystem(),
                    WakePolicy.allowAll(),
                    ResourcePolicy.followSystem(),
                    RefreezePolicy.followSystem());

            AppResidencyPolicy actual = draft.toPolicy(PACKAGE_NAME);

            assertFalse(actual.enabled());
            assertEquals(mode, actual.mode());
            assertEquals(preset.killPolicy(), actual.killPolicy());
            assertEquals(preset.freezePolicy(), actual.freezePolicy());
            assertEquals(preset.wakePolicy(), actual.wakePolicy());
            assertEquals(preset.resourcePolicy(), actual.resourcePolicy());
            assertEquals(preset.refreezePolicy(), actual.refreezePolicy());
        }
    }

    @Test
    public void customModePreservesEveryPolicyFamilyExactly() {
        KillPolicy kill = new KillPolicy(true, false, true, false, true, false, true, false);
        FreezePolicy freeze = new FreezePolicy(
                false, true, false, true, true, 2_000L, 3_000L, true, false, true);
        WakePolicy wake = new WakePolicy(
                new WakeChannelPolicy(WakeMode.THROTTLE, 4_000L),
                new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                EnumSet.of(WakeSource.SERVICE, WakeSource.BROADCAST));
        ResourcePolicy resources = new ResourcePolicy(
                true, EnumSet.of(ResourceType.AUDIO, ResourceType.GPS));
        RefreezePolicy refreeze = new RefreezePolicy(
                RefreezeMode.AFTER_WAKE,
                5_000L,
                6_000L,
                7_000L,
                8_000L,
                9_000L,
                10_000L,
                11_000L);
        ResidencyPolicyDraft draft = new ResidencyPolicyDraft(
                true, ResidencyMode.CUSTOM, kill, freeze, wake, resources, refreeze);

        AppResidencyPolicy policy = draft.toPolicy(PACKAGE_NAME);

        assertEquals(kill, policy.killPolicy());
        assertEquals(freeze, policy.freezePolicy());
        assertEquals(wake, policy.wakePolicy());
        assertEquals(resources, policy.resourcePolicy());
        assertEquals(refreeze, policy.refreezePolicy());
        assertEquals(draft.toPolicy(PACKAGE_NAME),
                ResidencyPolicyDraft.from(policy).toPolicy(PACKAGE_NAME));
    }
}
