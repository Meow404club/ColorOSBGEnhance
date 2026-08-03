package com.frostwork.bgenhance.residency.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.ResidencySectionPresets.FreezePreset;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets.KillPreset;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets.RefreezePreset;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets.ResourcePreset;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets.WakePreset;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

public final class ResidencySectionPresetsTest {
    @Test
    public void killPresetsUseExactAuthoritativeValuesAndRoundTrip() {
        assertEquals(KillPolicy.none(),
                ResidencySectionPresets.killPolicy(KillPreset.FOLLOW_SYSTEM));
        assertEquals(KillPolicy.all(),
                ResidencySectionPresets.killPolicy(KillPreset.FULL_PROTECTION));

        assertEquals(KillPreset.FOLLOW_SYSTEM,
                ResidencySectionPresets.matchKillPolicy(KillPolicy.none()));
        assertEquals(KillPreset.FULL_PROTECTION,
                ResidencySectionPresets.matchKillPolicy(KillPolicy.all()));
        assertEquals(KillPreset.CUSTOM, ResidencySectionPresets.matchKillPolicy(
                new KillPolicy(true, false, false, false, false, false, false, false)));
    }

    @Test
    public void freezePresetsUseNormalizedValuesAndRoundTrip() {
        assertFreezePreset(FreezePreset.FOLLOW_SYSTEM, new FreezePolicy(
                true, true, true, true, false, 0L, 0L, false, false, false));
        assertFreezePreset(FreezePreset.POWER_SAVING, new FreezePolicy(
                true, true, true, true, true, 60_000L, 60_000L,
                false, false, false));
        assertFreezePreset(FreezePreset.KEEP_WHILE_SCREEN_ON, new FreezePolicy(
                true, true, true, true, false, 0L, 0L, false, false, true));
        assertFreezePreset(FreezePreset.NEVER_FREEZE, new FreezePolicy(
                false, false, false, false, false, 0L, 0L, true, false, false));

        assertEquals(FreezePreset.CUSTOM, ResidencySectionPresets.matchFreezePolicy(
                new FreezePolicy(false, false, false, false, false, 1L, 0L,
                        true, false, false)));
    }

    @Test
    public void wakePresetsUseExactChannelsAndConfigurableSources() {
        WakePolicy normal = ResidencySectionPresets.wakePolicy(WakePreset.NORMAL);
        assertEquals(WakeChannelPolicy.allow(), normal.packet());
        assertEquals(WakeChannelPolicy.allow(), normal.alarm());
        assertTrue(normal.blockedSources().isEmpty());

        WakePolicy reduced = ResidencySectionPresets.wakePolicy(WakePreset.REDUCED_FREQUENCY);
        assertEquals(new WakeChannelPolicy(WakeMode.THROTTLE, 300_000L), reduced.packet());
        assertEquals(new WakeChannelPolicy(WakeMode.THROTTLE, 900_000L), reduced.alarm());
        assertTrue(reduced.blockedSources().isEmpty());

        WakePolicy strict = ResidencySectionPresets.wakePolicy(WakePreset.STRICT_POWER_SAVING);
        assertEquals(new WakeChannelPolicy(WakeMode.BLOCK, 0L), strict.packet());
        assertEquals(new WakeChannelPolicy(WakeMode.BLOCK, 0L), strict.alarm());
        for (WakeSource source : WakeSource.values()) {
            boolean expectedBlocked = !source.isAlwaysAllowed()
                    && source != WakeSource.PACKET
                    && source != WakeSource.ALARM;
            assertEquals(source.name(), expectedBlocked, strict.blockedSources().contains(source));
        }

        for (WakePreset preset : new WakePreset[] {
                WakePreset.NORMAL,
                WakePreset.REDUCED_FREQUENCY,
                WakePreset.STRICT_POWER_SAVING
        }) {
            assertEquals(preset, ResidencySectionPresets.matchWakePolicy(
                    ResidencySectionPresets.wakePolicy(preset)));
        }
        assertEquals(WakePreset.CUSTOM, ResidencySectionPresets.matchWakePolicy(
                new WakePolicy(
                        new WakeChannelPolicy(WakeMode.THROTTLE, 299_999L),
                        WakeChannelPolicy.allow(),
                        Collections.emptySet())));
    }

