package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class HookDiagnosticsTest {
    @Test
    public void configReportIdentityIncludesSourcePathAndError() {
        HookDiagnostics.ConfigReportKey envelope = key(
                "runtime_envelope", "config", "");

        assertEquals(envelope, key("runtime_envelope", "config", ""));
        assertNotEquals(envelope, key("legacy_preferences", "config", ""));
        assertNotEquals(envelope, key("runtime_envelope", "other", ""));
        assertNotEquals(envelope, key(
                "runtime_envelope", "config", "invalid_document"));
    }

    @Test
    public void configReportIdentityIncludesRevision() {
        HookDiagnostics.ConfigReportKey first = key(7, "runtime_envelope", "config", "");
        HookDiagnostics.ConfigReportKey second = key(8, "runtime_envelope", "config", "");

        assertNotEquals(first, second);
    }

    private static HookDiagnostics.ConfigReportKey key(
            String source, String path, String error) {
        return key(7, source, path, error);
    }

    private static HookDiagnostics.ConfigReportKey key(
            int revision, String source, String path, String error) {
        return new HookDiagnostics.ConfigReportKey(revision, source, path, error);
    }
}
