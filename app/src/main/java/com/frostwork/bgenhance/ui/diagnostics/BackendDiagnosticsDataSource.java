package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class BackendDiagnosticsDataSource implements DiagnosticsDataSource {
    private static final int MAX_HOOK_STATES = 512;

    private final ResidencyDiagnosticsBackend backend;

    BackendDiagnosticsDataSource(ResidencyDiagnosticsBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query) {
        return backend.queryAggregatesAsync(Objects.requireNonNull(query, "query"));
    }

    @Override
    public CompletableFuture<HookStatePage> queryHookStatesAsync(int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_HOOK_STATES, requestedLimit));
        DiagnosticsQuery.ExportOptions options = DiagnosticsQuery.ExportOptions.builder()
                .includeSensitiveDetails(true)
                .recentEventLimit(1)
                .aggregateLimit(1)
                .rawHookLimit(1)
                .hookStateLimit(limit)
                .build();

        // ExportSnapshot is currently the only backend result that keeps Hook query errors
        // explicit. The non-Hook limits are minimized, and the snapshot never leaves memory.
        return backend.exportAsync(options, Collections.emptyList())
                .handle((snapshot, failure) -> {
                    if (failure != null || snapshot == null) {
                        return HookStatePage.failure("database_unavailable");
                    }
                    if (snapshot.errorCode != null && !snapshot.errorCode.isEmpty()) {
                        return HookStatePage.failure(snapshot.errorCode);
                    }
                    return HookStatePage.success(snapshot.hookStates);
                });
    }
}
