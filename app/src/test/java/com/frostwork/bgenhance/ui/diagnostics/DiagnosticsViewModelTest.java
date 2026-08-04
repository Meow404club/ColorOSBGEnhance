package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    public void databaseSnapshotIsCollectedBeforeStableUiPagination() {
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
                java.util.Arrays.asList(
                        row("com.example.one", "KILL", 10L),
                        row("com.example.two", "FREEZE", 5L)),
                false,
                0));

        DiagnosticsViewModel.State firstUiPage = viewModel.state();
        assertEquals(DiagnosticsViewModel.Status.CONTENT, firstUiPage.status);
        assertEquals(1, firstUiPage.items.size());
        assertEquals(2, firstUiPage.totalItemCount);
        assertTrue(firstUiPage.hasMore);

        viewModel.loadNextPage();
        DiagnosticsViewModel.State secondUiPage = viewModel.state();
        assertEquals(2, secondUiPage.items.size());
        assertFalse(secondUiPage.hasMore);
        assertEquals(1, source.aggregateRequests.size());
    }

    @Test
    public void applicationHistoryTruncationSurvivesUiPagination() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 1, 2);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        source.aggregateRequests.get(0).future.complete(new DiagnosticsQuery.AggregatePage(
                java.util.Arrays.asList(
                        row("com.example.one", "KILL", 1L),
                        row("com.example.two", "FREEZE", 1L)),
                0, false, true, ""));

        assertTrue(viewModel.state().historyTruncated);
        viewModel.loadNextPage();
        assertTrue(viewModel.state().historyTruncated);
    }

    @Test
    public void incompleteApplicationSnapshotIsReportedAsAnError() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 2);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        source.aggregateRequests.get(0).future.complete(page(
                Collections.singletonList(row("com.example.one", "KILL", 1L)),
                true,
                0));

        assertEquals(DiagnosticsViewModel.Status.ERROR, viewModel.state().status);
        assertEquals("result_limit_exceeded", viewModel.state().errorCode);
    }

    @Test
    public void behaviorHistoryTruncationIsPublished() {
        FakeDataSource source = new FakeDataSource();
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);

        viewModel.load(DiagnosticsViewModel.View.BEHAVIOR);
        source.aggregateRequests.get(0).future.complete(new DiagnosticsQuery.AggregatePage(
                Collections.singletonList(row("", "KILL", 1L)),
                0, false, true, ""));

        assertEquals(DiagnosticsViewModel.Status.CONTENT, viewModel.state().status);
        assertTrue(viewModel.state().historyTruncated);
    }

    @Test
    public void managedApplicationWithoutEventsIsStillPublishedWithDisplayName() {
        FakeDataSource source = new FakeDataSource();
        source.provideManagedCatalog = true;
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        source.aggregateRequests.get(0).future.complete(page(
                Collections.emptyList(), false, 0));
        assertEquals(DiagnosticsViewModel.Status.LOADING, viewModel.state().status);

        source.managedRequests.get(0).complete(
                DiagnosticsDataSource.ManagedApplicationPage.success(
                        Collections.singletonList(
                                new DiagnosticsDataSource.ManagedApplication(
                                        "com.example.zero", "Zero Events"))));

        DiagnosticsViewModel.State state = viewModel.state();
        assertEquals(DiagnosticsViewModel.Status.CONTENT, state.status);
        assertEquals(1, state.items.size());
        DiagnosticsUiMapper.ApplicationRow row =
                (DiagnosticsUiMapper.ApplicationRow) state.items.get(0);
        assertEquals("Zero Events", row.displayName);
        assertTrue(row.totals.isEmpty());
    }

    @Test
    public void lateManagedCatalogResultFromOlderGenerationIsDiscarded() {
        FakeDataSource source = new FakeDataSource();
        source.provideManagedCatalog = true;
        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);

        viewModel.load(DiagnosticsViewModel.View.APPLICATION);
        viewModel.refresh();
        source.aggregateRequests.get(1).future.complete(page(
                Collections.emptyList(), false, 0));
        source.managedRequests.get(1).complete(
                DiagnosticsDataSource.ManagedApplicationPage.success(
                        Collections.singletonList(
                                new DiagnosticsDataSource.ManagedApplication(
                                        "com.example.new", "New"))));
        DiagnosticsViewModel.State accepted = viewModel.state();

        source.managedRequests.get(0).complete(
                DiagnosticsDataSource.ManagedApplicationPage.success(
                        Collections.singletonList(
                                new DiagnosticsDataSource.ManagedApplication(
                                        "com.example.old", "Old"))));
        source.aggregateRequests.get(0).future.complete(page(
                Collections.singletonList(row("com.example.old", "KILL", 50L)),
                false, 0));

        assertSame(accepted, viewModel.state());
        assertEquals("application:com.example.new", accepted.items.get(0).stableId());
    }

    @Test
    public void behaviorViewKeepsUnclassifiedHiddenWhenThereAreNoUnknownEvents() {
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
        assertEquals(1, source.closeCalls);
    }

    @Test
    public void managedCatalogLoaderIsAsyncAndViewModelCloseReleasesExecutor()
            throws Exception {
        FakeDataSource delegate = new FakeDataSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> loaderThread = new AtomicReference<>();
        ManagedCatalogDiagnosticsDataSource source = new ManagedCatalogDiagnosticsDataSource(
                delegate,
                () -> {
                    loaderThread.set(Thread.currentThread());
                    return Collections.singletonList(
                            new DiagnosticsDataSource.ManagedApplication(
                                    "com.example.managed", "Managed"));
                },
                executor,
                "boot");

        Thread caller = Thread.currentThread();
        DiagnosticsDataSource.ManagedApplicationPage page =
                source.queryManagedApplicationsAsync().get(5, TimeUnit.SECONDS);
        assertTrue(page.authoritative);
        assertNotEquals(caller, loaderThread.get());

        DiagnosticsViewModel viewModel = viewModel(source, 10, 10);
        viewModel.close();
        viewModel.close();

        assertTrue(source.isCatalogExecutorShutdown());
        assertEquals(1, delegate.closeCalls);
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

    private static class FakeDataSource implements DiagnosticsDataSource {
        final List<AggregateRequest> aggregateRequests = new ArrayList<>();
        final List<CompletableFuture<HookStatePage>> hookRequests = new ArrayList<>();
        final List<CompletableFuture<ManagedApplicationPage>> managedRequests =
                new ArrayList<>();
        boolean provideManagedCatalog;
        int closeCalls;

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

        @Override
        public CompletableFuture<ManagedApplicationPage> queryManagedApplicationsAsync() {
            if (!provideManagedCatalog) {
                return DiagnosticsDataSource.super.queryManagedApplicationsAsync();
            }
            CompletableFuture<ManagedApplicationPage> request = new CompletableFuture<>();
            managedRequests.add(request);
            return request;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
