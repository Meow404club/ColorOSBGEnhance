package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiagnosticsWriteBatchTest {
    private static final int TEST_WIRE_VERSION = 3;

    @Test
    public void identityHasStableValueSemanticsForBackendDeduplication() {
        DiagnosticsWriteBatch.BatchIdentity first = identity(7L);
        DiagnosticsWriteBatch.BatchIdentity retry = identity(7L);
        DiagnosticsWriteBatch.BatchIdentity next = identity(8L);
        DiagnosticsWriteBatch.BatchIdentity restarted = new DiagnosticsWriteBatch.BatchIdentity(
                TEST_WIRE_VERSION,
                "boot-42", "system_server", "sender-8", 3L, 7L);

        assertEquals(first, retry);
        assertEquals(first.hashCode(), retry.hashCode());
        assertEquals(first.deduplicationKey(), retry.deduplicationKey());
        assertNotEquals(first, next);
        assertNotEquals(first, restarted);
        assertNotEquals(first.deduplicationKey(), next.deduplicationKey());
        assertEquals(TEST_WIRE_VERSION, first.wireVersion);
        assertEquals("boot-42", first.bootId);
        assertEquals("system_server", first.processId);
        assertEquals("sender-7", first.senderInstanceId);
        assertEquals(3L, first.epoch);
        assertEquals(7L, first.batchSequence);
    }

    @Test
    public void typedAndLegacyPayloadsCannotBeMixedByBuilder() {
        DiagnosticsWriteBatch.Builder typed = DiagnosticsWriteBatch.builder()
                .addRawHit(rawHit());
        assertThrows(
                IllegalStateException.class,
                () -> typed.addLegacyFeatureDelta(legacyDelta()));

        DiagnosticsWriteBatch.Builder legacy = DiagnosticsWriteBatch.builder()
                .addLegacyFeatureDelta(legacyDelta());
        assertThrows(IllegalStateException.class, () -> legacy.addEvent(event()));
        assertThrows(
                IllegalStateException.class,
                () -> DiagnosticsWriteBatch.builder()
                        .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY)
                        .addHookState(hookState()));
    }

    @Test
    public void modeIsInferredForLocalBatchesAndExplicitForWireBatches() {
        DiagnosticsWriteBatch local = DiagnosticsWriteBatch.builder()
                .addRawHit(rawHit())
                .addEvent(event())
                .addHookState(hookState())
                .build();
        DiagnosticsWriteBatch wire = DiagnosticsWriteBatch.builder()
                .batchIdentity(identity(9L))
                .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY)
                .addLegacyFeatureDelta(legacyDelta())
                .build();

        assertEquals(DiagnosticsWriteBatch.CountingMode.TYPED, local.countingMode);
        assertFalse(local.hasBatchIdentity());
        assertEquals(DiagnosticsWriteBatch.CountingMode.LEGACY, wire.countingMode);
        assertTrue(wire.hasBatchIdentity());
        assertEquals(1, wire.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> wire.legacyFeatureDeltas.add(legacyDelta()));
    }

    @Test
    public void conflictingBatchIdentitiesCannotBeMerged() {
        DiagnosticsWriteBatch first = DiagnosticsWriteBatch.builder()
                .batchIdentity(identity(1L))
                .addRawHit(rawHit())
                .build();
        DiagnosticsWriteBatch second = DiagnosticsWriteBatch.builder()
                .batchIdentity(identity(2L))
                .addRawHit(rawHit())
                .build();

        DiagnosticsWriteBatch.Builder merged = DiagnosticsWriteBatch.builder().addAll(first);
        assertThrows(IllegalStateException.class, () -> merged.addAll(second));
    }

    @Test
    public void identityRejectsValuesThatWouldBeNormalizedOrCollide() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsWriteBatch.BatchIdentity(
                        TEST_WIRE_VERSION,
                        " boot-42",
                        "system_server",
                        "sender-7",
                        0L,
                        0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsWriteBatch.BatchIdentity(
                        TEST_WIRE_VERSION,
                        "boot-42",
                        "system_server",
                        "sender-7",
                        -1L,
                        0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsWriteBatch.BatchIdentity(
                        TEST_WIRE_VERSION,
                        "boot-42",
                        "system_server",
                        "",
                        0L,
                        0L));
    }

    @Test
    public void hookStateCountsAndRuntimeIdentityAreExplicit() {
        DiagnosticsHookState valid = hookState();
        DiagnosticsHookState inconsistent = DiagnosticsHookState.builder()
                .processId("system_server")
                .bootId("boot-42")
                .hookTargetId("freeze_target")
                .rawHitCount(1L)
                .parsedCount(2L)
                .build();

        assertTrue(valid.isStorable());
        assertTrue(valid.matchesBatchIdentity(identity(1L)));
        assertFalse(inconsistent.hasConsistentCounts());
        assertFalse(inconsistent.isStorable());
    }

    private static DiagnosticsWriteBatch.BatchIdentity identity(long sequence) {
        return new DiagnosticsWriteBatch.BatchIdentity(
                TEST_WIRE_VERSION,
                "boot-42",
                "system_server",
                "sender-7",
                3L,
                sequence);
    }

    private static RawHookHit rawHit() {
        return RawHookHit.builder()
                .timestampMillis(1_700_000_000_000L)
                .bootId("boot-42")
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .build();
    }

    private static NormalizedDiagnosticsEvent event() {
        return NormalizedDiagnosticsEvent.builder()
                .timestampMillis(1_700_000_000_000L)
                .bootId("boot-42")
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .behavior("FREEZE")
                .decision("BLOCK_FREEZE")
                .result("blocked")
                .build();
    }

    private static DiagnosticsHookState hookState() {
        return DiagnosticsHookState.builder()
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .installStatus("INSTALLED")
                .bootId("boot-42")
                .rawHitCount(3L)
                .parsedCount(2L)
                .identityResolvedCount(1L)
                .updatedAt(1_700_000_000_000L)
                .build();
    }

    private static DiagnosticsWriteBatch.LegacyFeatureDelta legacyDelta() {
        return new DiagnosticsWriteBatch.LegacyFeatureDelta(
                "freeze", 1L, 1L, 0L, 1_700_000_000_000L,
                "com.example.target", "system_server", "FAST", "7", "blocked");
    }
}
