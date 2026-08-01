package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.config.GlobalFeatureConfig;
import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Map;
import org.junit.Test;

public final class HookRuntimeConfigStateTest {
    @Test
    public void availableStateBuildsOneMatchingDecisionSnapshot() {
        RuntimeConfigDocument document = new RuntimeConfigDocument(
                RuntimeConfigDocument.DOCUMENT_TYPE,
                RuntimeConfigDocument.CURRENT_SCHEMA_VERSION,
                17L,
                100L,
                new GlobalFeatureConfig(
                        true,
                        true,
                        false,
                        "custom",
                        Map.of("block_oguard_kill", false)),
                ResidencyPolicyDocument.empty());

        HookRuntimeConfigState state = HookRuntimeConfigState.available(
                document, "runtime_envelope", 200L);

        assertTrue(state.available());
        assertEquals(17L, state.revision());
        assertEquals(17L, state.snapshot().configRevision());
        assertTrue(state.snapshot().available());
        assertTrue(state.snapshot().dryRun());
        assertFalse(state.snapshot().featureEnabled("block_oguard_kill", true));
        assertEquals("runtime_envelope", state.source());
    }

    @Test
    public void unavailableStateDropsDocumentAndEnforceableSnapshot() {
        HookRuntimeConfigState state = HookRuntimeConfigState.unavailable(
                "invalid_document", 16L, 300L);

        assertFalse(state.available());
        assertNull(state.document());
        assertFalse(state.snapshot().available());
        assertFalse(state.snapshot().masterEnabled());
        assertEquals(16L, state.lastGoodRevision());
        assertEquals("invalid_document", state.error());
    }
}
