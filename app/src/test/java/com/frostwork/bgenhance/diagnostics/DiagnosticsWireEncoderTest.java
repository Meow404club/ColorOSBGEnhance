package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DiagnosticsWireEncoderTest {
    @Test
    public void typedBatchRoundTripsThroughExactWireParser() {
        RawHookHit raw = RawHookHit.builder()
                .timestampMillis(1_700_000_000_000L)
                .bootId("boot-42")
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                .configRevision(7)
                .policyRevision(11)
                .parsed(true)
                .identityResolved(true)
                .errorCode("")
                .build();
        NormalizedDiagnosticsEvent event = NormalizedDiagnosticsEvent.builder()
                .timestampMillis(1_700_000_000_001L)
                .bootId("boot-42")
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                .behavior("FREEZE")
                .source("FAST")
                .packageName("com.example.target")
                .uid(10_123)
                .scene("cgroup_v2_fast")
                .reasonCode(1)
                .configRevision(7)
                .policyRevision(11)
                .mode("BALANCED")
                .decision("POLICY_BLOCKS_FREEZE")
                .result(DiagnosticsResult.BLOCKED)
                .failOpenReason("")
                .latencyMicros(123L)
                .rawHitCount(1)
                .identityResolved(true)
                .eventToken("event-42")
                .build();
        DiagnosticsHookState state = DiagnosticsHookState.builder()
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                .installStatus("INSTALLED:1")
                .bootId("boot-42")
                .lastHookAt(1_700_000_000_002L)
                .lastEventAt(1_700_000_000_001L)
                .rawHitCount(5L)
                .parsedCount(4L)
                .identityResolvedCount(3L)
                .configRevision(7)
                .policyRevision(11)
                .lastError("")
                .updatedAt(1_700_000_000_003L)
                .build();
        DiagnosticsWriteBatch input = DiagnosticsWriteBatch.builder()
                .wireIdentity(2, "boot-42", "system_server", "sender-42", 3L, 9L)
                .countingMode(DiagnosticsWriteBatch.CountingMode.TYPED)
                .addRawHit(raw)
                .addEvent(event)
                .addHookState(state)
                .build();

        DiagnosticsWireParser.ParseResult parsed = new DiagnosticsWireParser()
                .parseSnapshot(DiagnosticsWireEncoder.encodeTyped(input),
                        1_700_000_000_100L);

        assertTrue(parsed.accepted());
        assertEquals(3, parsed.acceptedItems);
        assertEquals(input.batchIdentity, parsed.batch.batchIdentity);
        assertEquals(DiagnosticsWriteBatch.CountingMode.TYPED, parsed.batch.countingMode);
        assertEquals(raw.featureId, parsed.batch.rawHits.get(0).featureId);
        assertEquals(raw.hookTargetId, parsed.batch.rawHits.get(0).hookTargetId);
        assertEquals(event.packageName, parsed.batch.events.get(0).packageName);
        assertEquals(event.mode, parsed.batch.events.get(0).mode);
        assertEquals(event.eventToken, parsed.batch.events.get(0).eventToken);
        assertEquals(state.installStatus, parsed.batch.hookStates.get(0).installStatus);
        assertEquals(state.identityResolvedCount,
                parsed.batch.hookStates.get(0).identityResolvedCount);
    }

    @Test(expected = IllegalArgumentException.class)
    public void encoderRejectsLegacyCountingMode() {
        DiagnosticsWriteBatch legacy = DiagnosticsWriteBatch.builder()
                .wireIdentity(2, "boot", "system_server", "sender", 0L, 0L)
                .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY)
                .addLegacyFeatureDelta(new DiagnosticsWriteBatch.LegacyFeatureDelta(
                        FeatureIds.HANS, 1L, 0L, 0L, 1L,
                        "", "system_server", "", "", DiagnosticsResult.PASSED))
                .build();

        DiagnosticsWireEncoder.encodeTyped(legacy);
    }
}
