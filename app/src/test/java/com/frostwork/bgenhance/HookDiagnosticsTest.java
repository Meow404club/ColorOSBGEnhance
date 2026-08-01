package com.frostwork.bgenhance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HookDiagnosticsTest {
    @Test
    public void configReportIdentityIncludesSourcePathAndError() {
        HookDiagnostics.ConfigReportState envelope = state(
                "runtime_envelope", "config", "");

        assertTrue(envelope.sameConfigurationAs(
                state("runtime_envelope", "config", "")));
        assertFalse(envelope.sameConfigurationAs(
                state("legacy_preferences", "config", "")));
        assertFalse(envelope.sameConfigurationAs(
                state("runtime_envelope", "other", "")));
        assertFalse(envelope.sameConfigurationAs(
                state("runtime_envelope", "config", "invalid_document")));
    }

    @Test
    public void configReportIdentityIncludesRevision() {
        HookDiagnostics.ConfigReportState first = state(
                7, "runtime_envelope", "config", 1L, "");
        HookDiagnostics.ConfigReportState second = state(
                8, "runtime_envelope", "config", 1L, "");

        assertFalse(first.sameConfigurationAs(second));
    }

    @Test
    public void latencyDoesNotTurnOneConfigStateIntoAFalseTransition() {
        HookDiagnostics.ConfigReportState first = state(
                7, "runtime_envelope", "config", 1L, "invalid");
        HookDiagnostics.ConfigReportState retry = state(
                7, "runtime_envelope", "config", 2_000L, "invalid");

        assertTrue(first.sameConfigurationAs(retry));
        assertNotEquals(first.reloadLatencyMs, retry.reloadLatencyMs);
    }

    private static HookDiagnostics.ConfigReportState state(
            String source, String path, String error) {
        return state(7, source, path, 1L, error);
    }

    private static HookDiagnostics.ConfigReportState state(
            int revision, String source, String path, long latencyMs, String error) {
        return new HookDiagnostics.ConfigReportState(
                revision, source, path, latencyMs, error);
    }
}
