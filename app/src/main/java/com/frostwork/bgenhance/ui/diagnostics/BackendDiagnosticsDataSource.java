package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
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
}
