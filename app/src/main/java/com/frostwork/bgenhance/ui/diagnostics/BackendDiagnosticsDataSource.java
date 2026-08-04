package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBootId;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class BackendDiagnosticsDataSource implements DiagnosticsDataSource {
    private static final int MAX_HOOK_STATES = 512;

    private final ResidencyDiagnosticsBackend backend;
    private final String currentBootId;

    BackendDiagnosticsDataSource(ResidencyDiagnosticsBackend backend) {
        this(backend, DiagnosticsBootId.readCurrent());
    }

    BackendDiagnosticsDataSource(
            ResidencyDiagnosticsBackend backend, String currentBootId) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.currentBootId = currentBootId == null ? "" : currentBootId.trim();
    }

    @Override
    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query) {
        return backend.queryAggregatesAsync(Objects.requireNonNull(query, "query"));
    }

    @Override
    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregateSnapshotAsync(
            DiagnosticsQuery.AggregateQuery query, int maxRows) {
        return backend.queryAggregateSnapshotAsync(
                Objects.requireNonNull(query, "query"), maxRows);
    }

    @Override
    public CompletableFuture<HookStatePage> queryHookStatesAsync(int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_HOOK_STATES, requestedLimit));
        return backend.queryHookStatesAsync(limit)
                .handle((page, failure) -> {
                    if (failure != null || page == null) {
                        return HookStatePage.failure("database_unavailable");
                    }
                    if (page.errorCode != null && !page.errorCode.isEmpty()) {
                        return HookStatePage.failure(page.errorCode);
                    }
                    return HookStatePage.success(page.items);
                });
    }

    @Override
    public String currentBootId() {
        return currentBootId;
    }
}
