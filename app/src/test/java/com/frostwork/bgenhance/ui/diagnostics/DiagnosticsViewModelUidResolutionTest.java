package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class DiagnosticsViewModelUidResolutionTest {
    @Test
    public void applicationContentWaitsForUidResolutionAndUsesResolvedIdentity() {
        Source source = new Source();
        DiagnosticsViewModel viewModel = new DiagnosticsViewModel(source);
        try {
            viewModel.load(DiagnosticsViewModel.View.APPLICATION);
            source.completeAggregate(0, aggregate("", 10_321, 5L));

            assertEquals(DiagnosticsViewModel.Status.LOADING, viewModel.state().status);
            assertEquals(Set.of(10_321), source.uidSets.get(0));

            source.uidRequests.get(0).complete(identity(
                    10_321, "com.example.reader", "Reader"));

            assertEquals(DiagnosticsViewModel.Status.CONTENT, viewModel.state().status);
            DiagnosticsUiMapper.ApplicationRow row =
                    (DiagnosticsUiMapper.ApplicationRow) viewModel.state().items.get(0);
            assertEquals("com.example.reader", row.packageName);
            assertEquals("Reader", row.displayName);
            assertEquals(5L, row.totals.observed);
            assertTrue(row.manageable);
        } finally {
            viewModel.close();
        }
    }

    @Test
    public void staleUidCompletionCannotReplaceNewerRefresh() {
        Source source = new Source();
        DiagnosticsViewModel viewModel = new DiagnosticsViewModel(source);
        try {
            viewModel.load(DiagnosticsViewModel.View.APPLICATION);
            source.completeAggregate(0, aggregate("", 10_001, 1L));
            assertEquals(1, source.uidRequests.size());

            viewModel.refresh();
            source.completeAggregate(1, aggregate("", 10_002, 2L));
            assertEquals(2, source.uidRequests.size());
            source.uidRequests.get(1).complete(identity(
                    10_002, "com.example.new", "New"));
            long acceptedGeneration = viewModel.state().generation;

            source.uidRequests.get(0).complete(identity(
                    10_001, "com.example.old", "Old"));

            assertEquals(acceptedGeneration, viewModel.state().generation);
            DiagnosticsUiMapper.ApplicationRow row =
                    (DiagnosticsUiMapper.ApplicationRow) viewModel.state().items.get(0);
            assertEquals("com.example.new", row.packageName);
            assertEquals(2L, row.totals.observed);
        } finally {
            viewModel.close();
        }
    }

    private static DiagnosticsDataSource.UidResolutionPage identity(
            int uid, String packageName, String displayName) {
        return DiagnosticsDataSource.UidResolutionPage.success(Map.of(
                uid,
                DiagnosticsDataSource.UidResolution.single(
                        uid,
                        new DiagnosticsDataSource.ResolvedApplication(
                                packageName, displayName, true))));
    }

    private static DiagnosticsQuery.AggregateRow aggregate(
            String packageName, int uid, long count) {
        return new DiagnosticsQuery.AggregateRow(
                packageName,
                uid,
                "feature",
                "hook",
                "WAKE",
                "source",
                "decision",
                DiagnosticsResult.PASSED,
                count,
                count,
                0L,
                0L,
                count,
                count,
                count,
                1L,
                count);
    }

    private static final class Source implements DiagnosticsDataSource {
        final List<CompletableFuture<DiagnosticsQuery.AggregatePage>> aggregateRequests =
                new ArrayList<>();
        final List<CompletableFuture<UidResolutionPage>> uidRequests = new ArrayList<>();
        final List<Set<Integer>> uidSets = new ArrayList<>();

        @Override
        public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
                DiagnosticsQuery.AggregateQuery query) {
            CompletableFuture<DiagnosticsQuery.AggregatePage> future =
                    new CompletableFuture<>();
            aggregateRequests.add(future);
            return future;
        }

        @Override
        public CompletableFuture<ManagedApplicationPage> queryManagedApplicationsAsync() {
            return CompletableFuture.completedFuture(
                    ManagedApplicationPage.success(Collections.emptyList()));
        }

        @Override
        public CompletableFuture<UidResolutionPage> resolveUidsAsync(Set<Integer> uids) {
            uidSets.add(Set.copyOf(uids));
            CompletableFuture<UidResolutionPage> future = new CompletableFuture<>();
            uidRequests.add(future);
            return future;
        }

        @Override
        public CompletableFuture<HookStatePage> queryHookStatesAsync(int limit) {
            return CompletableFuture.completedFuture(
                    HookStatePage.success(Collections.emptyList()));
        }

        void completeAggregate(int index, DiagnosticsQuery.AggregateRow row) {
            aggregateRequests.get(index).complete(new DiagnosticsQuery.AggregatePage(
                    Collections.singletonList(row), 0, false, ""));
        }
    }
}
