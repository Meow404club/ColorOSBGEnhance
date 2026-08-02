package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class DiagnosticsViewModelTest {
    @Test
    public void lateResultFromOlderGenerationIsDiscarded() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 2);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        viewModel.refresh();
        assertEquals(2, source.aggregateRequests.size());

        source.aggregateRequests.get(1).future.complete(page(
                Collections.singletonList(row("com.example.new", "KILL", 7L)), false, 0));
        DiagnosticsViewModel.State accepted = viewModel.state();
        assertEquals(DiagnosticsViewModel.Status.CONTENT, accepted.status);
        assertEquals("application:com.example.new", accepted.items.get(0).stableId());

        source.aggregateRequests.get(0).future.complete(page(
                Collections.singletonList(row("com.example.old", "KILL", 99L)), false, 0));

        assertSame(accepted, viewModel.state());
        assertEquals("application:com.example.new", viewModel.state().items.get(0).stableId());
    }

    @Test
    public void databasePagesAreCollectedBeforeStableUiPagination() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 1, 2);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        AggregateRequest first = source.aggregateRequests.get(0);
        assertEquals(0, first.query.offset);
        assertTrue(first.query.dimensions.contains(
                DiagnosticsQuery.AggregateDimension.APPLICATION));
        assertTrue(first.query.dimensions.contains(
                DiagnosticsQuery.AggregateDimension.BEHAVIOR));
        assertTrue(first.query.dimensions.contains(
                DiagnosticsQuery.AggregateDimension.RESULT));

        first.future.complete(page(
                Collections.singletonList(row("com.example.one", "KILL", 10L)), true, 2));
        assertEquals(2, source.aggregateRequests.size());
        AggregateRequest second = source.aggregateRequests.get(1);
        assertEquals(2, second.query.offset);
        second.future.complete(page(
                Collections.singletonList(row("com.example.two", "FREEZE", 5L)), false, 0));

        DiagnosticsViewModel.State firstUiPage = viewModel.state();
        assertEquals(DiagnosticsViewModel.Status.CONTENT, firstUiPage.status);
        assertEquals(1, firstUiPage.items.size());
        assertEquals(2, firstUiPage.totalItemCount);
        assertTrue(firstUiPage.hasMore);

        viewModel.loadNextPage();
        DiagnosticsViewModel.State secondUiPage = viewModel.state();
        assertEquals(2, secondUiPage.items.size());
        assertFalse(secondUiPage.hasMore);
        assertEquals(2, source.aggregateRequests.size());
    }

    @Test
    public void behaviorViewPublishesAllSixFamilies() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);

        viewModel.load(DiagnosticsViewModel.View.BEHAVIOR);
        source.aggregateRequests.get(0).future.complete(page(
                Collections.singletonList(row("com.example.one", "RECOVERY", 3L)),
                false, 0));

        DiagnosticsViewModel.State state = viewModel.state();
        assertEquals(DiagnosticsViewModel.Status.CONTENT, state.status);
        assertEquals(6, state.items.size());
        assertEquals("behavior:KILL", state.items.get(0).stableId());
        assertEquals("behavior:RECOVERY", state.items.get(5).stableId());
    }

    @Test
    public void hookCatalogRowsUseClientSidePagination() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 5, 10);

        viewModel.load(DiagnosticsViewModel.View.HOOK_TARGET);
        source.hookRequests.get(0).complete(
                DiagnosticsDataSource.HookStatePage.success(Collections.emptyList()));

        DiagnosticsViewModel.State state = viewModel.state();
        assertEquals(DiagnosticsViewModel.Status.CONTENT, state.status);
        assertEquals(5, state.items.size());
        assertEquals(DiagnosticsHookCatalog.all().size(), state.totalItemCount);
        assertTrue(state.hasMore);

        viewModel.loadNextPage();
        assertEquals(10, viewModel.state().items.size());
    }

    @Test
    public void aggregateErrorIsNotPublishedAsEmptyData() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        source.aggregateRequests.get(0).future.complete(
                DiagnosticsQuery.AggregatePage.empty("database_unavailable"));

        assertEquals(DiagnosticsViewModel.Status.ERROR, viewModel.state().status);
        assertEquals("database_unavailable", viewModel.state().errorCode);
        assertTrue(viewModel.state().items.isEmpty());
    }

    @Test
    public void hookErrorIsNotPublishedAsMissingHooks() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);

        viewModel.load(DiagnosticsViewModel.View.HOOK_TARGET);
        source.hookRequests.get(0).complete(
                DiagnosticsDataSource.HookStatePage.failure("database_unavailable"));

        assertEquals(DiagnosticsViewModel.Status.ERROR, viewModel.state().status);
        assertEquals("database_unavailable", viewModel.state().errorCode);
        assertTrue(viewModel.state().items.isEmpty());
    }

    @Test
    public void closeDropsInFlightCompletionAndListenerDelivery() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);
        List<DiagnosticsViewModel.State> delivered = new ArrayList<>();
        viewModel.setListener(delivered::add);
        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        int deliveredBeforeClose = delivered.size();

        viewModel.close();
        source.aggregateRequests.get(0).future.complete(page(
                Collections.singletonList(row("com.example.late", "KILL", 1L)), false, 0));

        assertTrue(viewModel.isClosed());
        assertEquals(deliveredBeforeClose, delivered.size());
    }

    private static DiagnosticsViewModel viewModel(
            DiagnosticsDataSource source, int uiPageSize, int queryPageSize) {
        return new DiagnosticsViewModel(
                source, new DiagnosticsUiMapper(), Runnable::run,
                uiPageSize, queryPageSize);
    }

    private static DiagnosticsQuery.AggregatePage page(
            List<DiagnosticsQuery.AggregateRow> rows, boolean hasMore, int nextOffset) {
        return new DiagnosticsQuery.AggregatePage(rows, nextOffset, hasMore, "");
    }

    private static DiagnosticsQuery.AggregateRow row(
            String packageName, String behavior, long count) {
        return new DiagnosticsQuery.AggregateRow(
                packageName,
                10_001,
                "feature",
                "hook",
                behavior,
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

    private static final class AggregateRequest {
        final DiagnosticsQuery.AggregateQuery query;
        final CompletableFuture<DiagnosticsQuery.AggregatePage> future =
                new CompletableFuture<>();

        AggregateRequest(DiagnosticsQuery.AggregateQuery query) {
            this.query = query;
        }
    }

    private static final class FakeDataSource implements DiagnosticsDataSource {
        final List<AggregateRequest> aggregateRequests = new ArrayList<>();
        final List<CompletableFuture<HookStatePage>> hookRequests = new ArrayList<>();

        @Override
        public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
                DiagnosticsQuery.AggregateQuery query) {
            AggregateRequest request = new AggregateRequest(query);
            aggregateRequests.add(request);
            return request.future;
        }

        @Override
        public CompletableFuture<HookStatePage> queryHookStatesAsync(int limit) {
            CompletableFuture<HookStatePage> request = new CompletableFuture<>();
            hookRequests.add(request);
            return request;
        }
    }
}
