package com.frostwork.bgenhance.diagnostics;

import java.util.List;

interface ResidencyDiagnosticsStorage extends AutoCloseable {
    enum WriteResult {
        NO_OP,
        COMMITTED,
        REPLAYED,
        REJECTED_BOOT,
        REJECTED_EPOCH,
        REJECTED_COUNTING_MODE,
        REJECTED_SEQUENCE,
        REJECTED_PAYLOAD,
        REJECTED_CAPACITY;

        boolean isRejected() {
            return this == NO_OP
                    || this == REJECTED_BOOT
                    || this == REJECTED_EPOCH
                    || this == REJECTED_COUNTING_MODE
                    || this == REJECTED_SEQUENCE
                    || this == REJECTED_PAYLOAD
                    || this == REJECTED_CAPACITY;
        }
    }

    /** Returns whether the whole transactional batch committed, replayed, or was rejected. */
    WriteResult writeBatch(DiagnosticsWriteBatch batch);

    /** Imports legacy feature totals and records its marker in one transaction. */
    boolean migrateLegacyOnce(DiagnosticsWriteBatch batch);

    /** Runs retention, or a row-limited pressure cleanup when {@code storagePressure} is true. */
    void performMaintenance(long nowMillis, boolean storagePressure);

    DiagnosticsQuery.EventPage queryEvents(DiagnosticsQuery.EventFilter filter);

    DiagnosticsQuery.AggregatePage queryAggregates(DiagnosticsQuery.AggregateQuery query);

    List<DiagnosticsQuery.RawHookSummary> queryRawHooks(
            long fromInclusive, long toExclusive, int limit);

    DiagnosticsQuery.HomeStatsSnapshot queryHomeStats(Iterable<String> featureIds);

    List<DiagnosticsHookState> queryHookStates(int limit);

    DiagnosticsQuery.ClearResult clear(DiagnosticsQuery.ClearScope scope);

    long currentEpoch();

    int schemaVersion();

    @Override
    void close();
}
