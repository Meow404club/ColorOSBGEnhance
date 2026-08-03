package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureIds;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class RecentBehaviorStatsRetentionTest {
    private Context context;
    private String databaseName;
    private ResidencyDiagnosticsDb database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        databaseName = "recent-behavior-stats-" + System.nanoTime() + ".db";
        DiagnosticsRetentionPolicy policy = DiagnosticsRetentionPolicy.builder()
                .retentionMillis(60_000L)
                .recentEventLimit(20)
                .aggregateRowLimit(2)
                .rawAggregateRowLimit(20)
                .hookStateLimit(20)
                .featureTotalLimit(20)
                .batchCursorLimit(20)
                .queueCapacity(20)
                .batchSize(20)
                .maxDatabaseBytes(2L * 1_024L * 1_024L)
                .build();
        database = new ResidencyDiagnosticsDb(context, databaseName, policy, "test_boot");
    }

    @After
    public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
    }

    @Test
    public void retainedAggregatesDriveBothTotalsWithoutInventingLegacyBehaviors()
            throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(database.migrateLegacyOnce(DiagnosticsWriteBatch.builder()
                .addLegacyFeatureDelta(new DiagnosticsWriteBatch.LegacyFeatureDelta(
                        FeatureIds.HANS,
                        100L,
                        40L,
                        10L,
                        now - 4_000L,
                        "com.example.legacy",
                        "system_server",
                        "legacy",
                        "legacy",
                        DiagnosticsResult.BLOCKED))
                .build()));

        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addEvent(event(now - 3_000L, "old", "KILL", DiagnosticsResult.PASSED))
                .addEvent(event(now - 2_000L, "blocked", "FREEZE", DiagnosticsResult.BLOCKED))
                .addEvent(event(now - 1_000L, "future", "FUTURE_BEHAVIOR",
                        DiagnosticsResult.FAIL_OPEN))
                .build());
        database.performMaintenance(now, false);

        RecentBehaviorStats stats = RecentBehaviorStatsLoader.load(
                query -> CompletableFuture.completedFuture(database.queryAggregates(query)))
                .get(5, TimeUnit.SECONDS);

        assertTrue(stats.isSuccessful());
        assertEquals(2L, stats.observed);
        assertEquals(1L, stats.blocked);
        assertEquals(1L, stats.failOpen);
        assertEquals(2, stats.rows.size());
        assertTrue(stats.rows.stream().anyMatch(row ->
                DiagnosticsBehaviorClassifier.UNCLASSIFIED.equals(row.behavior)));

        long rowObserved = stats.rows.stream().mapToLong(row -> row.eventCount).sum();
        long rowBlocked = stats.rows.stream().mapToLong(row -> row.blockedCount).sum();
        long rowFailOpen = stats.rows.stream().mapToLong(row -> row.failOpenCount).sum();
        assertEquals(stats.observed, rowObserved);
        assertEquals(stats.blocked, rowBlocked);
        assertEquals(stats.failOpen, rowFailOpen);

        DiagnosticsQuery.HomeStatsSnapshot legacyFeatureTotals = database.queryHomeStats(
                Collections.singletonList(FeatureIds.HANS));
        assertEquals(103L, legacyFeatureTotals.observed);
        assertEquals(41L, legacyFeatureTotals.blocked);
        assertEquals(11L, legacyFeatureTotals.failOpen);
    }

    private static NormalizedDiagnosticsEvent event(
            long timestamp,
            String token,
            String behavior,
            String result) {
        return NormalizedDiagnosticsEvent.builder()
                .timestampMillis(timestamp)
                .bootId("test_boot")
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId("test_hook")
                .behavior(behavior)
                .source("test")
                .packageName("com.example.target")
                .uid(10_001)
                .decision("test_decision")
                .result(result)
                .eventToken(token)
                .build();
    }
}
