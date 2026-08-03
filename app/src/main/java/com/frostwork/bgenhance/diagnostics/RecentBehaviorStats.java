package com.frostwork.bgenhance.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable recent behavior-and-result aggregate shared by home and diagnostics screens. */
public final class RecentBehaviorStats {
    public final List<Row> rows;
    public final long observed;
    public final long blocked;
    public final long failOpen;
    public final String errorCode;

    private RecentBehaviorStats(List<Row> rows, String errorCode) {
        ArrayList<Row> copy = new ArrayList<>();
        long observedTotal = 0L;
        long blockedTotal = 0L;
        long failOpenTotal = 0L;
        if (rows != null) {
            for (Row row : rows) {
                if (row == null) continue;
                copy.add(row);
                observedTotal = saturatingAdd(observedTotal, row.eventCount);
                blockedTotal = saturatingAdd(blockedTotal, row.blockedCount);
                failOpenTotal = saturatingAdd(failOpenTotal, row.failOpenCount);
            }
        }
        this.rows = Collections.unmodifiableList(copy);
        observed = observedTotal;
        blocked = blockedTotal;
        failOpen = failOpenTotal;
        this.errorCode = DiagnosticsValues.id(errorCode);
    }

    public static RecentBehaviorStats fromAggregateRows(
            List<DiagnosticsQuery.AggregateRow> aggregateRows) {
        LinkedHashMap<String, MutableRow> grouped = new LinkedHashMap<>();
        if (aggregateRows != null) {
            for (DiagnosticsQuery.AggregateRow aggregate : aggregateRows) {
                if (aggregate == null) continue;
                String behavior = DiagnosticsBehaviorClassifier.normalizeStoredBehavior(
                        aggregate.behavior);
                String result = DiagnosticsValues.id(aggregate.result);
                String key = behavior + '\u0000' + result;
                grouped.computeIfAbsent(key, ignored -> new MutableRow(behavior, result))
                        .add(aggregate);
            }
        }
        ArrayList<Row> rows = new ArrayList<>(grouped.size());
        for (Map.Entry<String, MutableRow> entry : grouped.entrySet()) {
            rows.add(entry.getValue().build());
        }
        return new RecentBehaviorStats(rows, "");
    }

    public static RecentBehaviorStats empty(String errorCode) {
        return new RecentBehaviorStats(Collections.emptyList(), errorCode);
    }

    public boolean isSuccessful() {
        return errorCode.isEmpty();
    }

    public static final class Row {
        public final String behavior;
        public final String result;
        public final long rawHitCount;
        public final long eventCount;
        public final long blockedCount;
        public final long failOpenCount;
        public final long identityResolvedCount;
        public final long latencyMicrosTotal;
        public final long latencyMicrosMax;
        public final long firstAt;
        public final long lastAt;

        private Row(MutableRow source) {
            behavior = source.behavior;
            result = source.result;
            rawHitCount = source.rawHitCount;
            eventCount = source.eventCount;
            blockedCount = source.blockedCount;
            failOpenCount = source.failOpenCount;
            identityResolvedCount = source.identityResolvedCount;
            latencyMicrosTotal = source.latencyMicrosTotal;
            latencyMicrosMax = source.latencyMicrosMax;
            firstAt = source.firstAt;
            lastAt = source.lastAt;
        }
    }

    private static final class MutableRow {
        final String behavior;
        final String result;
        long rawHitCount;
        long eventCount;
        long blockedCount;
        long failOpenCount;
        long identityResolvedCount;
        long latencyMicrosTotal;
        long latencyMicrosMax;
        long firstAt;
        long lastAt;

        MutableRow(String behavior, String result) {
            this.behavior = behavior;
            this.result = result;
        }

        void add(DiagnosticsQuery.AggregateRow row) {
            rawHitCount = saturatingAdd(rawHitCount, row.rawHitCount);
            eventCount = saturatingAdd(eventCount, row.eventCount);
            blockedCount = saturatingAdd(blockedCount, row.blockedCount);
            failOpenCount = saturatingAdd(failOpenCount, row.failOpenCount);
            identityResolvedCount = saturatingAdd(
                    identityResolvedCount, row.identityResolvedCount);
            latencyMicrosTotal = saturatingAdd(
                    latencyMicrosTotal, row.latencyMicrosTotal);
            latencyMicrosMax = Math.max(latencyMicrosMax, row.latencyMicrosMax);
            if (row.firstAt > 0L && (firstAt == 0L || row.firstAt < firstAt)) {
                firstAt = row.firstAt;
            }
            lastAt = Math.max(lastAt, row.lastAt);
        }

        Row build() {
            return new Row(this);
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
