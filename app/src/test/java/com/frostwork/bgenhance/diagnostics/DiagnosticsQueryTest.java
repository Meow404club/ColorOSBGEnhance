package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import org.junit.Test;

public final class DiagnosticsQueryTest {
    @Test
    public void eventFilterCapsPageSizeAndRetainsKeysetCursor() {
        DiagnosticsQuery.EventCursor cursor = new DiagnosticsQuery.EventCursor(100L, 7L);
        DiagnosticsQuery.EventFilter filter = DiagnosticsQuery.EventFilter.builder()
                .fromInclusive(-1L)
                .toExclusive(0L)
                .packageName("com.example.target")
                .limit(20_000)
                .before(cursor)
                .build();

        assertEquals(0L, filter.fromInclusive);
        assertEquals(Long.MAX_VALUE, filter.toExclusive);
        assertEquals(200, filter.limit);
        assertEquals(cursor, filter.before);
    }

    @Test
    public void homepageSnapshotUsesLongTotalsAndKeepsMissingFeatures() {
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> features = new LinkedHashMap<>();
        features.put("freeze", new DiagnosticsQuery.FeatureStats(
                "freeze", 3_000_000_000L, 2L, 1L,
                10L, "", "", "", "", "blocked"));
        features.put("kill", DiagnosticsQuery.FeatureStats.empty("kill"));

        DiagnosticsQuery.HomeStatsSnapshot snapshot =
                new DiagnosticsQuery.HomeStatsSnapshot(features, "");

        assertEquals(3_000_000_000L, snapshot.observed);
        assertEquals(2L, snapshot.blocked);
        assertEquals(2, snapshot.features.size());
    }

    @Test
    public void redactedExportDefaultsNeverOptInToIdentifiers() {
        DiagnosticsQuery.ExportOptions options =
                DiagnosticsQuery.ExportOptions.redactedDefaults();

        assertFalse(options.includePackageNames);
        assertFalse(options.includeUids);
        assertTrue(options.recentEventLimit <= 2_000);
    }
}
