package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ResidencyDiagnosticsDbTest {
    private Context context;
    private String databaseName;
    private ResidencyDiagnosticsDb database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        databaseName = "diagnostics-test-" + System.nanoTime() + ".db";
    }

    @After
    public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
    }

    @Test
    public void writesRawAndNormalizedLayersInOneTransaction() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch batch = DiagnosticsWriteBatch.builder()
                .addRawHit(raw(now, true, true))
                .addEvent(event(now, "token-1", "com.example.target", 10123,
                        "FREEZE", "blocked"))
                .addHookState(state(now, "INSTALLED"))
                .build();

        database.writeBatch(batch);

        DiagnosticsQuery.EventPage events = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build());
        assertEquals(1, events.items.size());
        assertEquals("token-1", events.items.get(0).eventToken);
        assertEquals(1L, database.queryRawHooks(0L, Long.MAX_VALUE, 10).get(0).hitCount);
        DiagnosticsQuery.HomeStatsSnapshot home =
                database.queryHomeStats(Collections.singletonList("freeze"));
        assertEquals(1L, home.observed);
        assertEquals(1L, home.blocked);
        List<DiagnosticsHookState> states = database.queryHookStates(10);
        assertEquals(1, states.size());
        assertEquals("INSTALLED", states.get(0).installStatus);
        assertEquals(1L, states.get(0).rawHitCount);
        assertEquals(1L, states.get(0).parsedCount);
    }

    @Test
    public void legacyMigrationDataAndMarkerCommitExactlyOnce() {
        database = open(policy(20, 20, 20, 60_000L));
        DiagnosticsWriteBatch first = DiagnosticsWriteBatch.builder()
                .addLegacyFeatureDelta(new DiagnosticsWriteBatch.LegacyFeatureDelta(
                        "legacy_feature", 7L, 3L, 1L, 100L,
                        "com.example.target", "system_server", "scene", "9", "blocked"))
                .build();
        DiagnosticsWriteBatch second = DiagnosticsWriteBatch.builder()
                .addLegacyFeatureDelta(new DiagnosticsWriteBatch.LegacyFeatureDelta(
                        "legacy_feature", 100L, 50L, 10L, 200L,
                        "other", "system_server", "other", "10", "blocked"))
                .build();

        assertTrue(database.migrateLegacyOnce(first));
        assertFalse(database.migrateLegacyOnce(second));

        DiagnosticsQuery.FeatureStats stats = database.queryHomeStats(
                Collections.singletonList("legacy_feature"))
                .features.get("legacy_feature");
        assertEquals(7L, stats.observed);
        assertEquals(3L, stats.blocked);
        assertEquals(1L, stats.failOpen);
        assertEquals("com.example.target", stats.lastPackage);

        database.close();
        database = open(policy(20, 20, 20, 60_000L));
        assertFalse(database.migrateLegacyOnce(second));
        assertEquals(7L, database.queryHomeStats(
                Collections.singletonList("legacy_feature")).observed);
    }

    @Test
    public void eventTokenDeduplicatesNormalizedEventsButNotRawHits() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        NormalizedDiagnosticsEvent duplicate = event(
                now, "same-call", "com.example.target", 10123, "FREEZE", "blocked");

        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addRawHit(raw(now, true, true))
                .addEvent(duplicate)
                .build());
        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addRawHit(raw(now + 1L, true, true))
                .addEvent(duplicate)
                .build());

        assertEquals(1, database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build()).items.size());
        assertEquals(2L, database.queryRawHooks(0L, Long.MAX_VALUE, 10).get(0).hitCount);
        assertEquals(1L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
    }

    @Test
    public void retentionBoundsAgeAndRowsAcrossDetailedTables() {
        database = open(policy(3, 2, 2, 1_000L));
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch.Builder batch = DiagnosticsWriteBatch.builder()
                .addRawHit(raw(now - 10_000L, false, false))
                .addEvent(event(now - 10_000L, "old", "old.pkg", 1,
                        "KILL", "passed"));
        for (int index = 0; index < 5; index++) {
            long at = now + index;
            batch.addRawHit(RawHookHit.builder()
                    .timestampMillis(at)
                    .processId("system_server")
                    .featureId("feature-" + index)
                    .hookTargetId("target-" + index)
                    .parsed(true)
                    .build());
            batch.addEvent(event(at, "token-" + index, "pkg-" + index, 100 + index,
                    "BEHAVIOR_" + index, index % 2 == 0 ? "blocked" : "passed"));
        }

        database.writeBatch(batch.build());
        database.performMaintenance(now, false);

        DiagnosticsQuery.EventPage events = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().limit(20).build());
        assertEquals(3, events.items.size());
        assertFalse(events.items.stream().anyMatch(value -> "old.pkg".equals(value.packageName)));
        assertEquals(2, database.queryAggregates(
                DiagnosticsQuery.AggregateQuery.builder()
                        .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                        .limit(20)
                        .build()).items.size());
        assertEquals(2, database.queryRawHooks(0L, Long.MAX_VALUE, 20).size());
    }

    @Test
    public void rejectsTransactionsLargerThanProtocolMaximumWithoutPartialWrites() {
        DiagnosticsRetentionPolicy bounded = DiagnosticsRetentionPolicy.builder()
                .recentEventLimit(20)
                .aggregateRowLimit(20)
                .rawAggregateRowLimit(20)
                .hookStateLimit(20)
                .featureTotalLimit(20)
                .queueCapacity(4)
                .batchSize(2)
                .maxDatabaseBytes(2L * 1_024L * 1_024L)
                .build();
        database = open(bounded);
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch.Builder oversizedBuilder = DiagnosticsWriteBatch.builder();
        for (int index = 0;
                index <= DiagnosticsRetentionPolicy.MAX_ACCEPTED_BATCH_ITEMS;
                index++) {
            oversizedBuilder.addEvent(event(
                    now + index,
                    "oversized-" + index,
                    "pkg." + index,
                    index,
                    "FREEZE",
                    "blocked"));
        }
        DiagnosticsWriteBatch oversized = oversizedBuilder.build();

        assertThrows(IllegalArgumentException.class, () -> database.writeBatch(oversized));

        assertTrue(database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build()).items.isEmpty());
    }

    @Test
    public void storagePressureCleanupDeletesNoMoreThanItsRowBudget() {
        DiagnosticsRetentionPolicy bounded = DiagnosticsRetentionPolicy.builder()
                .retentionMillis(60_000L)
                .recentEventLimit(20)
                .aggregateRowLimit(20)
                .rawAggregateRowLimit(20)
                .hookStateLimit(20)
                .featureTotalLimit(20)
                .queueCapacity(20)
                .batchSize(20)
                .emergencyCleanupRowLimit(3)
                .maxDatabaseBytes(2L * 1_024L * 1_024L)
                .build();
        database = open(bounded);
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch.Builder batch = DiagnosticsWriteBatch.builder();
        for (int index = 0; index < 10; index++) {
            batch.addEvent(event(now + index, "pressure-" + index,
                    "pkg-" + index, index, "FREEZE", "blocked"));
        }
        database.writeBatch(batch.build());

        database.performMaintenance(now, true);

        assertEquals(7, database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().limit(20).build()).items.size());
    }

    @Test
    public void filtersAndKeysetPaginationAreParameterizedAndStable() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch.Builder batch = DiagnosticsWriteBatch.builder();
        for (int index = 0; index < 5; index++) {
            batch.addEvent(event(now + index, "t-" + index,
                    index == 0 ? "x' OR 1=1 --" : "com.example.target",
                    10123, "FREEZE", index < 4 ? "runtime_fail_open" : "blocked"));
        }
        database.writeBatch(batch.build());

        DiagnosticsQuery.EventFilter firstFilter = DiagnosticsQuery.EventFilter.builder()
                .packageName("com.example.target")
                .failOpenOnly(true)
                .limit(2)
                .build();
        DiagnosticsQuery.EventPage first = database.queryEvents(firstFilter);
        DiagnosticsQuery.EventPage second = database.queryEvents(
                firstFilter.nextPage(first.nextCursor).build());
        DiagnosticsQuery.EventPage injection = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder()
                        .packageName("x' OR 1=1 --")
                        .build());

        assertEquals(2, first.items.size());
        assertNotNull(first.nextCursor);
        assertEquals(1, second.items.size());
        assertNull(second.nextCursor);
        assertEquals(1, injection.items.size());
        assertNull(injection.nextCursor);
    }

    @Test
    public void aggregationSupportsApplicationBehaviorHookAndDecisionDimensions() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addEvent(event(now, "one", "pkg.a", 1, "FREEZE", "blocked"))
                .addEvent(event(now + 1L, "two", "pkg.a", 1, "FREEZE", "blocked"))
                .addEvent(event(now + 2L, "three", "pkg.b", 2, "KILL", "passed"))
                .build());

        DiagnosticsQuery.AggregatePage rows = database.queryAggregates(
                DiagnosticsQuery.AggregateQuery.builder()
                        .dimension(DiagnosticsQuery.AggregateDimension.APPLICATION)
                        .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                        .dimension(DiagnosticsQuery.AggregateDimension.HOOK_TARGET)
                        .dimension(DiagnosticsQuery.AggregateDimension.DECISION)
                        .limit(20)
                        .build());

        assertEquals(2, rows.items.size());
        assertEquals(2L, rows.items.get(0).eventCount);
        assertEquals("pkg.a", rows.items.get(0).packageName);
        assertEquals("freeze_target", rows.items.get(0).hookTargetId);
    }

    @Test
    public void clearScopesKeepHookInstallationStateUnlessRequested() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addRawHit(raw(now, true, true))
                .addEvent(event(now, "token", "pkg", 1, "FREEZE", "blocked"))
                .addHookState(state(now, "INSTALLED"))
                .build());

        DiagnosticsQuery.ClearResult result =
                database.clear(DiagnosticsQuery.ClearScope.USER_ACTIVITY);

        assertTrue(result.success);
        assertTrue(database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build()).items.isEmpty());
        assertEquals(0L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
        List<DiagnosticsHookState> retained = database.queryHookStates(10);
        assertEquals(1, retained.size());
        assertEquals("INSTALLED", retained.get(0).installStatus);
        assertEquals(0L, retained.get(0).rawHitCount);
        assertEquals(0L, retained.get(0).parsedCount);
        assertEquals(0L, retained.get(0).identityResolvedCount);
        assertEquals(0L, retained.get(0).lastHookAt);
        assertEquals(0L, retained.get(0).lastEventAt);
        assertEquals(7, retained.get(0).configRevision);
        assertEquals(9, retained.get(0).policyRevision);
        database.clear(DiagnosticsQuery.ClearScope.ALL);
        assertTrue(database.queryHookStates(10).isEmpty());
    }

    @Test
    public void versionedBatchesAreAppliedAtMostOnceAndAdvanceMonotonically() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch first = versionedBatch(
                0L, 1L, event(now, "first", "pkg.first", 1, "FREEZE", "blocked"));
        DiagnosticsWriteBatch second = versionedBatch(
                0L, 2L, event(now + 1L, "second", "pkg.second", 2, "FREEZE", "blocked"));

        database.writeBatch(first);
        database.writeBatch(first);
        database.writeBatch(second);
        database.writeBatch(first);

        DiagnosticsQuery.EventPage events = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().limit(20).build());
        assertEquals(2, events.items.size());
        assertEquals(2L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
    }

    @Test
    public void currentEpochCursorSurvivesRetentionAndStillRejectsReplay() {
        database = open(policy(20, 20, 20, 1L, 4));
        long now = System.currentTimeMillis();
        DiagnosticsWriteBatch batch = versionedBatch(
                0L, 0L, "sender-a",
                event(now, "retained-cursor", "pkg.first", 1, "FREEZE", "blocked"));

        assertEquals(ResidencyDiagnosticsStorage.WriteResult.COMMITTED,
                database.writeBatch(batch));
        database.performMaintenance(now + 10_000L, false);
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.REPLAYED,
                database.writeBatch(batch));

        assertEquals(1L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
    }

    @Test
    public void newSenderInstanceCanRestartSequenceWithinSameBoot() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();

        database.writeBatch(versionedBatch(
                0L, 0L, "sender-before-restart",
                event(now, "before-restart", "pkg.first", 1, "FREEZE", "blocked")));
        database.writeBatch(versionedBatch(
                0L, 0L, "sender-after-restart",
                event(now + 1L, "after-restart", "pkg.second", 2, "FREEZE", "blocked")));

        assertEquals(2L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
    }

    @Test
    public void cursorLocksCountingModeUntilEpochChanges() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        database.writeBatch(versionedBatch(
                0L, 0L, "sender-a",
                event(now, "typed", "pkg.typed", 1, "FREEZE", "blocked")));
        DiagnosticsWriteBatch legacy = DiagnosticsWriteBatch.builder()
                .wireIdentity(2, "42", "system_server", "sender-a", 0L, 1L)
                .addLegacyFeatureDelta(new DiagnosticsWriteBatch.LegacyFeatureDelta(
                        "freeze", 10L, 10L, 0L, now + 1L,
                        "pkg.legacy", "system_server", "FAST", "7", "blocked"))
                .build();

        database.writeBatch(legacy);

        assertEquals(1L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
    }

    @Test
    public void cursorCapacityRejectsNewIdentityUntilClearAdvancesEpoch() {
        database = open(policy(20, 20, 20, 60_000L, 1));
        long now = System.currentTimeMillis();
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.COMMITTED,
                database.writeBatch(versionedBatch(
                        0L, 0L, "sender-a",
                        event(now, "sender-a", "pkg.first", 1,
                                "FREEZE", "blocked"))));
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.REJECTED_CAPACITY,
                database.writeBatch(versionedBatch(
                        0L, 0L, "sender-b",
                        event(now + 1L, "sender-b-rejected", "pkg.second", 2,
                                "FREEZE", "blocked"))));
        assertEquals(1, database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build()).items.size());

        DiagnosticsQuery.ClearResult clear = database.clear(DiagnosticsQuery.ClearScope.ALL);
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.COMMITTED,
                database.writeBatch(versionedBatch(
                        clear.epoch, 0L, "sender-b",
                        event(now + 2L, "sender-b-current", "pkg.second", 2,
                                "FREEZE", "blocked"))));

        DiagnosticsQuery.EventPage events = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build());
        assertEquals(1, events.items.size());
        assertEquals("pkg.second", events.items.get(0).packageName);
    }

    @Test
    public void oldBootCursorIsReclaimedWithoutWeakeningCurrentBootReplayDeduplication() {
        DiagnosticsRetentionPolicy value = policy(20, 20, 20, 60_000L, 1);
        long now = System.currentTimeMillis();
        database = open(value, "boot-old");
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.COMMITTED,
                database.writeBatch(versionedBatch(
                        0L, 0L, "sender-old",
                        event(now, "old", "pkg.old", 1, "FREEZE", "blocked")
                                .copyBuilder().bootId("boot-old").build())));
        database.close();

        database = open(value, "boot-new");
        DiagnosticsWriteBatch current = versionedBatch(
                0L, 0L, "sender-new",
                event(now + 1L, "new", "pkg.new", 2, "FREEZE", "blocked")
                        .copyBuilder().bootId("boot-new").build());
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.COMMITTED,
                database.writeBatch(current));
        assertEquals(1L, DatabaseUtils.queryNumEntries(
                database.getReadableDatabase(), ResidencyDiagnosticsDb.TABLE_BATCH_CURSORS));
        assertEquals(ResidencyDiagnosticsStorage.WriteResult.REPLAYED,
                database.writeBatch(current));

        assertEquals(2L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
    }

    @Test
    public void clearAdvancesEpochAndRejectsOldProcessBatches() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        database.writeBatch(versionedBatch(
                0L, 1L, event(now, "before", "pkg.before", 1, "FREEZE", "blocked")));

        DiagnosticsQuery.ClearResult clear =
                database.clear(DiagnosticsQuery.ClearScope.USER_ACTIVITY);
        database.writeBatch(versionedBatch(
                0L, 2L, event(now + 1L, "stale", "pkg.stale", 2, "FREEZE", "blocked")));
        database.writeBatch(versionedBatch(
                clear.epoch, 0L,
                event(now + 2L, "current", "pkg.current", 3, "FREEZE", "blocked")));

        assertEquals(1L, clear.epoch);
        assertEquals(clear.epoch, database.currentEpoch());
        DiagnosticsQuery.EventPage events = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().limit(20).build());
        assertEquals(1, events.items.size());
        assertEquals("pkg.current", events.items.get(0).packageName);
    }

    @Test
    public void newerBootReplacesHookStateInsteadOfInheritingCounts() {
        database = open(policy(20, 20, 20, 60_000L));
        long now = System.currentTimeMillis();
        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addHookState(state(now + 10_000L, "boot-old", "INSTALLED", 7L))
                .build());
        database.writeBatch(DiagnosticsWriteBatch.builder()
                .addHookState(state(now, "boot-new", "FAILED", 2L))
                .build());

        DiagnosticsHookState current = database.queryHookStates(10).get(0);
        assertEquals("boot-new", current.bootId);
        assertEquals("FAILED", current.installStatus);
        assertEquals(2L, current.rawHitCount);
    }

    @Test
    public void migratesVersionOneWithoutLosingEventsOrHomepageCounts() {
        SQLiteDatabase legacy = context.openOrCreateDatabase(
                databaseName, Context.MODE_PRIVATE, null);
        ResidencyDiagnosticsDb.createVersionOneForTest(legacy);
        ContentValues event = new ContentValues();
        event.put("observed_at", 1_700_000_000_000L);
        event.put("process_id", "system_server");
        event.put("feature_id", "freeze");
        event.put("hook_target_id", "freeze_target");
        event.put("behavior", "FREEZE");
        event.put("source", "FAST");
        event.put("package_name", "com.example.target");
        event.put("uid", 10123);
        event.put("decision", "BLOCK_FREEZE");
        event.put("result", "blocked");
        legacy.insertOrThrow(ResidencyDiagnosticsDb.TABLE_EVENTS, null, event);
        ContentValues aggregate = new ContentValues();
        aggregate.put("bucket_start", DiagnosticsValues.dayBucket(1_700_000_000_000L));
        aggregate.put("package_name", "com.example.target");
        aggregate.put("uid", 10123);
        aggregate.put("feature_id", "freeze");
        aggregate.put("hook_target_id", "freeze_target");
        aggregate.put("behavior", "FREEZE");
        aggregate.put("source", "FAST");
        aggregate.put("decision", "BLOCK_FREEZE");
        aggregate.put("result", "blocked");
        aggregate.put("event_count", 4L);
        aggregate.put("blocked_count", 4L);
        aggregate.put("first_at", 1_700_000_000_000L);
        aggregate.put("last_at", 1_700_000_000_100L);
        legacy.insertOrThrow(ResidencyDiagnosticsDb.TABLE_AGGREGATES, null, aggregate);
        legacy.setVersion(1);
        legacy.close();

        database = open(policy(20, 20, 20, 60_000L));
        SQLiteDatabase upgraded = database.getWritableDatabase();

        assertEquals(ResidencyDiagnosticsDb.SCHEMA_VERSION, upgraded.getVersion());
        DiagnosticsQuery.EventPage events = database.queryEvents(
                DiagnosticsQuery.EventFilter.builder().build());
        assertEquals(1, events.items.size());
        assertEquals("blocked", events.items.get(0).result);
        assertEquals(4L, database.queryHomeStats(
                Collections.singletonList("freeze")).observed);
        try (Cursor cursor = upgraded.rawQuery(
                "PRAGMA table_info(" + ResidencyDiagnosticsDb.TABLE_EVENTS + ")", null)) {
            boolean eventTokenFound = false;
            while (cursor.moveToNext()) {
                if ("event_token".equals(cursor.getString(1))) eventTokenFound = true;
            }
            assertTrue(eventTokenFound);
        }
    }

    private ResidencyDiagnosticsDb open(DiagnosticsRetentionPolicy policy) {
        return open(policy, "42");
    }

    private ResidencyDiagnosticsDb open(
            DiagnosticsRetentionPolicy policy, String currentBootId) {
        return new ResidencyDiagnosticsDb(context, databaseName, policy, currentBootId);
    }

    private static DiagnosticsRetentionPolicy policy(
            int events, int aggregates, int raw, long retentionMillis) {
        return policy(events, aggregates, raw, retentionMillis, 512);
    }

    private static DiagnosticsRetentionPolicy policy(
            int events, int aggregates, int raw, long retentionMillis, int cursorLimit) {
        return DiagnosticsRetentionPolicy.builder()
                .retentionMillis(retentionMillis)
                .recentEventLimit(events)
                .aggregateRowLimit(aggregates)
                .rawAggregateRowLimit(raw)
                .hookStateLimit(20)
                .featureTotalLimit(20)
                .batchCursorLimit(cursorLimit)
                .queueCapacity(20)
                .batchSize(20)
                .maxDatabaseBytes(2L * 1_024L * 1_024L)
                .build();
    }

    private static RawHookHit raw(long at, boolean parsed, boolean identity) {
        return RawHookHit.builder()
                .timestampMillis(at)
                .bootId("42")
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .configRevision(7)
                .policyRevision(9)
                .parsed(parsed)
                .identityResolved(identity)
                .build();
    }

    private static NormalizedDiagnosticsEvent event(
            long at, String token, String packageName, int uid,
            String behavior, String result) {
        return NormalizedDiagnosticsEvent.builder()
                .timestampMillis(at)
                .bootId("42")
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .behavior(behavior)
                .source("FAST")
                .packageName(packageName)
                .uid(uid)
                .configRevision(7)
                .policyRevision(9)
                .mode("CUSTOM")
                .decision("blocked".equals(result) ? "BLOCK_FREEZE" : "ALLOW_SYSTEM")
                .result(result)
                .failOpenReason("runtime_fail_open".equals(result) ? "runtime_error" : "")
                .latencyMicros(25L)
                .identityResolved(true)
                .eventToken(token)
                .build();
    }

    private static DiagnosticsHookState state(long now, String status) {
        return state(now, "42", status, 0L);
    }

    private static DiagnosticsHookState state(
            long now, String bootId, String status, long rawCount) {
        return DiagnosticsHookState.builder()
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .installStatus(status)
                .bootId(bootId)
                .rawHitCount(rawCount)
                .parsedCount(rawCount)
                .identityResolvedCount(rawCount)
                .configRevision(7)
                .policyRevision(9)
                .updatedAt(now)
                .build();
    }

    private static DiagnosticsWriteBatch versionedBatch(
            long epoch, long sequence, NormalizedDiagnosticsEvent event) {
        return versionedBatch(epoch, sequence, "sender-a", event);
    }

    private static DiagnosticsWriteBatch versionedBatch(
            long epoch, long sequence, String senderInstanceId,
            NormalizedDiagnosticsEvent event) {
        return DiagnosticsWriteBatch.builder()
                .wireIdentity(
                        2, event.bootId, "system_server", senderInstanceId, epoch, sequence)
                .addEvent(event)
                .build();
    }
}
