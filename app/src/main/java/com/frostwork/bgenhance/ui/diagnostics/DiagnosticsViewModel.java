package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Generation-aware asynchronous state owner for application, behavior and Hook diagnostics.
 * The injected callback executor is owned by the caller and is never shut down here.
 */
public final class DiagnosticsViewModel implements AutoCloseable {
    public static final int DEFAULT_UI_PAGE_SIZE = 40;
    public static final int DEFAULT_QUERY_PAGE_SIZE =
            com.frostwork.bgenhance.diagnostics.DiagnosticsRetentionPolicy
                    .DEFAULT_AGGREGATE_ROW_LIMIT;
    private static final int MAX_UI_PAGE_SIZE = 200;
    private static final int MAX_QUERY_PAGE_SIZE =
            com.frostwork.bgenhance.diagnostics.DiagnosticsRetentionPolicy
                    .DEFAULT_AGGREGATE_ROW_LIMIT;
    private static final int MAX_AGGREGATE_ROWS =
            com.frostwork.bgenhance.diagnostics.DiagnosticsRetentionPolicy
                    .MAX_AGGREGATE_SNAPSHOT_ROWS;
    private static final int MAX_HOOK_STATES = 512;

    public enum View {
        APPLICATION,
        BEHAVIOR,
        HOOK_TARGET
    }

    public enum Status {
        IDLE,
        LOADING,
        CONTENT,
        EMPTY,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        public final View view;
        public final Status status;
        public final List<DiagnosticsUiMapper.UiRow> items;
        public final int totalItemCount;
        public final boolean hasMore;
        public final boolean historyTruncated;
        public final String errorCode;
        public final long generation;

        private State(
                View view,
                Status status,
                List<? extends DiagnosticsUiMapper.UiRow> items,
                int totalItemCount,
                boolean hasMore,
                boolean historyTruncated,
                String errorCode,
                long generation) {
            this.view = view;
            this.status = status;
            this.items = immutableRows(items);
            this.totalItemCount = Math.max(this.items.size(), totalItemCount);
            this.hasMore = hasMore;
            this.historyTruncated = historyTruncated;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.generation = generation;
        }

