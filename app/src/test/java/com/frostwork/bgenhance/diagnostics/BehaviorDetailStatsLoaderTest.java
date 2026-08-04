package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class BehaviorDetailStatsLoaderTest {
    @Test
    public void knownBehaviorUsesAllDetailDimensionsInOneBoundedSnapshot()
            throws Exception {
        ArrayList<DiagnosticsQuery.AggregateQuery> queries = new ArrayList<>();
        BehaviorDetailStats snapshot = BehaviorDetailStatsLoader.load(query -> {
            queries.add(query);
            return CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                    List.of(
                            row("first", "KILL", "", 2L),
                            row("second", "KILL", "", 3L)),
                    0, false, ""));
        }, DiagnosticsBehaviorClassifier.KILL).get(5, TimeUnit.SECONDS);

        assertTrue(snapshot.isSuccessful());
        assertEquals(2, snapshot.rows.size());
        assertEquals(1, queries.size());
        assertEquals(0, queries.get(0).offset);
        for (DiagnosticsQuery.AggregateQuery query : queries) {
            assertEquals(DiagnosticsBehaviorClassifier.KILL, query.behavior);
            assertTrue(query.dimensions.contains(DiagnosticsQuery.AggregateDimension.FEATURE));
            assertTrue(query.dimensions.contains(DiagnosticsQuery.AggregateDimension.BEHAVIOR));
            assertTrue(query.dimensions.contains(DiagnosticsQuery.AggregateDimension.SOURCE));
            assertTrue(query.dimensions.contains(DiagnosticsQuery.AggregateDimension.DECISION));
            assertTrue(query.dimensions.contains(DiagnosticsQuery.AggregateDimension.RESULT));
            assertEquals(BehaviorDetailStatsLoader.QUERY_PAGE_SIZE, query.limit);
        }
    }

    @Test
    public void unclassifiedLoadsAllStoredBehaviorsForClientSideNormalization()
            throws Exception {
        ArrayList<DiagnosticsQuery.AggregateQuery> queries = new ArrayList<>();
        BehaviorDetailStats snapshot = BehaviorDetailStatsLoader.load(query -> {
            queries.add(query);
            return CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                    Collections.singletonList(
                            row("future", "FUTURE_BEHAVIOR", "FUTURE_SOURCE", 4L)),
                    0, false, ""));
        }, DiagnosticsBehaviorClassifier.UNCLASSIFIED).get(5, TimeUnit.SECONDS);

        assertTrue(snapshot.isSuccessful());
        assertEquals(1, snapshot.rows.size());
        assertEquals("", queries.get(0).behavior);
    }

    @Test
    public void truncatedSnapshotAndBackendErrorsAreExplicitFailures() throws Exception {
        BehaviorDetailStats truncatedSnapshot = BehaviorDetailStatsLoader.load(query ->
                CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                        Collections.emptyList(), query.limit, true, "")),
                DiagnosticsBehaviorClassifier.KILL).get(5, TimeUnit.SECONDS);
        assertFalse(truncatedSnapshot.isSuccessful());
        assertEquals("result_limit_exceeded", truncatedSnapshot.errorCode);

        BehaviorDetailStats backendError = BehaviorDetailStatsLoader.load(query ->
                CompletableFuture.completedFuture(
                        DiagnosticsQuery.AggregatePage.empty("database_unavailable")),
                DiagnosticsBehaviorClassifier.KILL).get(5, TimeUnit.SECONDS);
        assertFalse(backendError.isSuccessful());
        assertEquals("database_unavailable", backendError.errorCode);
    }

    @Test
    public void retainedSnapshotPreservesHistoricalCleanupMarker() throws Exception {
        BehaviorDetailStats snapshot = BehaviorDetailStatsLoader.load(query ->
                CompletableFuture.completedFuture(new DiagnosticsQuery.AggregatePage(
                        Collections.singletonList(row("first", "KILL", "", 1L)),
                        0, false, true, "")),
                DiagnosticsBehaviorClassifier.KILL).get(5, TimeUnit.SECONDS);

        assertTrue(snapshot.isSuccessful());
        assertTrue(snapshot.historyTruncated);
        assertEquals(1, snapshot.rows.size());
    }

    private static DiagnosticsQuery.AggregateRow row(
            String feature, String behavior, String source, long count) {
        return new DiagnosticsQuery.AggregateRow(
                "", -1, feature, "hook", behavior, source, "decision",
                DiagnosticsResult.PASSED,
                count, count, 0L, 0L, count, count, count, 1L, 2L);
    }
}
