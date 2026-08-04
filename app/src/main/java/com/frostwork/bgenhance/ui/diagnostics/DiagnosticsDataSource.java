package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStats;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStatsLoader;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStatsLoader;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Asynchronous query boundary consumed by the diagnostics UI data layer. */
public interface DiagnosticsDataSource extends AutoCloseable {
    CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query);

    default CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregateSnapshotAsync(
            DiagnosticsQuery.AggregateQuery query, int maxRows) {
        return queryAggregatesAsync(query);
    }

    default CompletableFuture<RecentBehaviorStats> queryRecentBehaviorStatsAsync() {
        return RecentBehaviorStatsLoader.load(this::queryAggregatesAsync);
    }

    default CompletableFuture<BehaviorDetailStats> queryBehaviorDetailsAsync(
            DiagnosticsBehavior behavior) {
        if (behavior == null) {
            return CompletableFuture.completedFuture(
                    BehaviorDetailStats.empty("invalid_behavior"));
        }
        return BehaviorDetailStatsLoader.load(this::queryAggregatesAsync, behavior.wireId);
    }

    CompletableFuture<HookStatePage> queryHookStatesAsync(int limit);

    default CompletableFuture<ManagedApplicationPage> queryManagedApplicationsAsync() {
        return CompletableFuture.completedFuture(ManagedApplicationPage.unsupported());
    }

    default String currentBootId() {
        return "";
    }

    @Override
    default void close() {}

    static DiagnosticsDataSource fromBackend(ResidencyDiagnosticsBackend backend) {
        return new BackendDiagnosticsDataSource(backend);
    }

    static DiagnosticsDataSource fromBackend(
            Context context, ResidencyDiagnosticsBackend backend) {
        return ManagedCatalogDiagnosticsDataSource.create(context, backend);
    }

    final class ManagedApplication {
        public final String packageName;
        public final String displayName;

        public ManagedApplication(String packageName, String displayName) {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be empty");
            }
            this.packageName = packageName.trim();
            this.displayName = displayName == null || displayName.trim().isEmpty()
                    ? this.packageName : displayName.trim();
        }
    }

    /**
     * An authoritative page is the left-hand application set. Unsupported pages preserve the
     * aggregate-only behavior used by Context-free callers.
     */
    final class ManagedApplicationPage {
        public final List<ManagedApplication> items;
        public final boolean authoritative;
        public final String errorCode;

        private ManagedApplicationPage(
                List<ManagedApplication> items, boolean authoritative, String errorCode) {
            String normalizedError = normalizeError(errorCode);
            this.authoritative = authoritative;
            this.errorCode = normalizedError;
            if (!normalizedError.isEmpty() || items == null || items.isEmpty()) {
                this.items = Collections.emptyList();
            } else {
                this.items = Collections.unmodifiableList(new ArrayList<>(items));
            }
        }

        public static ManagedApplicationPage success(List<ManagedApplication> items) {
            return new ManagedApplicationPage(items, true, "");
        }

        public static ManagedApplicationPage unsupported() {
            return new ManagedApplicationPage(Collections.emptyList(), false, "");
        }

        public static ManagedApplicationPage failure(String errorCode) {
            String normalized = normalizeError(errorCode);
            return new ManagedApplicationPage(Collections.emptyList(), true,
                    normalized.isEmpty() ? "catalog_unavailable" : normalized);
        }

        public boolean isSuccessful() {
            return errorCode.isEmpty();
        }
    }

    /** UI-facing Hook query result with an explicit error channel. */
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

    private static String normalizeError(String value) {
        return value == null ? "" : value.trim();
    }
}
