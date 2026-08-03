package com.frostwork.bgenhance.diagnostics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Private, bounded SQLite store. All calls are made by {@link ResidencyDiagnosticsBackend}. */
public final class ResidencyDiagnosticsDb extends SQLiteOpenHelper
        implements ResidencyDiagnosticsStorage {
    private static final long MAX_WAL_BYTES = 2L * 1_024L * 1_024L;

    public static final String DATABASE_NAME = "residency_diagnostics.db";
    public static final int SCHEMA_VERSION = 6;

    static final String TABLE_EVENTS = "normalized_events";
    static final String TABLE_AGGREGATES = "event_aggregates";
    static final String TABLE_RAW = "raw_hook_aggregates";
    static final String TABLE_FEATURE_TOTALS = "feature_totals";
    static final String TABLE_HOOK_STATES = "hook_states";
    static final String TABLE_BATCH_CURSORS = "batch_cursors";
    static final String TABLE_METADATA = "diagnostics_metadata";

    private static final String METADATA_EPOCH = "epoch";
    private static final String METADATA_LEGACY_MIGRATION = "legacy_hook_diagnostics_v1";

    private final DiagnosticsRetentionPolicy policy;
    private final String currentBootId;

    public ResidencyDiagnosticsDb(Context context, DiagnosticsRetentionPolicy policy) {
        this(context, DATABASE_NAME, policy, DiagnosticsBootId.readCurrent());
    }

    ResidencyDiagnosticsDb(
            Context context, String databaseName, DiagnosticsRetentionPolicy policy) {
        this(context, databaseName, policy, DiagnosticsBootId.readCurrent());
    }

    ResidencyDiagnosticsDb(
            Context context,
            String databaseName,
            DiagnosticsRetentionPolicy policy,
            String currentBootId) {
        super(context.getApplicationContext(), databaseName, null, SCHEMA_VERSION);
        this.policy = policy == null ? DiagnosticsRetentionPolicy.defaults() : policy;
        this.currentBootId = DiagnosticsValues.isStrictId(currentBootId, false)
                ? currentBootId : "";
        try {
            setWriteAheadLoggingEnabled(true);
        } catch (Throwable ignored) {
            // The backend remains usable on platforms that reject WAL during startup.
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setMaximumSize(policy.maxDatabaseBytes);
        long pageSize = Math.max(1L, db.getPageSize());
        long maximumPages = Math.max(1L, policy.maxDatabaseBytes / pageSize);
        executePragma(db, "PRAGMA max_page_count=" + maximumPages);
        long journalBytes = Math.max(
                pageSize, Math.min(MAX_WAL_BYTES, policy.maxDatabaseBytes / 4L));
        long checkpointPages = Math.max(1L, journalBytes / pageSize);
        executePragma(db, "PRAGMA journal_size_limit=" + journalBytes);
        executePragma(db, "PRAGMA wal_autocheckpoint=" + checkpointPages);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createLatestSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        int current = oldVersion;
        if (current < 1) {
            createLatestSchema(db);
            return;
        }
        if (current == 1 && newVersion >= 2) {
            migrateVersionOneToTwo(db);
            current = 2;
        }
        if (current == 2 && newVersion >= 3) {
            migrateVersionTwoToThree(db);
            current = 3;
        }
        if (current == 3 && newVersion >= 4) {
            migrateVersionThreeToFour(db);
            current = 4;
        }
        if (current == 4 && newVersion >= 5) {
            migrateVersionFourToFive(db);
            current = 5;
        }
        if (current == 5 && newVersion >= 6) {
            migrateVersionFiveToSix(db);
            current = 6;
        }
        if (current != newVersion) {
            throw new IllegalStateException(
                    "unsupported diagnostics migration " + oldVersion + " -> " + newVersion);
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        dropAllTables(db);
        createLatestSchema(db);
    }

    @Override
    public WriteResult writeBatch(DiagnosticsWriteBatch batch) {
        if (batch == null || batch.isEmpty()) return WriteResult.NO_OP;
        if (batch.size() > DiagnosticsRetentionPolicy.MAX_ACCEPTED_BATCH_ITEMS) {
            throw new IllegalArgumentException("diagnostics batch exceeds configured limit");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            if (batch.batchIdentity != null) {
                WriteResult claim = claimVersionedBatch(
                        db, batch.batchIdentity, batch.countingMode,
                        System.currentTimeMillis());
                if (claim != WriteResult.COMMITTED) {
                    db.setTransactionSuccessful();
                    return claim;
                }
            }
            for (RawHookHit hit : batch.rawHits) {
                if (hit != null && hit.isStorable()) writeRawHit(db, hit);
            }
            for (NormalizedDiagnosticsEvent event : batch.events) {
                if (event != null && event.isStorable()) writeEvent(db, event);
            }
            for (DiagnosticsWriteBatch.LegacyFeatureDelta delta
                    : batch.legacyFeatureDeltas) {
                if (delta != null && delta.isStorable()) writeLegacyFeatureDelta(db, delta);
            }
            for (DiagnosticsHookState state : batch.hookStates) {
                if (state != null && state.isStorable()) upsertHookState(db, state, true);
            }
            db.setTransactionSuccessful();
            return WriteResult.COMMITTED;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public boolean migrateLegacyOnce(DiagnosticsWriteBatch batch) {
        if (batch == null
                || batch.batchIdentity != null
                || batch.countingMode != DiagnosticsWriteBatch.CountingMode.LEGACY
                || !batch.rawHits.isEmpty()
                || !batch.events.isEmpty()
                || !batch.hookStates.isEmpty()
                || batch.size() > DiagnosticsRetentionPolicy.MAX_ACCEPTED_BATCH_ITEMS) {
            throw new IllegalArgumentException("invalid legacy diagnostics migration");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            if (hasMetadata(db, METADATA_LEGACY_MIGRATION)) {
                db.setTransactionSuccessful();
                return false;
            }
            for (DiagnosticsWriteBatch.LegacyFeatureDelta delta
                    : batch.legacyFeatureDeltas) {
                if (delta == null || !delta.isStorable()) {
                    throw new IllegalArgumentException("invalid legacy diagnostics delta");
                }
                writeLegacyFeatureDelta(db, delta);
            }
            putMetadataString(db, METADATA_LEGACY_MIGRATION, "1");
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void performMaintenance(long nowMillis, boolean storagePressure) {
        SQLiteDatabase db = getWritableDatabase();
        long now = nowMillis > 0L ? nowMillis : System.currentTimeMillis();
        db.beginTransactionNonExclusive();
        try {
            if (storagePressure) {
                pruneForStoragePressure(db, now);
            } else {
                prune(db, now);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (storagePressure) checkpointWal(db);
    }

    @Override
    public DiagnosticsQuery.EventPage queryEvents(DiagnosticsQuery.EventFilter requested) {
        DiagnosticsQuery.EventFilter filter = requested == null
                ? DiagnosticsQuery.EventFilter.builder().build() : requested;
        Selection selection = eventSelection(filter);
        ArrayList<NormalizedDiagnosticsEvent> events = new ArrayList<>();
        boolean hasMore = false;
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_EVENTS,
                EVENT_PROJECTION,
                selection.clause(),
                selection.arguments(),
                null,
                null,
                "observed_at DESC, id DESC",
                String.valueOf(filter.limit + 1))) {
            while (cursor.moveToNext()) {
                if (events.size() >= filter.limit) {
                    hasMore = true;
                    break;
                }
                events.add(readEvent(cursor));
            }
        }
        DiagnosticsQuery.EventCursor next = null;
        if (hasMore && !events.isEmpty()) {
            NormalizedDiagnosticsEvent last = events.get(events.size() - 1);
            next = new DiagnosticsQuery.EventCursor(last.timestampMillis, last.id);
        }
        return new DiagnosticsQuery.EventPage(events, next, "");
    }

    @Override
    public DiagnosticsQuery.AggregatePage queryAggregates(
            DiagnosticsQuery.AggregateQuery requested) {
        DiagnosticsQuery.AggregateQuery query = requested == null
                ? DiagnosticsQuery.AggregateQuery.builder().build() : requested;
        AggregateSql aggregateSql = aggregateSql(query);
        ArrayList<DiagnosticsQuery.AggregateRow> rows = new ArrayList<>();
        boolean hasMore = false;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                aggregateSql.sql, aggregateSql.arguments)) {
            while (cursor.moveToNext()) {
                if (rows.size() >= query.limit) {
                    hasMore = true;
                    break;
                }
                rows.add(readAggregate(cursor));
            }
        }
        int nextOffset = hasMore ? query.offset + rows.size() : 0;
        return new DiagnosticsQuery.AggregatePage(rows, nextOffset, hasMore, "");
    }

    @Override
    public List<DiagnosticsQuery.RawHookSummary> queryRawHooks(
            long fromInclusive, long toExclusive, int requestedLimit) {
        int limit = Math.max(1, Math.min(
                policy.rawAggregateRowLimit, requestedLimit));
        long fromBucket = DiagnosticsValues.dayBucket(Math.max(0L, fromInclusive));
        long to = toExclusive <= 0L ? Long.MAX_VALUE : toExclusive;
        long toBucket = to == Long.MAX_VALUE
                ? Long.MAX_VALUE : DiagnosticsValues.dayBucket(to - 1L) + DiagnosticsValues.DAY_MILLIS;
        ArrayList<DiagnosticsQuery.RawHookSummary> rows = new ArrayList<>();
        String sql = "SELECT process_id,feature_id,hook_target_id,"
                + "SUM(hit_count) AS hit_count,SUM(parsed_count) AS parsed_count,"
                + "SUM(identity_resolved_count) AS identity_resolved_count,"
                + "MAX(last_hit_at) AS last_hit_at,MAX(last_error) AS last_error "
                + "FROM " + TABLE_RAW + " WHERE bucket_start>=? AND bucket_start<? "
                + "GROUP BY process_id,feature_id,hook_target_id "
                + "ORDER BY hit_count DESC,last_hit_at DESC LIMIT ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[] {
                String.valueOf(fromBucket), String.valueOf(toBucket), String.valueOf(limit)
        })) {
            while (cursor.moveToNext()) {
                rows.add(new DiagnosticsQuery.RawHookSummary(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getLong(4), cursor.getLong(5),
                        cursor.getLong(6), cursor.getString(7)));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    @Override
    public DiagnosticsQuery.HomeStatsSnapshot queryHomeStats(Iterable<String> featureIds) {
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> rows = new LinkedHashMap<>();
        if (featureIds == null) {
            try (Cursor cursor = getReadableDatabase().query(
                    TABLE_FEATURE_TOTALS, FEATURE_TOTAL_PROJECTION,
                    null, null, null, null, "feature_id ASC",
                    String.valueOf(policy.featureTotalLimit))) {
                while (cursor.moveToNext()) {
                    DiagnosticsQuery.FeatureStats value = readFeatureStats(cursor);
                    rows.put(value.featureId, value);
                }
            }
        } else {
            int count = 0;
            for (String requested : featureIds) {
                if (count++ >= policy.featureTotalLimit) break;
                String featureId = DiagnosticsValues.id(requested);
                if (featureId.isEmpty() || rows.containsKey(featureId)) continue;
                DiagnosticsQuery.FeatureStats value = queryFeatureStats(featureId);
                rows.put(featureId, value == null
                        ? DiagnosticsQuery.FeatureStats.empty(featureId) : value);
            }
        }
        return new DiagnosticsQuery.HomeStatsSnapshot(rows, "");
    }

    @Override
    public List<DiagnosticsHookState> queryHookStates(int requestedLimit) {
        int limit = Math.max(1, Math.min(
                Math.min(512, policy.hookStateLimit), requestedLimit));
        ArrayList<DiagnosticsHookState> states = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_HOOK_STATES,
                HOOK_STATE_PROJECTION,
                null, null, null, null,
                "updated_at DESC, process_id ASC, hook_target_id ASC",
                String.valueOf(limit))) {
            while (cursor.moveToNext()) states.add(readHookState(cursor));
        }
        return Collections.unmodifiableList(states);
    }

    @Override
    public DiagnosticsQuery.ClearResult clear(DiagnosticsQuery.ClearScope requestedScope) {
        DiagnosticsQuery.ClearScope scope = requestedScope == null
                ? DiagnosticsQuery.ClearScope.USER_ACTIVITY : requestedScope;
        SQLiteDatabase db = getWritableDatabase();
        int deleted = 0;
        long epoch;
        db.beginTransactionNonExclusive();
        try {
            epoch = currentEpoch(db) + 1L;
            putMetadataLong(db, METADATA_EPOCH, epoch);
            deleted += db.delete(TABLE_BATCH_CURSORS, null, null);
            if (scope == DiagnosticsQuery.ClearScope.RECENT_EVENTS
                    || scope == DiagnosticsQuery.ClearScope.USER_ACTIVITY
                    || scope == DiagnosticsQuery.ClearScope.ALL) {
                deleted += db.delete(TABLE_EVENTS, null, null);
            }
            if (scope == DiagnosticsQuery.ClearScope.STATISTICS
                    || scope == DiagnosticsQuery.ClearScope.USER_ACTIVITY
                    || scope == DiagnosticsQuery.ClearScope.ALL) {
                deleted += db.delete(TABLE_AGGREGATES, null, null);
                deleted += db.delete(TABLE_RAW, null, null);
                deleted += db.delete(TABLE_FEATURE_TOTALS, null, null);
            }
            if (scope == DiagnosticsQuery.ClearScope.STATISTICS) {
                resetHookActivity(db, false);
            } else if (scope == DiagnosticsQuery.ClearScope.USER_ACTIVITY) {
                resetHookActivity(db, true);
            }
            if (scope == DiagnosticsQuery.ClearScope.HOOK_STATES
                    || scope == DiagnosticsQuery.ClearScope.ALL) {
                deleted += db.delete(TABLE_HOOK_STATES, null, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new DiagnosticsQuery.ClearResult(true, deleted, epoch, "");
    }

    private static void resetHookActivity(SQLiteDatabase db, boolean clearEventTime) {
        ContentValues values = new ContentValues();
        values.put("last_hook_at", 0L);
        if (clearEventTime) values.put("last_event_at", 0L);
        values.put("raw_hit_count", 0L);
        values.put("parsed_count", 0L);
        values.put("identity_resolved_count", 0L);
        db.update(TABLE_HOOK_STATES, values, null, null);
    }

    @Override
    public long currentEpoch() {
        return currentEpoch(getReadableDatabase());
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    private void writeRawHit(SQLiteDatabase db, RawHookHit hit) {
        long bucket = DiagnosticsValues.dayBucket(hit.timestampMillis);
        ContentValues values = new ContentValues();
        values.put("bucket_start", bucket);
        values.put("boot_id", hit.bootId);
        values.put("process_id", hit.processId);
        values.put("feature_id", hit.featureId);
        values.put("hook_target_id", hit.hookTargetId);
        values.put("hit_count", 1L);
        values.put("parsed_count", hit.parsed ? 1L : 0L);
        values.put("identity_resolved_count", hit.identityResolved ? 1L : 0L);
        values.put("last_hit_at", hit.timestampMillis);
        values.put("last_error", hit.errorCode);
        long inserted = db.insertWithOnConflict(
                TABLE_RAW, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1L) {
            db.execSQL("UPDATE " + TABLE_RAW + " SET "
                            + "hit_count=hit_count+1,parsed_count=parsed_count+?,"
                            + "identity_resolved_count=identity_resolved_count+?,"
                            + "last_error=CASE WHEN ?>=last_hit_at THEN ? ELSE last_error END,"
                            + "last_hit_at=MAX(last_hit_at,?) WHERE "
                            + "bucket_start=? AND boot_id=? AND process_id=? "
                            + "AND feature_id=? AND hook_target_id=?",
                    new Object[] {
                            hit.parsed ? 1 : 0,
                            hit.identityResolved ? 1 : 0,
                            hit.timestampMillis,
                            hit.errorCode,
                            hit.timestampMillis,
                            bucket,
                            hit.bootId,
                            hit.processId,
                            hit.featureId,
                            hit.hookTargetId
                    });
        }
        upsertHookState(db, DiagnosticsHookState.builder()
                .processId(hit.processId)
                .featureId(hit.featureId)
                .hookTargetId(hit.hookTargetId)
                .bootId(hit.bootId)
                .lastHookAt(hit.timestampMillis)
                .rawHitCount(1L)
                .parsedCount(hit.parsed ? 1L : 0L)
                .identityResolvedCount(hit.identityResolved ? 1L : 0L)
                .configRevision(hit.configRevision)
                .policyRevision(hit.policyRevision)
                .lastError(hit.errorCode)
                .updatedAt(hit.timestampMillis)
                .build(), false);
    }

    private void writeEvent(SQLiteDatabase db, NormalizedDiagnosticsEvent event) {
        ContentValues values = eventValues(event);
        long id = db.insertWithOnConflict(
                TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1L) return;
        upsertEventAggregate(db, event);
        upsertFeatureTotal(db, new DiagnosticsWriteBatch.LegacyFeatureDelta(
                event.featureId,
                1L,
                DiagnosticsValues.isBlocked(event.result) ? 1L : 0L,
                DiagnosticsValues.isFailOpen(event.result) ? 1L : 0L,
                event.timestampMillis,
                event.packageName,
                event.processId,
                event.scene,
                String.valueOf(event.reasonCode),
                event.result));
        upsertHookState(db, DiagnosticsHookState.builder()
                .processId(event.processId)
                .featureId(event.featureId)
                .hookTargetId(event.hookTargetId)
                .bootId(event.bootId)
                .lastEventAt(event.timestampMillis)
                .configRevision(event.configRevision)
                .policyRevision(event.policyRevision)
                .updatedAt(event.timestampMillis)
                .build(), false);
    }

    private void writeLegacyFeatureDelta(
            SQLiteDatabase db, DiagnosticsWriteBatch.LegacyFeatureDelta delta) {
        upsertFeatureTotal(db, delta);
    }

    private static ContentValues eventValues(NormalizedDiagnosticsEvent event) {
        ContentValues values = new ContentValues();
        values.put("event_token", event.eventToken);
        values.put("observed_at", event.timestampMillis);
        values.put("boot_id", event.bootId);
        values.put("process_id", event.processId);
        values.put("feature_id", event.featureId);
        values.put("hook_target_id", event.hookTargetId);
        values.put("behavior", event.behavior);
        values.put("source", event.source);
        values.put("package_name", event.packageName);
        values.put("uid", event.uid);
        values.put("scene", event.scene);
        values.put("reason_code", event.reasonCode);
        values.put("config_revision", event.configRevision);
        values.put("policy_revision", event.policyRevision);
        values.put("mode", event.mode);
        values.put("decision", event.decision);
        values.put("result", event.result);
        values.put("fail_open_reason", event.failOpenReason);
        values.put("latency_micros", event.latencyMicros);
        values.put("raw_hit_count", event.rawHitCount);
        values.put("identity_resolved", event.identityResolved ? 1 : 0);
        values.put("blocked", DiagnosticsValues.isBlocked(event.result) ? 1 : 0);
        values.put("fail_open", DiagnosticsValues.isFailOpen(event.result) ? 1 : 0);
        return values;
    }

    private static void upsertEventAggregate(
            SQLiteDatabase db, NormalizedDiagnosticsEvent event) {
        long bucket = DiagnosticsValues.dayBucket(event.timestampMillis);
        ContentValues values = new ContentValues();
        values.put("bucket_start", bucket);
        values.put("package_name", event.packageName);
        values.put("uid", event.uid);
        values.put("feature_id", event.featureId);
        values.put("hook_target_id", event.hookTargetId);
        values.put("behavior", event.behavior);
        values.put("source", event.source);
        values.put("decision", event.decision);
        values.put("result", event.result);
        values.put("raw_hit_count", event.rawHitCount);
        values.put("event_count", 1L);
        values.put("blocked_count", DiagnosticsValues.isBlocked(event.result) ? 1L : 0L);
        values.put("fail_open_count", DiagnosticsValues.isFailOpen(event.result) ? 1L : 0L);
        values.put("identity_resolved_count", event.identityResolved ? 1L : 0L);
        values.put("latency_micros_total", event.latencyMicros);
        values.put("latency_micros_max", event.latencyMicros);
        values.put("first_at", event.timestampMillis);
        values.put("last_at", event.timestampMillis);
        long inserted = db.insertWithOnConflict(
                TABLE_AGGREGATES, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted != -1L) return;
        db.execSQL("UPDATE " + TABLE_AGGREGATES + " SET "
                        + "raw_hit_count=raw_hit_count+?,event_count=event_count+1,"
                        + "blocked_count=blocked_count+?,fail_open_count=fail_open_count+?,"
                        + "identity_resolved_count=identity_resolved_count+?,"
                        + "latency_micros_total=latency_micros_total+?,"
                        + "latency_micros_max=MAX(latency_micros_max,?),"
                        + "first_at=MIN(first_at,?),last_at=MAX(last_at,?) WHERE "
                        + "bucket_start=? AND package_name=? AND uid=? AND feature_id=? "
                        + "AND hook_target_id=? AND behavior=? AND source=? "
                        + "AND decision=? AND result=?",
                new Object[] {
                        event.rawHitCount,
                        DiagnosticsValues.isBlocked(event.result) ? 1 : 0,
                        DiagnosticsValues.isFailOpen(event.result) ? 1 : 0,
                        event.identityResolved ? 1 : 0,
                        event.latencyMicros,
                        event.latencyMicros,
                        event.timestampMillis,
                        event.timestampMillis,
                        bucket,
                        event.packageName,
                        event.uid,
                        event.featureId,
                        event.hookTargetId,
                        event.behavior,
                        event.source,
                        event.decision,
                        event.result
                });
    }

    private static void upsertFeatureTotal(
            SQLiteDatabase db, DiagnosticsWriteBatch.LegacyFeatureDelta delta) {
        ContentValues values = new ContentValues();
        values.put("feature_id", delta.featureId);
        values.put("observed", delta.observedDelta);
        values.put("blocked", delta.blockedDelta);
        values.put("fail_open", delta.failOpenDelta);
        values.put("last_at", delta.lastAt);
        values.put("last_package", delta.lastPackage);
        values.put("last_process", delta.lastProcess);
        values.put("last_scene", delta.lastScene);
        values.put("last_reason", delta.lastReason);
        values.put("last_result", delta.lastResult);
        long inserted = db.insertWithOnConflict(
                TABLE_FEATURE_TOTALS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted != -1L) return;
        db.execSQL("UPDATE " + TABLE_FEATURE_TOTALS + " SET "
                        + "observed=observed+?,blocked=blocked+?,fail_open=fail_open+?,"
                        + "last_package=CASE WHEN ?>=last_at THEN ? ELSE last_package END,"
                        + "last_process=CASE WHEN ?>=last_at THEN ? ELSE last_process END,"
                        + "last_scene=CASE WHEN ?>=last_at THEN ? ELSE last_scene END,"
                        + "last_reason=CASE WHEN ?>=last_at THEN ? ELSE last_reason END,"
                        + "last_result=CASE WHEN ?>=last_at THEN ? ELSE last_result END,"
                        + "last_at=MAX(last_at,?) WHERE feature_id=?",
                new Object[] {
                        delta.observedDelta,
                        delta.blockedDelta,
                        delta.failOpenDelta,
                        delta.lastAt, delta.lastPackage,
                        delta.lastAt, delta.lastProcess,
                        delta.lastAt, delta.lastScene,
                        delta.lastAt, delta.lastReason,
                        delta.lastAt, delta.lastResult,
                        delta.lastAt,
                        delta.featureId
                });
    }

    private static void upsertHookState(
            SQLiteDatabase db, DiagnosticsHookState state, boolean authoritative) {
        ContentValues values = new ContentValues();
        values.put("process_id", state.processId);
        values.put("hook_target_id", state.hookTargetId);
        values.put("feature_id", state.featureId);
        values.put("install_status", state.installStatus);
        values.put("boot_id", state.bootId);
        values.put("last_hook_at", state.lastHookAt);
        values.put("last_event_at", state.lastEventAt);
        values.put("raw_hit_count", state.rawHitCount);
        values.put("parsed_count", state.parsedCount);
        values.put("identity_resolved_count", state.identityResolvedCount);
        values.put("config_revision", state.configRevision);
        values.put("policy_revision", state.policyRevision);
        values.put("last_error", state.lastError);
        values.put("updated_at", state.updatedAt);
        long inserted = db.insertWithOnConflict(
                TABLE_HOOK_STATES, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted != -1L) return;
        if (!state.bootId.isEmpty()) {
            int replaced = db.update(
                    TABLE_HOOK_STATES,
                    values,
                    "process_id=? AND hook_target_id=? AND boot_id<>?",
                    new String[] {
                            state.processId,
                            state.hookTargetId,
                            state.bootId
                    });
            if (replaced > 0) return;
        }
        db.execSQL("UPDATE " + TABLE_HOOK_STATES + " SET "
                        + "feature_id=CASE WHEN ?<>'' THEN ? ELSE feature_id END,"
                        + "install_status=CASE WHEN ?=1 THEN ? ELSE install_status END,"
                        + "boot_id=CASE WHEN ?<>'' THEN ? ELSE boot_id END,"
                        + "last_hook_at=MAX(last_hook_at,?),"
                        + "last_event_at=MAX(last_event_at,?),"
                        + "raw_hit_count=raw_hit_count+?,parsed_count=parsed_count+?,"
                        + "identity_resolved_count=identity_resolved_count+?,"
                        + "config_revision=CASE WHEN ?>=0 THEN ? ELSE config_revision END,"
                        + "policy_revision=CASE WHEN ?>=0 THEN ? ELSE policy_revision END,"
                        + "last_error=CASE WHEN ?=1 OR ?<>'' THEN ? ELSE last_error END,"
                        + "updated_at=MAX(updated_at,?) WHERE process_id=? AND hook_target_id=? "
                        + "AND (boot_id=? OR boot_id='' OR ?='')",
                new Object[] {
                        state.featureId, state.featureId,
                        authoritative ? 1 : 0, state.installStatus,
                        state.bootId, state.bootId,
                        state.lastHookAt,
                        state.lastEventAt,
                        state.rawHitCount,
                        state.parsedCount,
                        state.identityResolvedCount,
                        state.configRevision, state.configRevision,
                        state.policyRevision, state.policyRevision,
                        authoritative ? 1 : 0, state.lastError, state.lastError,
                        state.updatedAt,
                        state.processId,
                        state.hookTargetId,
                        state.bootId,
                        state.bootId
                });
    }

    private WriteResult claimVersionedBatch(
            SQLiteDatabase db,
            DiagnosticsWriteBatch.BatchIdentity identity,
            DiagnosticsWriteBatch.CountingMode countingMode,
            long now) {
        if (!currentBootId.isEmpty() && !currentBootId.equals(identity.bootId)) {
            return WriteResult.REJECTED_BOOT;
        }
        if (identity.epoch != currentEpoch(db)) return WriteResult.REJECTED_EPOCH;
        if (countingMode == null || countingMode == DiagnosticsWriteBatch.CountingMode.NONE) {
            return WriteResult.REJECTED_COUNTING_MODE;
        }
        if (!DiagnosticsValues.isSha256(identity.payloadHash, false)) {
            return WriteResult.REJECTED_PAYLOAD;
        }
        String cursorSelection = "wire_version=? AND boot_id=? AND process_id=? "
                + "AND sender_instance_id=? AND epoch=?";
        String[] cursorArguments = new String[] {
                String.valueOf(identity.wireVersion),
                identity.bootId,
                identity.processId,
                identity.senderInstanceId,
                String.valueOf(identity.epoch)
        };
        BatchCursor existing = queryBatchCursor(db, cursorSelection, cursorArguments);
        if (existing != null) {
            WriteResult classification = classifyExistingCursor(
                    existing, identity, countingMode);
            if (classification != WriteResult.COMMITTED) return classification;
            ContentValues update = new ContentValues();
            update.put("last_sequence", identity.batchSequence);
            update.put("last_payload_hash", identity.payloadHash);
            update.put("updated_at", now);
            return db.update(
                    TABLE_BATCH_CURSORS,
                    update,
                    cursorSelection + " AND counting_mode=? AND last_sequence=? "
                            + "AND last_payload_hash=?",
                    new String[] {
                            cursorArguments[0], cursorArguments[1], cursorArguments[2],
                            cursorArguments[3], cursorArguments[4], countingMode.wireValue,
                            String.valueOf(existing.lastSequence), existing.payloadHash
                    }) == 1 ? WriteResult.COMMITTED : WriteResult.REJECTED_SEQUENCE;
        }
        if (identity.batchSequence != 0L) return WriteResult.REJECTED_SEQUENCE;
        pruneObsoleteBatchCursors(db);
        if (DatabaseUtils.queryNumEntries(
                db, TABLE_BATCH_CURSORS, "epoch=? AND boot_id=?",
                new String[] {
                        String.valueOf(identity.epoch), identity.bootId
                }) >= policy.batchCursorLimit) {
            return WriteResult.REJECTED_CAPACITY;
        }
        ContentValues values = new ContentValues();
        values.put("wire_version", identity.wireVersion);
        values.put("boot_id", identity.bootId);
        values.put("process_id", identity.processId);
        values.put("sender_instance_id", identity.senderInstanceId);
        values.put("epoch", identity.epoch);
        values.put("counting_mode", countingMode.wireValue);
        values.put("last_sequence", identity.batchSequence);
        values.put("last_payload_hash", identity.payloadHash);
        values.put("updated_at", now);
        if (db.insertWithOnConflict(
                TABLE_BATCH_CURSORS, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
            return WriteResult.COMMITTED;
        }
        existing = queryBatchCursor(db, cursorSelection, cursorArguments);
        if (existing == null) return WriteResult.REJECTED_CAPACITY;
        WriteResult conflict = classifyExistingCursor(existing, identity, countingMode);
        return conflict == WriteResult.COMMITTED
                ? WriteResult.REJECTED_SEQUENCE : conflict;
    }

    private static WriteResult classifyExistingCursor(
            BatchCursor existing,
            DiagnosticsWriteBatch.BatchIdentity identity,
            DiagnosticsWriteBatch.CountingMode countingMode) {
        if (!countingMode.wireValue.equals(existing.countingMode)) {
            return WriteResult.REJECTED_COUNTING_MODE;
        }
        if (identity.batchSequence == existing.lastSequence) {
            return identity.payloadHash.equals(existing.payloadHash)
                    ? WriteResult.REPLAYED : WriteResult.REJECTED_PAYLOAD;
        }
        if (existing.lastSequence == Long.MAX_VALUE
                || identity.batchSequence != existing.lastSequence + 1L) {
            return WriteResult.REJECTED_SEQUENCE;
        }
        return WriteResult.COMMITTED;
    }

    private static BatchCursor queryBatchCursor(
            SQLiteDatabase db, String selection, String[] selectionArguments) {
        try (Cursor cursor = db.query(
                TABLE_BATCH_CURSORS,
                new String[] { "counting_mode", "last_sequence", "last_payload_hash" },
                selection,
                selectionArguments,
                null, null, null,
                "1")) {
            return cursor.moveToFirst()
                    ? new BatchCursor(cursor.getString(0), cursor.getLong(1), cursor.getString(2))
                    : null;
        }
    }

    private void pruneObsoleteBatchCursors(SQLiteDatabase db) {
        long epoch = currentEpoch(db);
        if (currentBootId.isEmpty()) {
            db.delete(TABLE_BATCH_CURSORS, "epoch<>?", new String[] {
                    String.valueOf(epoch)
            });
            return;
        }
        db.delete(TABLE_BATCH_CURSORS, "epoch<>? OR boot_id<>?", new String[] {
                String.valueOf(epoch), currentBootId
        });
    }

    private void prune(SQLiteDatabase db, long now) {
        long cutoff = now <= policy.retentionMillis ? 0L : now - policy.retentionMillis;
        db.delete(TABLE_EVENTS, "observed_at<?", new String[] { String.valueOf(cutoff) });
        db.delete(TABLE_AGGREGATES, "bucket_start<?", new String[] {
                String.valueOf(DiagnosticsValues.dayBucket(cutoff))
        });
        db.delete(TABLE_RAW, "bucket_start<?", new String[] {
                String.valueOf(DiagnosticsValues.dayBucket(cutoff))
        });
        trimRows(db, TABLE_EVENTS, "observed_at DESC,id DESC", policy.recentEventLimit);
        trimRows(db, TABLE_AGGREGATES, "last_at DESC,rowid DESC", policy.aggregateRowLimit);
        trimRows(db, TABLE_RAW, "last_hit_at DESC,rowid DESC", policy.rawAggregateRowLimit);
        trimRows(db, TABLE_HOOK_STATES, "updated_at DESC,rowid DESC", policy.hookStateLimit);
        trimRows(db, TABLE_FEATURE_TOTALS, "last_at DESC,rowid DESC", policy.featureTotalLimit);
        pruneObsoleteBatchCursors(db);
    }

    private void pruneForStoragePressure(SQLiteDatabase db, long now) {
        int remaining = policy.emergencyCleanupRowLimit;
        long cutoff = now <= policy.retentionMillis ? 0L : now - policy.retentionMillis;
        long bucketCutoff = DiagnosticsValues.dayBucket(cutoff);

        remaining -= deleteLimited(db, TABLE_EVENTS, "observed_at<?",
                new String[] { String.valueOf(cutoff) },
                "observed_at ASC,id ASC", remaining);
        remaining -= deleteLimited(db, TABLE_AGGREGATES, "bucket_start<?",
                new String[] { String.valueOf(bucketCutoff) },
                "last_at ASC,rowid ASC", remaining);
        remaining -= deleteLimited(db, TABLE_RAW, "bucket_start<?",
                new String[] { String.valueOf(bucketCutoff) },
                "last_hit_at ASC,rowid ASC", remaining);

        remaining -= deleteLimited(db, TABLE_EVENTS, null, null,
                "observed_at ASC,id ASC", remaining);
        remaining -= deleteLimited(db, TABLE_AGGREGATES, null, null,
                "last_at ASC,rowid ASC", remaining);
        remaining -= deleteLimited(db, TABLE_RAW, null, null,
                "last_hit_at ASC,rowid ASC", remaining);
        remaining -= deleteLimited(db, TABLE_HOOK_STATES, null, null,
                "updated_at ASC,rowid ASC", remaining);
        remaining -= deleteLimited(db, TABLE_FEATURE_TOTALS, null, null,
                "last_at ASC,rowid ASC", remaining);
        String cursorWhere = currentBootId.isEmpty()
                ? "epoch<>?" : "epoch<>? OR boot_id<>?";
        String[] cursorArguments = currentBootId.isEmpty()
                ? new String[] { String.valueOf(currentEpoch(db)) }
                : new String[] { String.valueOf(currentEpoch(db)), currentBootId };
        deleteLimited(db, TABLE_BATCH_CURSORS, cursorWhere, cursorArguments,
                "updated_at ASC,rowid ASC", remaining);
    }

    private static int deleteLimited(
            SQLiteDatabase db,
            String table,
            String where,
            String[] whereArguments,
            String orderBy,
            int limit) {
        if (limit <= 0) return 0;
        StringBuilder selection = new StringBuilder("rowid IN (SELECT rowid FROM ")
                .append(table);
        ArrayList<String> arguments = new ArrayList<>();
        if (where != null && !where.isEmpty()) {
            selection.append(" WHERE ").append(where);
            if (whereArguments != null) Collections.addAll(arguments, whereArguments);
        }
        selection.append(" ORDER BY ").append(orderBy).append(" LIMIT ?)");
        arguments.add(String.valueOf(limit));
        return db.delete(table, selection.toString(), arguments.toArray(new String[0]));
    }

    private static void checkpointWal(SQLiteDatabase db) {
        executePragma(db, "PRAGMA wal_checkpoint(TRUNCATE)");
    }

    private static void executePragma(SQLiteDatabase db, String statement) {
        try (Cursor cursor = db.rawQuery(statement, null)) {
            while (cursor.moveToNext()) {
                // Result-bearing PRAGMAs only take effect after their row is consumed.
            }
        }
    }

    private static void trimRows(
            SQLiteDatabase db, String table, String orderBy, int limit) {
        db.execSQL("DELETE FROM " + table + " WHERE rowid IN (SELECT rowid FROM "
                + table + " ORDER BY " + orderBy + " LIMIT -1 OFFSET ?)",
                new Object[] { limit });
    }

    private static Selection eventSelection(DiagnosticsQuery.EventFilter filter) {
        Selection selection = new Selection();
        selection.add("observed_at>=?", filter.fromInclusive);
        selection.add("observed_at<?", filter.toExclusive);
        selection.addIfPresent("package_name=?", filter.packageName);
        if (filter.uid != null) selection.add("uid=?", filter.uid);
        selection.addIfPresent("feature_id=?", filter.featureId);
        selection.addIfPresent("hook_target_id=?", filter.hookTargetId);
        selection.addIfPresent("behavior=?", filter.behavior);
        selection.addIfPresent("source=?", filter.source);
        selection.addIfPresent("decision=?", filter.decision);
        selection.addIfPresent("result=?", filter.result);
        if (filter.failOpenOnly) selection.addLiteral("fail_open=1");
        if (filter.before != null) {
            selection.addLiteral("(observed_at<? OR (observed_at=? AND id<?))");
            selection.argument(filter.before.timestampMillis);
            selection.argument(filter.before.timestampMillis);
            selection.argument(filter.before.id);
        }
        return selection;
    }

    private static AggregateSql aggregateSql(DiagnosticsQuery.AggregateQuery query) {
        ArrayList<String> selected = new ArrayList<>();
        ArrayList<String> grouped = new ArrayList<>();
        selectDimension(query, DiagnosticsQuery.AggregateDimension.APPLICATION,
                "package_name", selected, grouped, "''");
        if (query.dimensions.contains(DiagnosticsQuery.AggregateDimension.APPLICATION)) {
            selected.add("uid AS uid");
            grouped.add("uid");
        } else {
            selected.add("-1 AS uid");
        }
        selectDimension(query, DiagnosticsQuery.AggregateDimension.FEATURE,
                "feature_id", selected, grouped, "''");
        selectDimension(query, DiagnosticsQuery.AggregateDimension.HOOK_TARGET,
                "hook_target_id", selected, grouped, "''");
        selectDimension(query, DiagnosticsQuery.AggregateDimension.BEHAVIOR,
                "behavior", selected, grouped, "''");
        selectDimension(query, DiagnosticsQuery.AggregateDimension.SOURCE,
                "source", selected, grouped, "''");
        selectDimension(query, DiagnosticsQuery.AggregateDimension.DECISION,
                "decision", selected, grouped, "''");
        selectDimension(query, DiagnosticsQuery.AggregateDimension.RESULT,
                "result", selected, grouped, "''");
        selected.add("SUM(raw_hit_count) AS raw_hit_count");
        selected.add("SUM(event_count) AS event_count");
        selected.add("SUM(blocked_count) AS blocked_count");
        selected.add("SUM(fail_open_count) AS fail_open_count");
        selected.add("SUM(identity_resolved_count) AS identity_resolved_count");
        selected.add("SUM(latency_micros_total) AS latency_micros_total");
        selected.add("MAX(latency_micros_max) AS latency_micros_max");
        selected.add("MIN(first_at) AS first_at");
        selected.add("MAX(last_at) AS last_at");

        Selection where = new Selection();
        where.add("last_at>=?", query.fromInclusive);
        where.add("first_at<?", query.toExclusive);
        where.addIfPresent("package_name=?", query.packageName);
        where.addIfPresent("feature_id=?", query.featureId);
        where.addIfPresent("hook_target_id=?", query.hookTargetId);
        where.addIfPresent("behavior=?", query.behavior);
        where.addIfPresent("source=?", query.source);
        where.addIfPresent("decision=?", query.decision);
        where.addIfPresent("result=?", query.result);

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(join(selected)).append(" FROM ").append(TABLE_AGGREGATES)
                .append(" WHERE ").append(where.clause());
        if (!grouped.isEmpty()) sql.append(" GROUP BY ").append(join(grouped));
        sql.append(" ORDER BY event_count DESC,last_at DESC LIMIT ? OFFSET ?");
        where.argument(query.limit + 1);
        where.argument(query.offset);
        return new AggregateSql(sql.toString(), where.arguments());
    }

    private static void selectDimension(
            DiagnosticsQuery.AggregateQuery query,
            DiagnosticsQuery.AggregateDimension dimension,
            String column,
            List<String> selected,
            List<String> grouped,
            String fallback) {
        if (query.dimensions.contains(dimension)) {
            selected.add(column + " AS " + column);
            grouped.add(column);
        } else {
            selected.add(fallback + " AS " + column);
        }
    }

    private DiagnosticsQuery.FeatureStats queryFeatureStats(String featureId) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_FEATURE_TOTALS, FEATURE_TOTAL_PROJECTION,
                "feature_id=?", new String[] { featureId },
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readFeatureStats(cursor) : null;
        }
    }

    private static NormalizedDiagnosticsEvent readEvent(Cursor cursor) {
        return NormalizedDiagnosticsEvent.builder()
                .id(cursor.getLong(0))
                .eventToken(cursor.getString(1))
                .timestampMillis(cursor.getLong(2))
                .bootId(cursor.getString(3))
                .processId(cursor.getString(4))
                .featureId(cursor.getString(5))
                .hookTargetId(cursor.getString(6))
                .behavior(cursor.getString(7))
                .source(cursor.getString(8))
                .packageName(cursor.getString(9))
                .uid(cursor.getInt(10))
                .scene(cursor.getString(11))
                .reasonCode(cursor.getInt(12))
                .configRevision(cursor.getInt(13))
                .policyRevision(cursor.getInt(14))
                .mode(cursor.getString(15))
                .decision(cursor.getString(16))
                .result(cursor.getString(17))
                .failOpenReason(cursor.getString(18))
                .latencyMicros(cursor.getLong(19))
                .rawHitCount(cursor.getInt(20))
                .identityResolved(cursor.getInt(21) != 0)
                .build();
    }

    private static DiagnosticsQuery.AggregateRow readAggregate(Cursor cursor) {
        return new DiagnosticsQuery.AggregateRow(
                cursor.getString(0), cursor.getInt(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getString(5),
                cursor.getString(6), cursor.getString(7), cursor.getLong(8),
                cursor.getLong(9), cursor.getLong(10), cursor.getLong(11),
                cursor.getLong(12), cursor.getLong(13), cursor.getLong(14),
                cursor.getLong(15), cursor.getLong(16));
    }

    private static DiagnosticsQuery.FeatureStats readFeatureStats(Cursor cursor) {
        return new DiagnosticsQuery.FeatureStats(
                cursor.getString(0), cursor.getLong(1), cursor.getLong(2),
                cursor.getLong(3), cursor.getLong(4), cursor.getString(5),
                cursor.getString(6), cursor.getString(7), cursor.getString(8),
                cursor.getString(9));
    }

    private static DiagnosticsHookState readHookState(Cursor cursor) {
        return DiagnosticsHookState.builder()
                .processId(cursor.getString(0))
                .hookTargetId(cursor.getString(1))
                .featureId(cursor.getString(2))
                .installStatus(cursor.getString(3))
                .bootId(cursor.getString(4))
                .lastHookAt(cursor.getLong(5))
                .lastEventAt(cursor.getLong(6))
                .rawHitCount(cursor.getLong(7))
                .parsedCount(cursor.getLong(8))
                .identityResolvedCount(cursor.getLong(9))
                .configRevision(cursor.getInt(10))
                .policyRevision(cursor.getInt(11))
                .lastError(cursor.getString(12))
                .updatedAt(cursor.getLong(13))
                .build();
    }

    private static void createLatestSchema(SQLiteDatabase db) {
        createEventTableLatest(db);
        createAggregateTableLatest(db);
        createRawTable(db);
        createFeatureTotalsTable(db);
        createHookStateTable(db);
        createBatchCursorTable(db);
        createMetadataTable(db);
        createIndexes(db);
    }

    static void createVersionOneForTest(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_EVENTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "observed_at INTEGER NOT NULL,process_id TEXT NOT NULL,"
                + "feature_id TEXT NOT NULL,hook_target_id TEXT NOT NULL,"
                + "behavior TEXT NOT NULL,source TEXT NOT NULL DEFAULT '',"
                + "package_name TEXT NOT NULL DEFAULT '',uid INTEGER NOT NULL DEFAULT -1,"
                + "decision TEXT NOT NULL,result TEXT NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_AGGREGATES + " ("
                + "bucket_start INTEGER NOT NULL,package_name TEXT NOT NULL DEFAULT '',"
                + "uid INTEGER NOT NULL DEFAULT -1,feature_id TEXT NOT NULL,"
                + "hook_target_id TEXT NOT NULL,behavior TEXT NOT NULL,"
                + "source TEXT NOT NULL DEFAULT '',decision TEXT NOT NULL,result TEXT NOT NULL,"
                + "event_count INTEGER NOT NULL DEFAULT 0,"
                + "blocked_count INTEGER NOT NULL DEFAULT 0,"
                + "first_at INTEGER NOT NULL,last_at INTEGER NOT NULL,"
                + "PRIMARY KEY(bucket_start,package_name,uid,feature_id,hook_target_id,"
                + "behavior,source,decision,result))");
    }

    static void createVersionFiveForTest(SQLiteDatabase db) {
        createEventTableLatest(db);
        createAggregateTableLatest(db);
        createRawTable(db);
        createFeatureTotalsTable(db);
        createHookStateTable(db);
        createMetadataTable(db);
        db.execSQL("CREATE TABLE " + TABLE_BATCH_CURSORS + " ("
                + "wire_version INTEGER NOT NULL,boot_id TEXT NOT NULL,"
                + "process_id TEXT NOT NULL,sender_instance_id TEXT NOT NULL,"
                + "epoch INTEGER NOT NULL,counting_mode TEXT NOT NULL,"
                + "last_sequence INTEGER NOT NULL,updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(wire_version,boot_id,process_id,sender_instance_id,epoch))");
        createIndexes(db);
    }

    private static void migrateVersionOneToTwo(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN boot_id TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN scene TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN reason_code INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN config_revision INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN policy_revision INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN mode TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN fail_open_reason TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN latency_micros INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN raw_hit_count INTEGER NOT NULL DEFAULT 1");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN identity_resolved INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN fail_open INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE " + TABLE_EVENTS + " SET blocked=CASE WHEN result IN "
                + "('blocked','throttle_blocked') THEN 1 ELSE 0 END,"
                + "fail_open=CASE WHEN result IN ('fail_open','config_unavailable',"
                + "'runtime_fail_open') THEN 1 ELSE 0 END");

        db.execSQL("ALTER TABLE " + TABLE_AGGREGATES
                + " ADD COLUMN raw_hit_count INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_AGGREGATES
                + " ADD COLUMN fail_open_count INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_AGGREGATES
                + " ADD COLUMN identity_resolved_count INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_AGGREGATES
                + " ADD COLUMN latency_micros_total INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_AGGREGATES
                + " ADD COLUMN latency_micros_max INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE " + TABLE_AGGREGATES + " SET raw_hit_count=event_count,"
                + "fail_open_count=CASE WHEN result IN ('fail_open','config_unavailable',"
                + "'runtime_fail_open') THEN event_count ELSE 0 END");
        createRawTable(db);
        createFeatureTotalsTable(db);
        createHookStateTable(db);
        db.execSQL("INSERT OR IGNORE INTO " + TABLE_FEATURE_TOTALS
                + "(feature_id,observed,blocked,fail_open,last_at) "
                + "SELECT feature_id,SUM(event_count),SUM(blocked_count),"
                + "SUM(fail_open_count),MAX(last_at) FROM " + TABLE_AGGREGATES
                + " GROUP BY feature_id");
    }

    private static void migrateVersionTwoToThree(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE_EVENTS
                + " ADD COLUMN event_token TEXT NOT NULL DEFAULT ''");
        createBatchCursorTable(db);
        createMetadataTable(db);
        createIndexes(db);
    }

    private static void migrateVersionThreeToFour(SQLiteDatabase db) {
        createBatchCursorTable(db);
        createMetadataTable(db);
        createIndexes(db);
    }

    private static void migrateVersionFourToFive(SQLiteDatabase db) {
        createMetadataTable(db);
        putMetadataLong(db, METADATA_EPOCH, currentEpoch(db) + 1L);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BATCH_CURSORS);
        createBatchCursorTable(db);
        createIndexes(db);
    }

    private static void migrateVersionFiveToSix(SQLiteDatabase db) {
        createMetadataTable(db);
        putMetadataLong(db, METADATA_EPOCH, currentEpoch(db) + 1L);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BATCH_CURSORS);
        createBatchCursorTable(db);
        createIndexes(db);
    }

    private static void createEventTableLatest(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_EVENTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,event_token TEXT NOT NULL DEFAULT '',"
                + "observed_at INTEGER NOT NULL,boot_id TEXT NOT NULL DEFAULT '',"
                + "process_id TEXT NOT NULL,feature_id TEXT NOT NULL,"
                + "hook_target_id TEXT NOT NULL,behavior TEXT NOT NULL,"
                + "source TEXT NOT NULL DEFAULT '',package_name TEXT NOT NULL DEFAULT '',"
                + "uid INTEGER NOT NULL DEFAULT -1,scene TEXT NOT NULL DEFAULT '',"
                + "reason_code INTEGER NOT NULL DEFAULT 0,"
                + "config_revision INTEGER NOT NULL DEFAULT -1,"
                + "policy_revision INTEGER NOT NULL DEFAULT -1,"
                + "mode TEXT NOT NULL DEFAULT '',decision TEXT NOT NULL,result TEXT NOT NULL,"
                + "fail_open_reason TEXT NOT NULL DEFAULT '',"
                + "latency_micros INTEGER NOT NULL DEFAULT 0,"
                + "raw_hit_count INTEGER NOT NULL DEFAULT 1,"
                + "identity_resolved INTEGER NOT NULL DEFAULT 0,"
                + "blocked INTEGER NOT NULL DEFAULT 0,"
                + "fail_open INTEGER NOT NULL DEFAULT 0)");
    }

    private static void createAggregateTableLatest(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_AGGREGATES + " ("
                + "bucket_start INTEGER NOT NULL,package_name TEXT NOT NULL DEFAULT '',"
                + "uid INTEGER NOT NULL DEFAULT -1,feature_id TEXT NOT NULL,"
                + "hook_target_id TEXT NOT NULL,behavior TEXT NOT NULL,"
                + "source TEXT NOT NULL DEFAULT '',decision TEXT NOT NULL,result TEXT NOT NULL,"
                + "raw_hit_count INTEGER NOT NULL DEFAULT 0,"
                + "event_count INTEGER NOT NULL DEFAULT 0,"
                + "blocked_count INTEGER NOT NULL DEFAULT 0,"
                + "fail_open_count INTEGER NOT NULL DEFAULT 0,"
                + "identity_resolved_count INTEGER NOT NULL DEFAULT 0,"
                + "latency_micros_total INTEGER NOT NULL DEFAULT 0,"
                + "latency_micros_max INTEGER NOT NULL DEFAULT 0,"
                + "first_at INTEGER NOT NULL,last_at INTEGER NOT NULL,"
                + "PRIMARY KEY(bucket_start,package_name,uid,feature_id,hook_target_id,"
                + "behavior,source,decision,result))");
    }

    private static void createRawTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_RAW + " ("
                + "bucket_start INTEGER NOT NULL,boot_id TEXT NOT NULL DEFAULT '',"
                + "process_id TEXT NOT NULL,feature_id TEXT NOT NULL DEFAULT '',"
                + "hook_target_id TEXT NOT NULL,hit_count INTEGER NOT NULL DEFAULT 0,"
                + "parsed_count INTEGER NOT NULL DEFAULT 0,"
                + "identity_resolved_count INTEGER NOT NULL DEFAULT 0,"
                + "last_hit_at INTEGER NOT NULL,last_error TEXT NOT NULL DEFAULT '',"
                + "PRIMARY KEY(bucket_start,boot_id,process_id,feature_id,hook_target_id))");
    }

    private static void createFeatureTotalsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FEATURE_TOTALS + " ("
                + "feature_id TEXT PRIMARY KEY NOT NULL,observed INTEGER NOT NULL DEFAULT 0,"
                + "blocked INTEGER NOT NULL DEFAULT 0,fail_open INTEGER NOT NULL DEFAULT 0,"
                + "last_at INTEGER NOT NULL DEFAULT 0,last_package TEXT NOT NULL DEFAULT '',"
                + "last_process TEXT NOT NULL DEFAULT '',last_scene TEXT NOT NULL DEFAULT '',"
                + "last_reason TEXT NOT NULL DEFAULT '',last_result TEXT NOT NULL DEFAULT '')");
    }

    private static void createHookStateTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_HOOK_STATES + " ("
                + "process_id TEXT NOT NULL,hook_target_id TEXT NOT NULL,"
                + "feature_id TEXT NOT NULL DEFAULT '',"
                + "install_status TEXT NOT NULL DEFAULT 'UNKNOWN',"
                + "boot_id TEXT NOT NULL DEFAULT '',last_hook_at INTEGER NOT NULL DEFAULT 0,"
                + "last_event_at INTEGER NOT NULL DEFAULT 0,"
                + "raw_hit_count INTEGER NOT NULL DEFAULT 0,"
                + "parsed_count INTEGER NOT NULL DEFAULT 0,"
                + "identity_resolved_count INTEGER NOT NULL DEFAULT 0,"
                + "config_revision INTEGER NOT NULL DEFAULT -1,"
                + "policy_revision INTEGER NOT NULL DEFAULT -1,"
                + "last_error TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(process_id,hook_target_id))");
    }

    private static void createBatchCursorTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BATCH_CURSORS + " ("
                + "wire_version INTEGER NOT NULL,boot_id TEXT NOT NULL,"
                + "process_id TEXT NOT NULL,sender_instance_id TEXT NOT NULL,"
                + "epoch INTEGER NOT NULL,counting_mode TEXT NOT NULL,"
                + "last_sequence INTEGER NOT NULL,last_payload_hash TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(wire_version,boot_id,process_id,sender_instance_id,epoch))");
    }

    private static final class BatchCursor {
        final String countingMode;
        final long lastSequence;
        final String payloadHash;

        BatchCursor(String countingMode, long lastSequence, String payloadHash) {
            this.countingMode = countingMode;
            this.lastSequence = lastSequence;
            this.payloadHash = payloadHash == null ? "" : payloadHash;
        }
    }

    private static void createMetadataTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_METADATA + " ("
                + "name TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO " + TABLE_METADATA
                + "(name,value) VALUES('format','residency-diagnostics-v1')");
        db.execSQL("INSERT OR IGNORE INTO " + TABLE_METADATA
                + "(name,value) VALUES('" + METADATA_EPOCH + "','0')");
    }

    private static long currentEpoch(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                TABLE_METADATA,
                new String[] { "value" },
                "name=?",
                new String[] { METADATA_EPOCH },
                null,
                null,
                null,
                "1")) {
            if (!cursor.moveToFirst()) return 0L;
            try {
                return Math.max(0L, Long.parseLong(cursor.getString(0)));
            } catch (RuntimeException ignored) {
                return 0L;
            }
        }
    }

    private static boolean hasMetadata(SQLiteDatabase db, String name) {
        return DatabaseUtils.queryNumEntries(
                db, TABLE_METADATA, "name=?", new String[] { name }) > 0L;
    }

    private static void putMetadataString(SQLiteDatabase db, String name, String value) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("value", value == null ? "" : value);
        db.insertWithOnConflict(
                TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void putMetadataLong(SQLiteDatabase db, String name, long value) {
        putMetadataString(db, name, String.valueOf(Math.max(0L, value)));
    }

    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_time ON "
                + TABLE_EVENTS + "(observed_at DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_package_time ON "
                + TABLE_EVENTS + "(package_name,observed_at DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_feature_time ON "
                + TABLE_EVENTS + "(feature_id,observed_at DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_hook_time ON "
                + TABLE_EVENTS + "(hook_target_id,observed_at DESC,id DESC)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_events_token ON "
                + TABLE_EVENTS + "(event_token) WHERE event_token<>''");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_aggregates_last ON "
                + TABLE_AGGREGATES + "(last_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_last ON "
                + TABLE_RAW + "(last_hit_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hook_states_updated ON "
                + TABLE_HOOK_STATES + "(updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_batch_cursors_updated ON "
                + TABLE_BATCH_CURSORS + "(updated_at DESC)");
    }

    private static void dropAllTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_METADATA);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BATCH_CURSORS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HOOK_STATES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FEATURE_TOTALS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_RAW);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_AGGREGATES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENTS);
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    private static final String[] EVENT_PROJECTION = {
            "id", "event_token", "observed_at", "boot_id", "process_id", "feature_id",
            "hook_target_id", "behavior", "source", "package_name", "uid", "scene",
            "reason_code", "config_revision", "policy_revision", "mode", "decision",
            "result", "fail_open_reason", "latency_micros", "raw_hit_count",
            "identity_resolved"
    };

    private static final String[] FEATURE_TOTAL_PROJECTION = {
            "feature_id", "observed", "blocked", "fail_open", "last_at", "last_package",
            "last_process", "last_scene", "last_reason", "last_result"
    };

    private static final String[] HOOK_STATE_PROJECTION = {
            "process_id", "hook_target_id", "feature_id", "install_status", "boot_id",
            "last_hook_at", "last_event_at", "raw_hit_count", "parsed_count",
            "identity_resolved_count", "config_revision", "policy_revision", "last_error",
            "updated_at"
    };

    private static final class Selection {
        private final StringBuilder clause = new StringBuilder();
        private final ArrayList<String> arguments = new ArrayList<>();

        void add(String expression, Object value) {
            addLiteral(expression);
            argument(value);
        }

        void addIfPresent(String expression, String value) {
            if (value != null && !value.isEmpty()) add(expression, value);
        }

        void addLiteral(String expression) {
            if (clause.length() > 0) clause.append(" AND ");
            clause.append(expression);
        }

        void argument(Object value) {
            arguments.add(String.valueOf(value));
        }

        String clause() { return clause.length() == 0 ? null : clause.toString(); }
        String[] arguments() { return arguments.toArray(new String[0]); }
    }

    private static final class AggregateSql {
        final String sql;
        final String[] arguments;

        AggregateSql(String sql, String[] arguments) {
            this.sql = sql;
            this.arguments = arguments;
        }
    }
}
