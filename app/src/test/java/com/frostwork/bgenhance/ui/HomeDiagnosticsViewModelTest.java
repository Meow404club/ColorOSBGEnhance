package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class HomeDiagnosticsViewModelTest {
    @Test
    public void preservesLongCountsBeyondIntegerRange() {
        Source source = new Source();
        List<HomeDiagnosticsViewModel.State> states = new ArrayList<>();
        HomeDiagnosticsViewModel viewModel = viewModel(source, states);

        viewModel.load();
        source.snapshot.complete(snapshot(
                3_000_000_000L, 2_500_000_000L, 2_000_000_000L));

        HomeDiagnosticsViewModel.State state = states.get(states.size() - 1);
        assertEquals(HomeDiagnosticsViewModel.Status.CONTENT, state.status);
        assertEquals(3_000_000_000L, state.stats.observed);
        assertEquals(2_500_000_000L, state.stats.blocked);
        assertEquals(2_000_000_000L, state.stats.failOpen);
    }

    @Test
    public void lateOlderRequestCannotReplaceNewerResult() {
        Source first = new Source();
        SwitchingSource source = new SwitchingSource(first);
        List<HomeDiagnosticsViewModel.State> states = new ArrayList<>();
        HomeDiagnosticsViewModel viewModel = new HomeDiagnosticsViewModel(source, Runnable::run);
        viewModel.setListener(states::add);
        viewModel.load();
        Source second = new Source();
        source.current = second;
        viewModel.load();

        second.snapshot.complete(snapshot(9L, 8L, 7L));
        first.snapshot.complete(snapshot(1L, 1L, 1L));

        assertEquals(9L, states.get(states.size() - 1).stats.observed);
    }

    @Test
    public void loadIssuesOneCompositeSnapshotRequest() {
        Source source = new Source();
        List<HomeDiagnosticsViewModel.State> states = new ArrayList<>();
        HomeDiagnosticsViewModel viewModel = viewModel(source, states);

        viewModel.load();

        assertEquals(1, source.requests);
        source.snapshot.complete(snapshot(1L, 0L, 0L));
        assertEquals(HomeDiagnosticsViewModel.Status.CONTENT,
                states.get(states.size() - 1).status);
    }

    @Test
    public void queryErrorsNeverPublishFakeZeroContent() {
        Source source = new Source();
        List<HomeDiagnosticsViewModel.State> states = new ArrayList<>();
        HomeDiagnosticsViewModel viewModel = viewModel(source, states);
        viewModel.load();

        source.snapshot.complete(new DiagnosticsQuery.HomeDiagnosticsSnapshot(
                DiagnosticsQuery.AggregatePage.empty("database_unavailable"),
                new DiagnosticsQuery.HookStatePage(Collections.emptyList(), "")));

        HomeDiagnosticsViewModel.State state = states.get(states.size() - 1);
        assertEquals(HomeDiagnosticsViewModel.Status.ERROR, state.status);
        assertNull(state.stats);
    }

    @Test
    public void closeDropsPendingCompletion() {
        Source source = new Source();
        List<HomeDiagnosticsViewModel.State> states = new ArrayList<>();
        HomeDiagnosticsViewModel viewModel = viewModel(source, states);
        viewModel.load();
        int beforeClose = states.size();
        viewModel.close();

        source.snapshot.complete(snapshot(1L, 0L, 0L));

        assertEquals(beforeClose, states.size());
    }

    private static HomeDiagnosticsViewModel viewModel(
            Source source, List<HomeDiagnosticsViewModel.State> states) {
        HomeDiagnosticsViewModel viewModel =
                new HomeDiagnosticsViewModel(source, Runnable::run);
        viewModel.setListener(states::add);
        return viewModel;
    }

    private static DiagnosticsQuery.HomeDiagnosticsSnapshot snapshot(
            long observed, long blocked, long failOpen) {
        DiagnosticsQuery.AggregateRow row = new DiagnosticsQuery.AggregateRow(
                "",
                -1,
                "",
                "",
                "KILL",
                "",
                "",
                DiagnosticsResult.BLOCKED,
                observed,
                observed,
                blocked,
                failOpen,
                observed,
                0L,
                0L,
                1L,
                1L);
        DiagnosticsQuery.AggregatePage stats = new DiagnosticsQuery.AggregatePage(
                Collections.singletonList(row), 0, false, "");
        DiagnosticsQuery.HookStatePage hooks = new DiagnosticsQuery.HookStatePage(
                Collections.emptyList(), "");
        return new DiagnosticsQuery.HomeDiagnosticsSnapshot(stats, hooks);
    }

    private static class Source implements HomeDiagnosticsViewModel.Source {
        final CompletableFuture<DiagnosticsQuery.HomeDiagnosticsSnapshot> snapshot =
                new CompletableFuture<>();
        int requests;

        @Override
        public CompletableFuture<DiagnosticsQuery.HomeDiagnosticsSnapshot> loadSnapshot() {
            requests++;
            return snapshot;
        }
    }

    private static final class SwitchingSource implements HomeDiagnosticsViewModel.Source {
        volatile Source current;
        SwitchingSource(Source current) { this.current = current; }
        @Override
        public CompletableFuture<DiagnosticsQuery.HomeDiagnosticsSnapshot> loadSnapshot() {
            return current.loadSnapshot();
        }
    }
}
