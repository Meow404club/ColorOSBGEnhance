package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiagnosticsEventNormalizerTest {
    @Test
    public void rawHitAndNormalizedEventRemainSeparate() {
        RawHookHit raw = rawHit(true);
        DiagnosticsEventNormalizer.DecisionData decision = decision();

        DiagnosticsEventNormalizer.Result result =
                DiagnosticsEventNormalizer.normalize(raw, decision);

        assertTrue(result.accepted());
        assertEquals("FREEZE", result.event.behavior);
        assertEquals("BLOCK_FREEZE", result.event.decision);
        assertEquals("com.example.target", result.event.packageName);
        assertEquals(8, result.event.configRevision);
        assertEquals(11, result.event.policyRevision);
        assertEquals("call-7", result.event.eventToken);
    }

    @Test
    public void unparsedHitIsStillStorableButDoesNotProduceUserEvent() {
        RawHookHit raw = rawHit(false);

        DiagnosticsEventNormalizer.Result result =
                DiagnosticsEventNormalizer.normalize(raw, decision());

        assertTrue(raw.isStorable());
        assertFalse(result.accepted());
        assertEquals("hook_arguments_unparsed", result.rejectionReason);
    }

    @Test
    public void malformedDecisionFailsOpenWithoutThrowing() {
        DiagnosticsEventNormalizer.DecisionData decision = new DiagnosticsEventNormalizer.DecisionData();

        DiagnosticsEventNormalizer.Result result =
                DiagnosticsEventNormalizer.normalize(rawHit(true), decision);

        assertFalse(result.accepted());
        assertEquals("invalid_decision", result.rejectionReason);
    }

    @Test
    public void dtoBoundsUntrustedStringsAndNegativeLatency() {
        String oversized = "x".repeat(2_000);
        NormalizedDiagnosticsEvent value = NormalizedDiagnosticsEvent.builder()
                .processId("system_server")
                .featureId("feature")
                .hookTargetId("target")
                .behavior("KILL")
                .decision("BLOCK_KILL")
                .result("blocked")
                .packageName(oversized)
                .failOpenReason(oversized)
                .latencyMicros(-9L)
                .build();

        assertEquals(DiagnosticsValues.MAX_PACKAGE_LENGTH, value.packageName.length());
        assertEquals(DiagnosticsValues.MAX_ERROR_LENGTH, value.failOpenReason.length());
        assertEquals(0L, value.latencyMicros);
    }

    private static RawHookHit rawHit(boolean parsed) {
        return RawHookHit.builder()
                .timestampMillis(1_700_000_000_000L)
                .bootId("42")
                .processId("system_server")
                .featureId("hans_freeze")
                .hookTargetId("hans_freeze_dispatch")
                .configRevision(8)
                .policyRevision(11)
                .parsed(parsed)
                .identityResolved(true)
                .build();
    }

    private static DiagnosticsEventNormalizer.DecisionData decision() {
        DiagnosticsEventNormalizer.DecisionData value =
                new DiagnosticsEventNormalizer.DecisionData();
        value.behavior = "FREEZE";
        value.source = "FAST";
        value.packageName = "com.example.target";
        value.uid = 10123;
        value.mode = "CUSTOM";
        value.decision = "BLOCK_FREEZE";
        value.result = "blocked";
        value.latencyMicros = 73L;
        value.eventToken = "call-7";
        return value;
    }
}
