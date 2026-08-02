package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Asynchronous query boundary consumed by the diagnostics UI data layer. */
public interface DiagnosticsDataSource {
    CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query);

    CompletableFuture<HookStatePage> queryHookStatesAsync(int limit);

    static DiagnosticsDataSource fromBackend(ResidencyDiagnosticsBackend backend) {
        return new BackendDiagnosticsDataSource(backend);
    }

    /** Hook query result with an explicit error channel absent from the current backend DTO. */
    final class HookStatePage {
        public final List<DiagnosticsHookState> items;
        public final String errorCode;

        private HookStatePage(List<DiagnosticsHookState> items, String errorCode) {
            String normalizedError = normalizeError(errorCode);
            this.errorCode = normalizedError;
            if (!normalizedError.isEmpty() || items == null || items.isEmpty()) {
                this.items = Collections.emptyList();
            } else {
                this.items = Collections.unmodifiableList(new ArrayList<>(items));
            }
        }

        public static HookStatePage success(List<DiagnosticsHookState> items) {
            return new HookStatePage(items, "");
        }

        public static HookStatePage failure(String errorCode) {
            String normalized = normalizeError(errorCode);
            return new HookStatePage(Collections.emptyList(),
                    normalized.isEmpty() ? "database_unavailable" : normalized);
        }

        public boolean isSuccessful() {
            return errorCode.isEmpty();
        }

        private static String normalizeError(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
