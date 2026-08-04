package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class FeatureCatalogIdentifierTest {
    private static final List<String> GROUPS = Arrays.asList(
            FeatureCatalog.GROUP_FAST_CLEAR,
            FeatureCatalog.GROUP_MEMORY,
            FeatureCatalog.GROUP_NIRVANA,
            FeatureCatalog.GROUP_FREEZE
    );

    @Test
    public void everyEntryUsesKnownFeatureAndHookTargetIds() {
        Set<String> featureIds = new HashSet<>(FeatureIds.all());
        Set<String> hookTargetIds = new HashSet<>(HookTargetIds.all());
        int count = 0;
        for (String group : GROUPS) {
            for (FeatureCatalog.FeatureSpec spec : FeatureCatalog.forGroup(group)) {
                assertTrue(featureIds.contains(spec.featureId));
                assertTrue(hookTargetIds.contains(spec.hookTargetId));
                assertFalse(spec.prefKey.isEmpty());
                count++;
            }
        }
        assertEquals(12, count);
    }

    @Test
    public void catalogPreservesExistingOneToManyMappings() {
        assertGroupTargets(FeatureCatalog.GROUP_FAST_CLEAR,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                HookTargetIds.TOURNAMENT);
        assertGroupTargets(FeatureCatalog.GROUP_MEMORY,
                HookTargetIds.MEMORY_GUARD_DISPATCH,
                HookTargetIds.MEMORY_GUARD_DISPATCH);
        assertGroupTargets(FeatureCatalog.GROUP_NIRVANA,
                HookTargetIds.OSENSE_PROCESS_KILL,
                HookTargetIds.OSENSE_PROCESS_KILL,
                HookTargetIds.OSENSE_PROCESS_KILL);
        assertGroupTargets(FeatureCatalog.GROUP_FREEZE,
                HookTargetIds.HANS_FREEZE_DISPATCH,
                HookTargetIds.HANS_FREEZE_DISPATCH);

        List<FeatureCatalog.FeatureSpec> freeze =
                FeatureCatalog.forGroup(FeatureCatalog.GROUP_FREEZE);
        assertFalse(freeze.get(0).diagnosticOnly);
        assertFalse(freeze.get(1).diagnosticOnly);
    }

    private static void assertGroupTargets(String group, String... expected) {
        List<FeatureCatalog.FeatureSpec> specs = FeatureCatalog.forGroup(group);
        assertEquals(expected.length, specs.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], specs.get(i).hookTargetId);
        }
    }
}
