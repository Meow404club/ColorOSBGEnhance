package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class DiagnosticsBehaviorDetailViewModelTest {
    @Test
    public void lateCompletionFromPreviousRefreshIsDiscarded() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsBehaviorDetailViewModel viewModel = viewModel(source);

        viewModel.load();
        viewModel.refresh();
        assertEquals(2, source.requests.size());

        source.requests.get(1).complete(page(7L));
        DiagnosticsBehaviorDetailViewModel.State accepted = viewModel.state();
        assertEquals(DiagnosticsBehaviorDetailViewModel.Status.CONTENT, accepted.status);
        assertEquals(7L, accepted.totals.observed);

        source.requests.get(0).complete(page(99L));
        assertSame(accepted, viewModel.state());
        assertEquals(7L, viewModel.state().totals.observed);
    }

    @Test
    public void fixedZeroRowsAreContentAndBackendFailuresAreErrors() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsBehaviorDetailViewModel viewModel = viewModel(source);
        viewModel.load();
        source.requests.get(0).complete(new DiagnosticsQuery.AggregatePage(
                Collections.emptyList(), 0, false, ""));

        assertEquals(DiagnosticsBehaviorDetailViewModel.Status.CONTENT,
                viewModel.state().status);
        assertEquals(17, viewModel.state().items.size());
        assertEquals(0L, viewModel.state().totals.observed);

        viewModel.refresh();
        source.requests.get(1).complete(
                DiagnosticsQuery.AggregatePage.empty("database_unavailable"));
        assertEquals(DiagnosticsBehaviorDetailViewModel.Status.ERROR,
                viewModel.state().status);
        assertEquals("database_unavailable", viewModel.state().errorCode);
    }

    @Test
    public void publishesHistoryTruncationFromAggregateSnapshot() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsBehaviorDetailViewModel viewModel = viewModel(source);
        viewModel.load();
        source.requests.get(0).complete(new DiagnosticsQuery.AggregatePage(
                Collections.emptyList(), 0, false, true, ""));

        assertEquals(DiagnosticsBehaviorDetailViewModel.Status.CONTENT,
                viewModel.state().status);
        assertTrue(viewModel.state().historyTruncated);
    }

    @Test
    public void closeDropsInFlightResultsAndClosesSourceOnce() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsBehaviorDetailViewModel viewModel = viewModel(source);
        List<DiagnosticsBehaviorDetailViewModel.State> delivered = new ArrayList<>();
        viewModel.setListener(delivered::add);
        viewModel.load();
        int beforeClose = delivered.size();

        viewModel.close();
        viewModel.close();
        source.requests.get(0).complete(page(3L));

        assertEquals(beforeClose, delivered.size());
        assertEquals(1, source.closeCalls);
        assertTrue(viewModel.state().status
                == DiagnosticsBehaviorDetailViewModel.Status.LOADING);
    }

    private static DiagnosticsBehaviorDetailViewModel viewModel(FakeDataSource source) {
        return new DiagnosticsBehaviorDetailViewModel(
                DiagnosticsBehavior.KILL,
                source,
                new DiagnosticsUiMapper(),
                Runnable::run);
    }

    private static DiagnosticsQuery.AggregatePage page(long count) {
        DiagnosticsQuery.AggregateRow row = new DiagnosticsQuery.AggregateRow(
                "", -1, FeatureIds.ATHENA_PERIODIC, "hook", "KILL", "",
                "decision", DiagnosticsResult.PASSED,
                count, count, 0L, 0L, count, 0L, 0L, 1L, 2L);
        return new DiagnosticsQuery.AggregatePage(
                Collections.singletonList(row), 0, false, "");
    }

    private static final class FakeDataSource implements DiagnosticsDataSource {
        final List<CompletableFuture<DiagnosticsQuery.AggregatePage>> requests =
                new ArrayList<>();
        int closeCalls;

        @Override
        public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
                DiagnosticsQuery.AggregateQuery query) {
            CompletableFuture<DiagnosticsQuery.AggregatePage> future =
                    new CompletableFuture<>();
            requests.add(future);
            return future;
        }

        @Override
        public CompletableFuture<HookStatePage> queryHookStatesAsync(int limit) {
            return CompletableFuture.completedFuture(
                    HookStatePage.success(Collections.emptyList()));
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
