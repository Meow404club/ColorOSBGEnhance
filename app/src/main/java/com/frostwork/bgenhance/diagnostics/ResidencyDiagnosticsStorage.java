package com.frostwork.bgenhance.diagnostics;

import java.util.List;

interface ResidencyDiagnosticsStorage extends AutoCloseable {
    void writeBatch(DiagnosticsWriteBatch batch);

    /** Runs retention, or a row-limited pressure cleanup when {@code storagePressure} is true. */
    void performMaintenance(long nowMillis, boolean storagePressure);

    DiagnosticsQuery.EventPage queryEvents(DiagnosticsQuery.EventFilter filter);

    DiagnosticsQuery.AggregatePage queryAggregates(DiagnosticsQuery.AggregateQuery query);

    List<DiagnosticsQuery.RawHookSummary> queryRawHooks(
            long fromInclusive, long toExclusive, int limit);

    DiagnosticsQuery.HomeStatsSnapshot queryHomeStats(Iterable<String> featureIds);

    List<DiagnosticsHookState> queryHookStates(int limit);

    DiagnosticsQuery.ClearResult clear(DiagnosticsQuery.ClearScope scope);

    default long currentEpoch() { return 0L; }

    int schemaVersion();

    @Override
    void close();
}
