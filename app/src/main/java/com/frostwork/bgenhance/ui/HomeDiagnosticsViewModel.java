package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsDataSource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Generation-aware owner for the homepage diagnostics summary and activation state. */
public final class HomeDiagnosticsViewModel implements AutoCloseable {
    public enum Status { LOADING, CONTENT, ERROR }

    public interface Source {
        CompletableFuture<RecentBehaviorStats> loadStats();
        CompletableFuture<DiagnosticsQuery.HookStatePage> loadHookStates();
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        public final Status status;
        public final RecentBehaviorStats stats;
        public final DiagnosticsQuery.HookStatePage hookStates;
        public final String errorCode;

        private State(
                Status status,
                RecentBehaviorStats stats,
                DiagnosticsQuery.HookStatePage hookStates,
                String errorCode) {
            this.status = status;
            this.stats = stats;
            this.hookStates = hookStates;
            this.errorCode = errorCode == null ? "" : errorCode;
        }

        static State loading() {
            return new State(Status.LOADING, null, null, "");
        }

        static State content(
                RecentBehaviorStats stats,
                DiagnosticsQuery.HookStatePage hookStates) {
            return new State(Status.CONTENT, stats, hookStates, "");
        }

        static State error(String errorCode) {
            return new State(Status.ERROR, null, null,
                    errorCode == null || errorCode.isEmpty()
                            ? "database_unavailable" : errorCode);
        }
    }

    private final Source source;
    private final Executor callbackExecutor;
    private int generation;
    private boolean closed;
    private Listener listener;

    public HomeDiagnosticsViewModel(Source source, Executor callbackExecutor) {
        this.source = Objects.requireNonNull(source, "source");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    public static Source backendSource(ResidencyDiagnosticsBackend backend) {
        Objects.requireNonNull(backend, "backend");
        DiagnosticsDataSource dataSource = DiagnosticsDataSource.fromBackend(backend);
        return new Source() {
            @Override
            public CompletableFuture<RecentBehaviorStats> loadStats() {
                return dataSource.queryRecentBehaviorStatsAsync();
            }

            @Override
            public CompletableFuture<DiagnosticsQuery.HookStatePage> loadHookStates() {
                return backend.queryHookStatesAsync(256);
            }
        };
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public void load() {
        final int requestGeneration;
        synchronized (this) {
            if (closed) return;
            requestGeneration = ++generation;
        }
        publish(requestGeneration, State.loading());
        CompletableFuture<RecentBehaviorStats> stats;
        CompletableFuture<DiagnosticsQuery.HookStatePage> hookStates;
        try {
            stats = source.loadStats();
            hookStates = source.loadHookStates();
        } catch (Throwable error) {
            publish(requestGeneration, State.error("database_unavailable"));
            return;
        }
        if (stats == null || hookStates == null) {
            publish(requestGeneration, State.error("database_unavailable"));
            return;
        }
        stats.thenCombine(hookStates, Result::new).whenComplete((result, error) -> {
            State state;
            if (error != null || result == null
                    || result.stats == null || result.hookStates == null) {
                state = State.error("database_unavailable");
            } else if (!result.stats.errorCode.isEmpty()) {
                state = State.error(result.stats.errorCode);
            } else if (!result.hookStates.errorCode.isEmpty()) {
                state = State.error(result.hookStates.errorCode);
            } else {
                state = State.content(result.stats, result.hookStates);
            }
            publish(requestGeneration, state);
        });
    }

    private void publish(int requestGeneration, State state) {
        try {
            callbackExecutor.execute(() -> {
                Listener current;
                synchronized (HomeDiagnosticsViewModel.this) {
                    if (closed || requestGeneration != generation) return;
                    current = listener;
                }
                if (current != null) current.onStateChanged(state);
            });
        } catch (Throwable ignored) {
            // A destroyed UI executor must not affect diagnostics or the module process.
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        listener = null;
    }

    private static final class Result {
        final RecentBehaviorStats stats;
        final DiagnosticsQuery.HookStatePage hookStates;

        Result(
                RecentBehaviorStats stats,
                DiagnosticsQuery.HookStatePage hookStates) {
            this.stats = stats;
            this.hookStates = hookStates;
        }
    }
}
