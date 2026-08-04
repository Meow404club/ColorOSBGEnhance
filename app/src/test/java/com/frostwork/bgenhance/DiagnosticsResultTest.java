package com.frostwork.bgenhance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiagnosticsResultTest {
    @Test
    public void blockedCountersIncludeThrottleWindowBlocks() {
        assertTrue(DiagnosticsResult.countsAsBlocked(DiagnosticsResult.BLOCKED));
        assertTrue(DiagnosticsResult.countsAsBlocked(DiagnosticsResult.THROTTLE_BLOCKED));
        assertFalse(DiagnosticsResult.countsAsBlocked(DiagnosticsResult.PASSED));
        assertFalse(DiagnosticsResult.countsAsBlocked(DiagnosticsResult.THROTTLE_ALLOWED));
        assertFalse(DiagnosticsResult.countsAsBlocked(DiagnosticsResult.RECOVERED));
        assertFalse(DiagnosticsResult.countsAsBlocked(DiagnosticsResult.APPLIED));
        assertTrue(DiagnosticsResult.countsAsBlocked(
                DiagnosticsResult.CANDIDATE_FILTERED));
    }

    @Test
    public void failOpenCountersIncludePolicyConfigurationAndRuntimeFallbacks() {
        assertTrue(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.FAIL_OPEN));
        assertTrue(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.CONFIG_UNAVAILABLE));
        assertTrue(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.RUNTIME_FAIL_OPEN));
        assertFalse(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.PASSED));
        assertFalse(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.THROTTLE_BLOCKED));
        assertFalse(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.NO_ACTION_NEEDED));
        assertFalse(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.SAFETY_ABORTED));
        assertFalse(DiagnosticsResult.countsAsFailOpen(DiagnosticsResult.APPLIED));
        assertFalse(DiagnosticsResult.countsAsFailOpen(
                DiagnosticsResult.CANDIDATE_FILTERED));
    }

    @Test
    public void anomalousFailOpenCountersExcludeProtectiveCompatibilityOutcomes() {
        assertTrue(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "STALE_IDENTITY_INDEX"));
        assertTrue(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "FAIL_OPEN"));
        assertTrue(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.RUNTIME_FAIL_OPEN, "SYSTEM_UID"));
        assertTrue(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.CONFIG_UNAVAILABLE, "CONFIG_UNAVAILABLE"));
        assertFalse(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "IDENTITY_UNKNOWN"));
        assertFalse(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "IDENTITY_UID_MISMATCH"));
        assertFalse(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "SHARED_UID_CONFLICT"));
        assertFalse(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "SYSTEM_UID"));
        assertFalse(DiagnosticsResult.countsAsAnomalousFailOpen(
                DiagnosticsResult.FAIL_OPEN, "MASTER_DISABLED"));
    }

    @Test
    public void appliedIsAStableKnownResult() {
        assertTrue(DiagnosticsResult.isKnown(DiagnosticsResult.APPLIED));
    }
}
