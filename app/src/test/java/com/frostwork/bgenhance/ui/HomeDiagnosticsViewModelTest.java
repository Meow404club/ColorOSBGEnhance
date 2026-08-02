package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        source.stats.complete(stats(3_000_000_000L, 2_500_000_000L, 2_000_000_000L));
        source.hooks.complete(new DiagnosticsQuery.HookStatePage(
                Collections.emptyList(), ""));

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

        second.stats.complete(stats(9L, 8L, 7L));
        second.hooks.complete(new DiagnosticsQuery.HookStatePage(
                Collections.emptyList(), ""));
        first.stats.complete(stats(1L, 1L, 1L));
        first.hooks.complete(new DiagnosticsQuery.HookStatePage(
                Collections.emptyList(), ""));

        assertEquals(9L, states.get(states.size() - 1).stats.observed);
    }

    @Test
    public void queryErrorsNeverPublishFakeZeroContent() {
        Source source = new Source();
        List<HomeDiagnosticsViewModel.State> states = new ArrayList<>();
        HomeDiagnosticsViewModel viewModel = viewModel(source, states);
        viewModel.load();

        source.stats.complete(DiagnosticsQuery.HomeStatsSnapshot.empty(
                "database_unavailable"));
        source.hooks.complete(new DiagnosticsQuery.HookStatePage(
                Collections.emptyList(), ""));

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

        source.stats.complete(stats(1L, 0L, 0L));
        source.hooks.complete(new DiagnosticsQuery.HookStatePage(
                Collections.emptyList(), ""));

        assertEquals(beforeClose, states.size());
    }

    private static HomeDiagnosticsViewModel viewModel(
            Source source, List<HomeDiagnosticsViewModel.State> states) {
        HomeDiagnosticsViewModel viewModel =
                new HomeDiagnosticsViewModel(source, Runnable::run);
        viewModel.setListener(states::add);
        return viewModel;
    }

    private static DiagnosticsQuery.HomeStatsSnapshot stats(
            long observed, long blocked, long failOpen) {
        LinkedHashMap<String, DiagnosticsQuery.FeatureStats> values = new LinkedHashMap<>();
        values.put("feature", new DiagnosticsQuery.FeatureStats(
                "feature", observed, blocked, failOpen,
                0L, "", "", "", "", ""));
        return new DiagnosticsQuery.HomeStatsSnapshot(values, "");
    }

    private static class Source implements HomeDiagnosticsViewModel.Source {
        final CompletableFuture<DiagnosticsQuery.HomeStatsSnapshot> stats =
                new CompletableFuture<>();
        final CompletableFuture<DiagnosticsQuery.HookStatePage> hooks =
                new CompletableFuture<>();

        @Override public CompletableFuture<DiagnosticsQuery.HomeStatsSnapshot> loadStats() {
            return stats;
        }

        @Override public CompletableFuture<DiagnosticsQuery.HookStatePage> loadHookStates() {
            return hooks;
        }
    }

    private static final class SwitchingSource implements HomeDiagnosticsViewModel.Source {
        volatile Source current;
        SwitchingSource(Source current) { this.current = current; }
        @Override public CompletableFuture<DiagnosticsQuery.HomeStatsSnapshot> loadStats() {
            return current.loadStats();
        }
        @Override public CompletableFuture<DiagnosticsQuery.HookStatePage> loadHookStates() {
            return current.loadHookStates();
        }
    }
}
