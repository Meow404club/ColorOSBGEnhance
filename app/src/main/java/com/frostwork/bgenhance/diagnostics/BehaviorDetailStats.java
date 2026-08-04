package com.frostwork.bgenhance.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable feature, behavior, source and result aggregates for one behavior family. */
public final class BehaviorDetailStats {
    public final List<DiagnosticsQuery.AggregateRow> rows;
    public final boolean historyTruncated;
    public final String errorCode;

    private BehaviorDetailStats(
            List<DiagnosticsQuery.AggregateRow> rows,
            boolean historyTruncated,
            String errorCode) {
        this.rows = rows == null || rows.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(rows));
        this.historyTruncated = historyTruncated;
        this.errorCode = DiagnosticsValues.id(errorCode);
    }

    public static BehaviorDetailStats success(List<DiagnosticsQuery.AggregateRow> rows) {
        return success(rows, false);
    }

    public static BehaviorDetailStats success(
            List<DiagnosticsQuery.AggregateRow> rows, boolean historyTruncated) {
        return new BehaviorDetailStats(rows, historyTruncated, "");
    }

    public static BehaviorDetailStats empty(String errorCode) {
        return new BehaviorDetailStats(Collections.emptyList(), false, errorCode);
    }

    public boolean isSuccessful() {
        return errorCode.isEmpty();
    }
}
