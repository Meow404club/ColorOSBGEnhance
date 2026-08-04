package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStats;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Generation-aware state owner for one behavior detail screen. */
public final class DiagnosticsBehaviorDetailViewModel implements AutoCloseable {
    public enum Status {
        IDLE,
        LOADING,
        CONTENT,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        public final DiagnosticsBehavior behavior;
        public final Status status;
        public final List<DiagnosticsUiMapper.BehaviorDetailRow> items;
        public final DiagnosticsUiMapper.Metrics totals;
        public final boolean historyTruncated;
        public final String errorCode;
        public final long generation;

        private State(
                DiagnosticsBehavior behavior,
                Status status,
                List<DiagnosticsUiMapper.BehaviorDetailRow> items,
                DiagnosticsUiMapper.Metrics totals,
                boolean historyTruncated,
                String errorCode,
                long generation) {
            this.behavior = behavior;
            this.status = status;
            this.items = items == null || items.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(items));
            this.totals = totals == null ? DiagnosticsUiMapper.emptyMetrics() : totals;
            this.historyTruncated = historyTruncated;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.generation = generation;
        }
    }

    private final Object lock = new Object();
    private final DiagnosticsBehavior behavior;
    private final DiagnosticsDataSource dataSource;
    private final DiagnosticsUiMapper mapper;
    private final Executor callbackExecutor;
    private State state;
    private Listener listener;
    private long generation;
    private boolean closed;

    public static DiagnosticsBehaviorDetailViewModel create(
            Context context,
            ResidencyDiagnosticsBackend backend,
            DiagnosticsBehavior behavior) {
        Context requested = Objects.requireNonNull(context, "context");
        Context applicationContext = requested.getApplicationContext();
        Context stableContext = applicationContext == null ? requested : applicationContext;
        return new DiagnosticsBehaviorDetailViewModel(
                behavior,
                DiagnosticsDataSource.fromBackend(backend),
                new DiagnosticsUiMapper(),
                stableContext.getMainExecutor());
    }

    public DiagnosticsBehaviorDetailViewModel(
            DiagnosticsBehavior behavior,
            DiagnosticsDataSource dataSource,
            DiagnosticsUiMapper mapper,
            Executor callbackExecutor) {
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        state = new State(
                behavior, Status.IDLE, Collections.emptyList(), null,
                false, "", 0L);
    }

    public void setListener(Listener listener) {
        State snapshot;
        synchronized (lock) {
            if (closed) return;
            this.listener = listener;
            snapshot = state;
        }
        if (listener != null) emit(snapshot);
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public void load() {
        final long requestGeneration;
        final State loading;
        synchronized (lock) {
            if (closed) return;
            requestGeneration = ++generation;
            loading = new State(
                    behavior, Status.LOADING, Collections.emptyList(), null,
                    false, "",
                    requestGeneration);
            state = loading;
        }
        emit(loading);

        CompletableFuture<BehaviorDetailStats> future;
        try {
            future = dataSource.queryBehaviorDetailsAsync(behavior);
        } catch (RuntimeException error) {
            finishError(requestGeneration, "query_failed");
            return;
        }
        if (future == null) {
            finishError(requestGeneration, "query_failed");
            return;
        }
        future.whenComplete((snapshot, failure) -> {
            if (failure != null || snapshot == null) {
                finishError(requestGeneration, "query_failed");
                return;
            }
            if (!snapshot.isSuccessful()) {
                finishError(requestGeneration, snapshot.errorCode);
                return;
            }
            List<DiagnosticsUiMapper.BehaviorDetailRow> rows;
            try {
                rows = mapper.mapBehaviorDetails(behavior, snapshot);
            } catch (RuntimeException error) {
                finishError(requestGeneration, "mapping_failed");
                return;
            }
            DiagnosticsUiMapper.Metrics totals = DiagnosticsUiMapper.sumDetailMetrics(rows);
            finishContent(
                    requestGeneration, rows, totals, snapshot.historyTruncated);
        });
    }

    public void refresh() {
        load();
    }

    private void finishContent(
            long requestGeneration,
            List<DiagnosticsUiMapper.BehaviorDetailRow> rows,
            DiagnosticsUiMapper.Metrics totals,
            boolean historyTruncated) {
        State content;
        synchronized (lock) {
            if (closed || requestGeneration != generation) return;
            content = new State(
                    behavior, Status.CONTENT, rows, totals,
                    historyTruncated, "", requestGeneration);
            state = content;
        }
        emit(content);
    }

    private void finishError(long requestGeneration, String errorCode) {
        State error;
        synchronized (lock) {
            if (closed || requestGeneration != generation) return;
            error = new State(
                    behavior,
                    Status.ERROR,
                    Collections.emptyList(),
                    null,
                    false,
                    errorCode == null || errorCode.isEmpty()
                            ? "database_unavailable" : errorCode,
                    requestGeneration);
            state = error;
        }
        emit(error);
    }

    private void emit(State expected) {
        callbackExecutor.execute(() -> {
            Listener target;
            synchronized (lock) {
                if (closed || state != expected) return;
                target = listener;
            }
            if (target != null) target.onStateChanged(expected);
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            listener = null;
            generation++;
        }
        dataSource.close();
    }
}
