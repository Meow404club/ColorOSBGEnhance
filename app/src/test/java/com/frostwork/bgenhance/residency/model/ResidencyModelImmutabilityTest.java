package com.frostwork.bgenhance.residency.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.testing.ResidencyTestFixtures;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

public final class ResidencyModelImmutabilityTest {
    @Test
    public void documentDefensivelyCopiesRules() {
        List<AppResidencyPolicy> input = new ArrayList<>();
        input.add(ResidencyPresets.create(
                ResidencyTestFixtures.MANAGED_PACKAGE, ResidencyMode.BALANCED));

        ResidencyPolicyDocument document = new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                7L,
                true,
                ResidencyMode.BALANCED,
                input);
        input.clear();

        assertEquals(1, document.rules().size());
        assertThrows(UnsupportedOperationException.class, () -> document.rules().clear());
    }

    @Test
    public void wakePolicyDefensivelyCopiesBlockedSources() {
        EnumSet<WakeSource> blocked = EnumSet.of(WakeSource.SERVICE);
        WakePolicy policy = new WakePolicy(
                WakeChannelPolicy.allow(), WakeChannelPolicy.allow(), blocked);
        blocked.add(WakeSource.BROADCAST);

        assertEquals(WakeMode.BLOCK, policy.modeFor(WakeSource.SERVICE));
        assertEquals(WakeMode.ALLOW, policy.modeFor(WakeSource.BROADCAST));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.blockedSources().add(WakeSource.JOB_SYNC));
    }

    @Test
    public void resourcePolicyDefensivelyCopiesKeptResources() {
        EnumSet<ResourceType> kept = EnumSet.of(ResourceType.AUDIO);
        ResourcePolicy policy = new ResourcePolicy(true, kept);
        kept.add(ResourceType.GPS);

        assertTrue(policy.keepNetwork());
        assertTrue(policy.keeps(ResourceType.AUDIO));
        assertFalse(policy.keeps(ResourceType.GPS));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.keptResources().add(ResourceType.JOB));
    }
}
