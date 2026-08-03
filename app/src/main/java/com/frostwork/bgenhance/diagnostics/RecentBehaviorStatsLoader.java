package com.frostwork.bgenhance.diagnostics;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Collects every retained behavior-and-result aggregate page into one shared snapshot. */
public final class RecentBehaviorStatsLoader {
    public static final int QUERY_PAGE_SIZE = 200;
    private static final int MAX_ROWS =
            DiagnosticsRetentionPolicy.DEFAULT_AGGREGATE_ROW_LIMIT;

    public interface PageSource {
        CompletableFuture<DiagnosticsQuery.AggregatePage> query(
                DiagnosticsQuery.AggregateQuery query);
    }

    private RecentBehaviorStatsLoader() {}

    public static CompletableFuture<RecentBehaviorStats> load(PageSource source) {
        Objects.requireNonNull(source, "source");
        CompletableFuture<RecentBehaviorStats> result = new CompletableFuture<>();
        requestPage(source, 0, new ArrayList<>(), result);
        return result;
    }

    private static void requestPage(
            PageSource source,
            int offset,
            ArrayList<DiagnosticsQuery.AggregateRow> rows,
            CompletableFuture<RecentBehaviorStats> result) {
        if (result.isDone()) return;
        DiagnosticsQuery.AggregateQuery query = DiagnosticsQuery.AggregateQuery.builder()
                .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                .dimension(DiagnosticsQuery.AggregateDimension.RESULT)
                .limit(QUERY_PAGE_SIZE)
                .offset(offset)
                .build();
        CompletableFuture<DiagnosticsQuery.AggregatePage> future;
        try {
            future = source.query(query);
        } catch (RuntimeException error) {
            result.complete(RecentBehaviorStats.empty("query_failed"));
            return;
        }
        if (future == null) {
            result.complete(RecentBehaviorStats.empty("query_failed"));
            return;
        }
        future.whenComplete((page, failure) -> {
            if (failure != null || page == null) {
                result.complete(RecentBehaviorStats.empty("query_failed"));
                return;
            }
            if (page.errorCode != null && !page.errorCode.isEmpty()) {
                result.complete(RecentBehaviorStats.empty(page.errorCode));
                return;
            }
            if (rows.size() > MAX_ROWS - page.items.size()) {
                result.complete(RecentBehaviorStats.empty("result_limit_exceeded"));
                return;
            }
            rows.addAll(page.items);
            if (!page.hasMore) {
                result.complete(RecentBehaviorStats.fromAggregateRows(rows));
                return;
            }
            if (page.nextOffset <= offset) {
                result.complete(RecentBehaviorStats.empty("invalid_page_cursor"));
                return;
            }
            requestPage(source, page.nextOffset, rows, result);
        });
    }
}