    @Test
    public void resourcePresetsUseExactResourceSetsAndRoundTrip() {
        assertResourcePreset(ResourcePreset.FOLLOW_SYSTEM, false,
                EnumSet.noneOf(ResourceType.class));
        assertResourcePreset(ResourcePreset.MESSAGE_SYNC, true,
                EnumSet.of(ResourceType.SERVICE, ResourceType.BROADCAST, ResourceType.BINDER));
        assertResourcePreset(ResourcePreset.AUDIO_PLAYBACK, true,
                EnumSet.of(ResourceType.SERVICE, ResourceType.BINDER,
                        ResourceType.WAKE_LOCK, ResourceType.AUDIO));
        assertResourcePreset(ResourcePreset.NAVIGATION, true,
                EnumSet.of(ResourceType.SERVICE, ResourceType.SENSOR,
                        ResourceType.WAKE_LOCK, ResourceType.GPS));

        assertEquals(ResourcePreset.CUSTOM, ResidencySectionPresets.matchResourcePolicy(
                new ResourcePolicy(false, EnumSet.of(ResourceType.SERVICE))));
    }

    @Test
    public void refreezePresetsUseExactDelaysAndRoundTrip() {
        assertRefreezePreset(RefreezePreset.FOLLOW_SYSTEM, new RefreezePolicy(
                RefreezeMode.SYSTEM, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
        assertRefreezePreset(RefreezePreset.AFTER_WAKE, new RefreezePolicy(
                RefreezeMode.AFTER_WAKE,
                60_000L, 60_000L, 120_000L, 60_000L, 60_000L, 60_000L, 60_000L));
        assertRefreezePreset(RefreezePreset.DISABLED, new RefreezePolicy(
                RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L));

        assertEquals(RefreezePreset.CUSTOM, ResidencySectionPresets.matchRefreezePolicy(
                new RefreezePolicy(
                        RefreezeMode.DISABLED, 1L, 0L, 0L, 0L, 0L, 0L, 0L)));
    }

    @Test
    public void customEntriesCannotBeExpandedIntoInventedPolicies() {
        assertThrows(IllegalArgumentException.class,
                () -> ResidencySectionPresets.killPolicy(KillPreset.CUSTOM));
        assertThrows(IllegalArgumentException.class,
                () -> ResidencySectionPresets.freezePolicy(FreezePreset.CUSTOM));
        assertThrows(IllegalArgumentException.class,
                () -> ResidencySectionPresets.wakePolicy(WakePreset.CUSTOM));
        assertThrows(IllegalArgumentException.class,
                () -> ResidencySectionPresets.resourcePolicy(ResourcePreset.CUSTOM));
        assertThrows(IllegalArgumentException.class,
                () -> ResidencySectionPresets.refreezePolicy(RefreezePreset.CUSTOM));
    }

    @Test
    public void matchersRejectNullPolicies() {
        assertThrows(NullPointerException.class,
                () -> ResidencySectionPresets.matchKillPolicy(null));
        assertThrows(NullPointerException.class,
                () -> ResidencySectionPresets.matchFreezePolicy(null));
        assertThrows(NullPointerException.class,
                () -> ResidencySectionPresets.matchWakePolicy(null));
        assertThrows(NullPointerException.class,
                () -> ResidencySectionPresets.matchResourcePolicy(null));
        assertThrows(NullPointerException.class,
                () -> ResidencySectionPresets.matchRefreezePolicy(null));
    }

    private static void assertFreezePreset(FreezePreset preset, FreezePolicy expected) {
        FreezePolicy actual = ResidencySectionPresets.freezePolicy(preset);
        assertEquals(expected, actual);
        assertEquals(preset, ResidencySectionPresets.matchFreezePolicy(actual));
    }

    private static void assertResourcePreset(
            ResourcePreset preset,
            boolean keepNetwork,
            EnumSet<ResourceType> resources) {
        ResourcePolicy actual = ResidencySectionPresets.resourcePolicy(preset);
        assertEquals(keepNetwork, actual.keepNetwork());
        assertEquals(resources, actual.keptResources());
        assertEquals(preset, ResidencySectionPresets.matchResourcePolicy(actual));
    }

    private static void assertRefreezePreset(RefreezePreset preset, RefreezePolicy expected) {
        RefreezePolicy actual = ResidencySectionPresets.refreezePolicy(preset);
        assertEquals(expected, actual);
        assertEquals(preset, ResidencySectionPresets.matchRefreezePolicy(actual));
    }
}
