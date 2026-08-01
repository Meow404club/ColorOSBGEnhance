package com.frostwork.bgenhance;

import com.frostwork.bgenhance.residency.config.GlobalFeatureConfig;
import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import com.frostwork.bgenhance.residency.runtime.HookRuntimeConfigState;

public final class Decision {
    private Decision() {}

    public static boolean shouldBlock(String prefKey, HookEvent event) {
        HookRuntimeConfigState configState = HookConfig.state();
        Outcome outcome = evaluate(configState, prefKey);
        if (outcome == Outcome.CONFIG_UNAVAILABLE) {
            ModuleLog.w("config unavailable; fail-open for " + event.feature);
            HookDiagnostics.recordEvent(event, "config_unavailable");
            return false;
        }
        String result = outcome.wireValue;
        boolean block = outcome == Outcome.BLOCKED;
        HookDiagnostics.recordEvent(event, result);
        ModuleLog.d(event + " result=" + result
                + " configRevision=" + configState.revision());
        return block;
    }

    static Outcome evaluate(HookRuntimeConfigState state, String prefKey) {
        if (state == null || !state.available()) return Outcome.CONFIG_UNAVAILABLE;
        RuntimeConfigDocument document = state.document();
        if (document == null) return Outcome.CONFIG_UNAVAILABLE;
        GlobalFeatureConfig global = document.global();
        Boolean configured = global.legacyFeatures().get(prefKey);
        boolean selected = configured != null
                ? configured
                : ModuleSettings.defaultFor(prefKey);
        if (!global.masterEnabled() || !selected) return Outcome.PASSED;
        return global.dryRun() ? Outcome.OBSERVED : Outcome.BLOCKED;
    }

    enum Outcome {
        CONFIG_UNAVAILABLE("config_unavailable"),
        PASSED("passed"),
        OBSERVED("observed"),
        BLOCKED("blocked");

        final String wireValue;

        Outcome(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}
