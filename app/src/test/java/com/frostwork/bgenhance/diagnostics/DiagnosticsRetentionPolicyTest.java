package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiagnosticsRetentionPolicyTest {
    @Test
    public void defaultsBoundEveryGrowingResource() {
        DiagnosticsRetentionPolicy value = DiagnosticsRetentionPolicy.defaults();

        assertTrue(value.retentionMillis > 0L);
        assertTrue(value.recentEventLimit > 0);
        assertTrue(value.aggregateRowLimit > 0);
        assertTrue(value.rawAggregateRowLimit > 0);
        assertTrue(value.hookStateLimit > 0);
        assertTrue(value.featureTotalLimit > 0);
        assertTrue(value.queueCapacity > 0);
        assertTrue(value.batchSize <= value.queueCapacity);
        assertTrue(value.maintenanceIntervalMillis > 0L);
        assertTrue(value.emergencyCleanupRowLimit > 0);
        assertTrue(value.maxDatabaseBytes >= 1_048_576L);
    }

    @Test
    public void customPolicyIsImmutable() {
        DiagnosticsRetentionPolicy value = DiagnosticsRetentionPolicy.builder()
                .retentionMillis(1_000L)
                .recentEventLimit(3)
                .aggregateRowLimit(4)
                .rawAggregateRowLimit(5)
                .hookStateLimit(6)
                .featureTotalLimit(7)
                .queueCapacity(8)
                .batchSize(2)
                .flushDelayMillis(0L)
                .maintenanceIntervalMillis(25L)
                .emergencyCleanupRowLimit(9)
                .maxDatabaseBytes(1_048_576L)
                .build();

        assertEquals(3, value.recentEventLimit);
        assertEquals(8, value.queueCapacity);
        assertEquals(0L, value.flushDelayMillis);
        assertEquals(25L, value.maintenanceIntervalMillis);
        assertEquals(9, value.emergencyCleanupRowLimit);
    }

    @Test
    public void invalidLimitsAreRejectedBeforeWorkerStartup() {
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().queueCapacity(1).batchSize(2).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().recentEventLimit(0).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().maxDatabaseBytes(10L).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().queueCapacity(8_193).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder()
                        .queueCapacity(1_000).batchSize(257).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().maintenanceIntervalMillis(0L).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().emergencyCleanupRowLimit(8_193).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder().maxDatabaseBytes(1_048_577L).build());
        assertThrows(IllegalArgumentException.class, () ->
                DiagnosticsRetentionPolicy.builder()
                        .maxDatabaseBytes(65L * 1_024L * 1_024L).build());
    }
}
