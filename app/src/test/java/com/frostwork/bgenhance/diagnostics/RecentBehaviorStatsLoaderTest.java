package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class RecentBehaviorStatsLoaderTest {
    @Test
    public void oneBoundedSnapshotProducesTotalsWithoutDroppingUnknowns()
            throws Exception {
        List<DiagnosticsQuery.AggregateQuery> queries = new ArrayList<>();
        RecentBehaviorStats stats = RecentBehaviorStatsLoader.load(query -> {
            queries.add(query);
            return CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                    List.of(
                            row("KILL", DiagnosticsResult.BLOCKED, 5L, 5L, 0L),
                            row("FUTURE_BEHAVIOR", DiagnosticsResult.FAIL_OPEN,
                                    3L, 0L, 3L)),
                    0,
                    false,
                    ""));
        }).get();

        assertTrue(stats.isSuccessful());
        assertEquals(8L, stats.observed);
        assertEquals(5L, stats.blocked);
        assertEquals(3L, stats.failOpen);
        assertEquals(2, stats.rows.size());
        assertEquals(DiagnosticsBehaviorClassifier.UNCLASSIFIED,
                stats.rows.get(1).behavior);
        assertEquals(1, queries.size());
        assertEquals(0, queries.get(0).offset);
        for (DiagnosticsQuery.AggregateQuery query : queries) {
            assertEquals(RecentBehaviorStatsLoader.QUERY_PAGE_SIZE, query.limit);
            assertTrue(query.dimensions.contains(
                    DiagnosticsQuery.AggregateDimension.BEHAVIOR));
            assertTrue(query.dimensions.contains(
                    DiagnosticsQuery.AggregateDimension.RESULT));
            assertEquals(2, query.dimensions.size());
        }
    }

    @Test
    public void truncatedSnapshotReturnsAnErrorInsteadOfPartialTotals() throws Exception {
        RecentBehaviorStats stats = RecentBehaviorStatsLoader.load(query ->
                CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                        Collections.singletonList(row(
                                "KILL", DiagnosticsResult.PASSED, 1L, 0L, 0L)),
                        query.limit,
                        true,
                        ""))).get();

        assertFalse(stats.isSuccessful());
        assertEquals("result_limit_exceeded", stats.errorCode);
        assertEquals(0L, stats.observed);
    }

    @Test
    public void retainedSnapshotPreservesHistoricalCleanupMarker() throws Exception {
        RecentBehaviorStats stats = RecentBehaviorStatsLoader.load(query ->
                CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                        Collections.singletonList(row(
                                "KILL", DiagnosticsResult.PASSED, 1L, 0L, 0L)),
                        0,
                        false,
                        true,
                        ""))).get();

        assertTrue(stats.isSuccessful());
        assertTrue(stats.historyTruncated);
        assertEquals(1L, stats.observed);
    }

    private static DiagnosticsQuery.AggregateRow row(
            String behavior,
            String result,
            long eventCount,
            long blockedCount,
            long failOpenCount) {
        return new DiagnosticsQuery.AggregateRow(
                "",
                -1,
                "",
                "",
                behavior,
                "",
                "",
                result,
                eventCount,
                eventCount,
                blockedCount,
                failOpenCount,
                eventCount,
                0L,
                0L,
                1L,
                1L);
    }
}
