package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.config.GlobalFeatureConfig;
import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.runtime.HookRuntimeConfigState;
import java.util.Map;
import org.junit.Test;

public final class DecisionTest {
    @Test
    public void unavailableStateAlwaysFailsOpen() {
        HookRuntimeConfigState state = HookRuntimeConfigState.unavailable(
                "invalid_document", 7L, 100L);

        assertEquals(
                Decision.Outcome.CONFIG_UNAVAILABLE,
                Decision.evaluate(state, ModuleSettings.KEY_OGUARD_KILL));
    }

    @Test
    public void oneAvailableStateDeterminesTheWholeLegacyGate() {
        HookRuntimeConfigState enabled = state(true, false, true);
        HookRuntimeConfigState dryRun = state(true, true, true);
        HookRuntimeConfigState disabled = state(true, false, false);

        assertEquals(
                Decision.Outcome.BLOCKED,
                Decision.evaluate(enabled, ModuleSettings.KEY_OGUARD_KILL));
        assertEquals(
                Decision.Outcome.OBSERVED,
                Decision.evaluate(dryRun, ModuleSettings.KEY_OGUARD_KILL));
        assertEquals(
                Decision.Outcome.PASSED,
                Decision.evaluate(disabled, ModuleSettings.KEY_OGUARD_KILL));
    }

    @Test
    public void absentFeatureUsesItsDeclaredDefaultFromTheSameDocument() {
        HookRuntimeConfigState state = state(true, false, true);

        assertEquals(
                Decision.Outcome.PASSED,
                Decision.evaluate(state, ModuleSettings.KEY_FAST_AI));
        assertEquals(
                Decision.Outcome.PASSED,
                Decision.evaluate(state, "unknown.feature"));
    }

    private static HookRuntimeConfigState state(
            boolean masterEnabled, boolean dryRun, boolean oguardEnabled) {
        RuntimeConfigDocument document = new RuntimeConfigDocument(
                RuntimeConfigDocument.DOCUMENT_TYPE,
                RuntimeConfigDocument.CURRENT_SCHEMA_VERSION,
                8L,
                0L,
                new GlobalFeatureConfig(
                        masterEnabled,
                        dryRun,
                        false,
                        ModuleSettings.PRESET_CUSTOM,
                        Map.of(ModuleSettings.KEY_OGUARD_KILL, oguardEnabled)),
                ResidencyPolicyDocument.empty());
        return HookRuntimeConfigState.available(document, "runtime_envelope", 100L);
    }
}
