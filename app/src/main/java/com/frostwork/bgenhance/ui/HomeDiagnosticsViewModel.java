package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStatsLoader;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Generation-aware owner for the homepage diagnostics summary and activation state. */
public final class HomeDiagnosticsViewModel implements AutoCloseable {
    private static final int MAX_HOOK_STATES = 256;
    private static final int MAX_STATS_ROWS = RecentBehaviorStatsLoader.QUERY_PAGE_SIZE;
    private static final DiagnosticsQuery.AggregateQuery STATS_QUERY =
            DiagnosticsQuery.AggregateQuery.builder()
                    .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                    .dimension(DiagnosticsQuery.AggregateDimension.RESULT)
                    .limit(MAX_STATS_ROWS)
                    .build();

    public enum Status { LOADING, CONTENT, ERROR }

    public interface Source {
        CompletableFuture<DiagnosticsQuery.HomeDiagnosticsSnapshot> loadSnapshot();
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
        return () -> backend.queryHomeDiagnosticsSnapshotAsync(
                STATS_QUERY, MAX_STATS_ROWS, MAX_HOOK_STATES);
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
        CompletableFuture<DiagnosticsQuery.HomeDiagnosticsSnapshot> snapshot;
        try {
            snapshot = source.loadSnapshot();
        } catch (Throwable error) {
            publish(requestGeneration, State.error("database_unavailable"));
            return;
        }
        if (snapshot == null) {
            publish(requestGeneration, State.error("database_unavailable"));
            return;
        }
        snapshot.whenComplete((result, error) -> {
            State state;
            if (error != null || result == null
                    || result.behaviorStats == null || result.hookStates == null) {
                state = State.error("database_unavailable");
            } else if (!result.behaviorStats.errorCode.isEmpty()) {
                state = State.error(result.behaviorStats.errorCode);
            } else if (result.behaviorStats.items.size() > MAX_STATS_ROWS
                    || result.behaviorStats.hasMore) {
                state = State.error("result_limit_exceeded");
            } else if (!result.hookStates.errorCode.isEmpty()) {
                state = State.error(result.hookStates.errorCode);
            } else {
                RecentBehaviorStats stats = RecentBehaviorStats.fromAggregateRows(
                        result.behaviorStats.items,
                        result.behaviorStats.historyTruncated);
                state = State.content(stats, result.hookStates);
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
}
