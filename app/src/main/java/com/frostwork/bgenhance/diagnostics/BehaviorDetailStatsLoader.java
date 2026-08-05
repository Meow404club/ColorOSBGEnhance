package com.frostwork.bgenhance.diagnostics;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Collects the bounded aggregate pages needed to render one behavior detail screen. */
public final class BehaviorDetailStatsLoader {
    public static final int QUERY_PAGE_SIZE =
            DiagnosticsRetentionPolicy.DEFAULT_AGGREGATE_ROW_LIMIT;
    private static final int MAX_ROWS =
            DiagnosticsRetentionPolicy.DEFAULT_AGGREGATE_ROW_LIMIT;

    public interface PageSource {
        CompletableFuture<DiagnosticsQuery.AggregatePage> query(
                DiagnosticsQuery.AggregateQuery query);
    }

    private BehaviorDetailStatsLoader() {}

    public static CompletableFuture<BehaviorDetailStats> load(
            PageSource source, String behavior) {
        Objects.requireNonNull(source, "source");
        String selectedBehavior = behavior == null ? "" : behavior.trim();
        if (selectedBehavior.isEmpty()) {
            return CompletableFuture.completedFuture(
                    BehaviorDetailStats.empty("invalid_behavior"));
        }
        CompletableFuture<BehaviorDetailStats> result = new CompletableFuture<>();
        requestSnapshot(source, selectedBehavior, result);
        return result;
    }

    private static void requestSnapshot(
            PageSource source,
            String behavior,
            CompletableFuture<BehaviorDetailStats> result) {
        if (result.isDone()) return;
        DiagnosticsQuery.AggregateQuery.Builder builder = DiagnosticsQuery.AggregateQuery.builder()
                .dimension(DiagnosticsQuery.AggregateDimension.FEATURE)
                .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                .dimension(DiagnosticsQuery.AggregateDimension.SOURCE)
                .dimension(DiagnosticsQuery.AggregateDimension.DECISION)
                .dimension(DiagnosticsQuery.AggregateDimension.RESULT)
                .limit(QUERY_PAGE_SIZE);
        if (!DiagnosticsBehaviorClassifier.UNCLASSIFIED.equals(behavior)) {
            builder.behavior(behavior);
        }

        CompletableFuture<DiagnosticsQuery.AggregatePage> future;
        try {
            future = source.query(builder.build());
        } catch (RuntimeException error) {
            result.complete(BehaviorDetailStats.empty("query_failed"));
            return;
        }
        if (future == null) {
            result.complete(BehaviorDetailStats.empty("query_failed"));
            return;
        }
        future.whenComplete((page, failure) -> {
            if (failure != null || page == null) {
                result.complete(BehaviorDetailStats.empty("query_failed"));
                return;
            }
            if (page.errorCode != null && !page.errorCode.isEmpty()) {
                result.complete(BehaviorDetailStats.empty(page.errorCode));
                return;
            }
            if (page.items.size() > MAX_ROWS) {
                result.complete(BehaviorDetailStats.empty("result_limit_exceeded"));
                return;
            }
            if (page.hasMore) {
                result.complete(BehaviorDetailStats.empty("result_limit_exceeded"));
                return;
            }
            result.complete(BehaviorDetailStats.success(
                    page.items, page.historyTruncated));
        });
    }
}