        private static List<DiagnosticsUiMapper.UiRow> immutableRows(
                List<? extends DiagnosticsUiMapper.UiRow> values) {
            if (values == null || values.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    private final Object lock = new Object();
    private final DiagnosticsDataSource dataSource;
    private final DiagnosticsUiMapper mapper;
    private final Executor callbackExecutor;
    private final int uiPageSize;
    private final int queryPageSize;

    private final ArrayList<DiagnosticsQuery.AggregateRow> aggregateRows = new ArrayList<>();
    private List<DiagnosticsUiMapper.UiRow> allRows = Collections.emptyList();
    private State state = new State(
            View.APPLICATION, Status.IDLE, Collections.emptyList(), 0,
            false, false, "", 0L);
    private Listener listener;
    private View selectedView = View.APPLICATION;
    private DiagnosticsDataSource.ManagedApplicationPage managedApplications =
            DiagnosticsDataSource.ManagedApplicationPage.unsupported();
    private long generation;
    private int visibleLimit;
    private boolean requestInFlight;
    private boolean aggregateComplete;
    private boolean aggregateHistoryTruncated;
    private boolean contentHistoryTruncated;
    private boolean managedApplicationsComplete;
    private boolean applicationMappingStarted;
    private boolean closed;

    public static DiagnosticsViewModel create(
            Context context, ResidencyDiagnosticsBackend backend) {
        Context requested = Objects.requireNonNull(context, "context");
        Context applicationContext = requested.getApplicationContext();
        Context stableContext = applicationContext == null ? requested : applicationContext;
        return create(stableContext, backend, stableContext.getMainExecutor());
    }

    public static DiagnosticsViewModel create(
            Context context,
            ResidencyDiagnosticsBackend backend,
            Executor callbackExecutor) {
        Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        return new DiagnosticsViewModel(
                DiagnosticsDataSource.fromBackend(context, backend),
                new DiagnosticsUiMapper(
                        () -> ModuleSettings.getConfigRevision(context),
                        () -> ModuleSettings.getResidencyPolicy(context).policyRevision()),
                callbackExecutor,
                DEFAULT_UI_PAGE_SIZE,
                DEFAULT_QUERY_PAGE_SIZE);
    }

    public DiagnosticsViewModel(DiagnosticsDataSource dataSource) {
        this(dataSource, new DiagnosticsUiMapper(), Runnable::run,
                DEFAULT_UI_PAGE_SIZE, DEFAULT_QUERY_PAGE_SIZE);
    }

    public DiagnosticsViewModel(
            DiagnosticsDataSource dataSource,
            DiagnosticsUiMapper mapper,
            Executor callbackExecutor,
            int uiPageSize,
            int queryPageSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        if (uiPageSize <= 0 || uiPageSize > MAX_UI_PAGE_SIZE) {
            throw new IllegalArgumentException("uiPageSize must be between 1 and 200");
        }
        if (queryPageSize <= 0 || queryPageSize > MAX_QUERY_PAGE_SIZE) {
            throw new IllegalArgumentException("queryPageSize is outside the supported range");
        }
        this.uiPageSize = uiPageSize;
        this.queryPageSize = queryPageSize;
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

    public void load(View view) {
        Objects.requireNonNull(view, "view");
        final long requestGeneration;
        final State loading;
        synchronized (lock) {
            if (closed) return;
            requestGeneration = ++generation;
            selectedView = view;
            requestInFlight = true;
            visibleLimit = uiPageSize;
            aggregateRows.clear();
            allRows = Collections.emptyList();
            managedApplications = DiagnosticsDataSource.ManagedApplicationPage.unsupported();
            aggregateComplete = false;
            aggregateHistoryTruncated = false;
            contentHistoryTruncated = false;
            managedApplicationsComplete = view != View.APPLICATION;
            applicationMappingStarted = false;
            loading = new State(
                    view, Status.LOADING, Collections.emptyList(), 0,
                    false, false, "",
                    requestGeneration);
            state = loading;
        }
        emit(loading);
        if (view == View.HOOK_TARGET) {
            requestHookStates(requestGeneration);
        } else if (view == View.BEHAVIOR) {
            requestBehaviorStats(requestGeneration);
        } else {
            requestManagedApplications(requestGeneration);
            requestAggregateSnapshot(requestGeneration, view);
        }
    }

    public void refresh() {
        View view;
        synchronized (lock) {
            if (closed) return;
            view = selectedView;
        }
        load(view);
    }

    public void loadNextPage() {
        State next;
        synchronized (lock) {
            if (closed || requestInFlight || state.status != Status.CONTENT
                    || !state.hasMore) return;
            visibleLimit = Math.min(allRows.size(), visibleLimit + uiPageSize);
            next = contentStateLocked();
            state = next;
        }
        emit(next);
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    @Override
    public void close() {
        boolean closeDataSource;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            requestInFlight = false;
            listener = null;
            aggregateRows.clear();
            allRows = Collections.emptyList();
            closeDataSource = true;
        }
        if (closeDataSource) {
            try {
                dataSource.close();
            } catch (RuntimeException ignored) {
                // A UI teardown cannot be failed by optional diagnostics cleanup.
            }
        }
    }

    private void requestAggregateSnapshot(long requestGeneration, View view) {
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
        }
        DiagnosticsQuery.AggregateQuery query = applicationAggregateQuery(0);
        CompletableFuture<DiagnosticsQuery.AggregatePage> future;
        try {
            future = dataSource.queryAggregateSnapshotAsync(query, MAX_AGGREGATE_ROWS);
        } catch (RuntimeException error) {
            finishError(requestGeneration, view, "query_failed");
            return;
        }
        if (future == null) {
            finishError(requestGeneration, view, "query_failed");
            return;
        }
        future.whenComplete((page, failure) ->
                handleAggregateSnapshot(requestGeneration, view, page, failure));
    }

    private void handleAggregateSnapshot(
            long requestGeneration,
            View view,
            DiagnosticsQuery.AggregatePage page,
            Throwable failure) {
        if (failure != null || page == null) {
            finishError(requestGeneration, view, "query_failed");
            return;
        }
        if (page.errorCode != null && !page.errorCode.isEmpty()) {
            finishError(requestGeneration, view, page.errorCode);
            return;
        }

        if (page.hasMore) {
            finishError(requestGeneration, view, "result_limit_exceeded");
            return;
        }
        if (page.items.size() > MAX_AGGREGATE_ROWS) {
            finishError(requestGeneration, view, "result_limit_exceeded");
            return;
        }
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
            aggregateRows.addAll(page.items);
            aggregateHistoryTruncated |= page.historyTruncated;
        }
        finishApplicationAggregateMapping(requestGeneration);
    }

    private void finishApplicationAggregateMapping(long requestGeneration) {
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, View.APPLICATION)) return;
            aggregateComplete = true;
        }
        tryStartApplicationMapping(requestGeneration);
    }

    private void requestBehaviorStats(long requestGeneration) {
        CompletableFuture<RecentBehaviorStats> future;
        try {
            future = dataSource.queryRecentBehaviorStatsAsync();
        } catch (RuntimeException error) {
            finishError(requestGeneration, View.BEHAVIOR, "query_failed");
            return;
        }
        if (future == null) {
            finishError(requestGeneration, View.BEHAVIOR, "query_failed");
            return;
        }
        future.whenComplete((snapshot, failure) -> {
            if (failure != null || snapshot == null) {
                finishError(requestGeneration, View.BEHAVIOR, "query_failed");
                return;
            }
            if (!snapshot.isSuccessful()) {
                finishError(requestGeneration, View.BEHAVIOR, snapshot.errorCode);
                return;
            }
            List<DiagnosticsUiMapper.BehaviorRow> mapped;
            try {
                mapped = mapper.mapBehaviors(snapshot);
            } catch (RuntimeException error) {
                finishError(requestGeneration, View.BEHAVIOR, "mapping_failed");
                return;
            }
            finishContent(
                    requestGeneration, View.BEHAVIOR, mapped, snapshot.historyTruncated);
        });
    }

    private void requestManagedApplications(long requestGeneration) {
        CompletableFuture<DiagnosticsDataSource.ManagedApplicationPage> future;
        try {
            future = dataSource.queryManagedApplicationsAsync();
        } catch (RuntimeException error) {
            finishError(requestGeneration, View.APPLICATION, "catalog_unavailable");
            return;
        }
        if (future == null) {
            finishError(requestGeneration, View.APPLICATION, "catalog_unavailable");
            return;
        }
        future.whenComplete((page, failure) -> {
            if (failure != null || page == null) {
                finishError(requestGeneration, View.APPLICATION, "catalog_unavailable");
                return;
            }
            if (!page.isSuccessful()) {
                finishError(requestGeneration, View.APPLICATION, page.errorCode);
                return;
            }
            synchronized (lock) {
                if (!isCurrentLocked(requestGeneration, View.APPLICATION)) return;
                managedApplications = page;
                managedApplicationsComplete = true;
            }
            tryStartApplicationMapping(requestGeneration);
        });
    }

    private void tryStartApplicationMapping(long requestGeneration) {
        List<DiagnosticsQuery.AggregateRow> aggregates;
        DiagnosticsDataSource.ManagedApplicationPage applications;
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, View.APPLICATION)
                    || !aggregateComplete
                    || !managedApplicationsComplete
                    || applicationMappingStarted) return;
            applicationMappingStarted = true;
            aggregates = new ArrayList<>(aggregateRows);
            applications = managedApplications;
        }

        List<DiagnosticsUiMapper.ApplicationRow> mapped;
        try {
            mapped = applications.authoritative
                    ? mapper.mapApplications(applications.items, aggregates)
                    : mapper.mapApplications(aggregates);
        } catch (RuntimeException error) {
            finishError(requestGeneration, View.APPLICATION, "mapping_failed");
            return;
        }
        boolean historyTruncated;
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, View.APPLICATION)) return;
            historyTruncated = aggregateHistoryTruncated;
        }
        finishContent(
                requestGeneration, View.APPLICATION, mapped, historyTruncated);
    }

    private void requestHookStates(long requestGeneration) {
        String currentBootId;
        try {
            currentBootId = dataSource.currentBootId();
        } catch (RuntimeException error) {
            finishError(requestGeneration, View.HOOK_TARGET, "query_failed");
            return;
        }
        CompletableFuture<DiagnosticsDataSource.HookStatePage> future;
        try {
            future = dataSource.queryHookStatesAsync(MAX_HOOK_STATES);
        } catch (RuntimeException error) {
            finishError(requestGeneration, View.HOOK_TARGET, "query_failed");
            return;
        }
        if (future == null) {
            finishError(requestGeneration, View.HOOK_TARGET, "query_failed");
            return;
        }
        future.whenComplete((page, failure) -> {
            if (failure != null || page == null) {
                finishError(requestGeneration, View.HOOK_TARGET, "query_failed");
                return;
            }
            if (!page.isSuccessful()) {
                finishError(requestGeneration, View.HOOK_TARGET, page.errorCode);
                return;
            }
            List<DiagnosticsUiMapper.HookTargetRow> mapped;
            try {
                mapped = mapper.mapHookTargets(page, currentBootId);
            } catch (RuntimeException error) {
                finishError(requestGeneration, View.HOOK_TARGET, "mapping_failed");
                return;
            }
            finishContent(requestGeneration, View.HOOK_TARGET, mapped, false);
        });
    }

    private void finishContent(
            long requestGeneration,
            View view,
            List<? extends DiagnosticsUiMapper.UiRow> mapped,
            boolean historyTruncated) {
        State next;
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
            allRows = immutableRows(mapped);
            contentHistoryTruncated = historyTruncated;
            requestInFlight = false;
            visibleLimit = Math.min(uiPageSize, allRows.size());
            next = allRows.isEmpty()
                    ? new State(view, Status.EMPTY, Collections.emptyList(), 0,
                            false, historyTruncated, "", requestGeneration)
                    : contentStateLocked();
            state = next;
        }
        emit(next);
    }

    private void finishError(long requestGeneration, View view, String requestedErrorCode) {
        State error;
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
            requestInFlight = false;
            aggregateRows.clear();
            allRows = Collections.emptyList();
            aggregateComplete = false;
            aggregateHistoryTruncated = false;
            contentHistoryTruncated = false;
            managedApplicationsComplete = false;
            applicationMappingStarted = false;
            String errorCode = normalizeError(requestedErrorCode);
            error = new State(view, Status.ERROR, Collections.emptyList(), 0,
                    false, false, errorCode, requestGeneration);
            state = error;
        }
        emit(error);
    }

    private DiagnosticsQuery.AggregateQuery applicationAggregateQuery(int offset) {
        return DiagnosticsQuery.AggregateQuery.builder()
                .limit(queryPageSize)
                .offset(offset)
                .dimension(DiagnosticsQuery.AggregateDimension.APPLICATION)
                .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                .dimension(DiagnosticsQuery.AggregateDimension.RESULT)
                .build();
    }

    private State contentStateLocked() {
        int count = Math.min(visibleLimit, allRows.size());
        return new State(
                selectedView,
                Status.CONTENT,
                allRows.subList(0, count),
                allRows.size(),
                count < allRows.size(),
                contentHistoryTruncated,
                "",
                generation);
    }

    private boolean isCurrentLocked(long requestGeneration, View view) {
        return !closed
                && requestInFlight
                && generation == requestGeneration
                && selectedView == view;
    }

    private void emit(State expectedState) {
        try {
            callbackExecutor.execute(() -> {
                Listener current;
                synchronized (lock) {
                    if (closed || state != expectedState
                            || generation != expectedState.generation) return;
                    current = listener;
                }
                if (current != null) {
                    try {
                        current.onStateChanged(expectedState);
                    } catch (RuntimeException ignored) {
                        // A rendering callback cannot invalidate the data state.
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // A detached UI executor must not make diagnostics affect module behavior.
        }
    }

    private static List<DiagnosticsUiMapper.UiRow> immutableRows(
            List<? extends DiagnosticsUiMapper.UiRow> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String normalizeError(String value) {
        return value == null || value.trim().isEmpty() ? "query_failed" : value.trim();
    }
}
