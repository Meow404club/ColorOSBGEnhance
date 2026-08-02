package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
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
    public static final int DEFAULT_QUERY_PAGE_SIZE = 200;
    private static final int MAX_UI_PAGE_SIZE = 200;
    private static final int MAX_QUERY_PAGE_SIZE = 200;
    private static final int MAX_AGGREGATE_ROWS = 65_536;
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
        public final String errorCode;
        public final long generation;

        private State(
                View view,
                Status status,
                List<? extends DiagnosticsUiMapper.UiRow> items,
                int totalItemCount,
                boolean hasMore,
                String errorCode,
                long generation) {
            this.view = view;
            this.status = status;
            this.items = immutableRows(items);
            this.totalItemCount = Math.max(this.items.size(), totalItemCount);
            this.hasMore = hasMore;
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
            View.APPLICATION, Status.IDLE, Collections.emptyList(), 0, false, "", 0L);
    private Listener listener;
    private View selectedView = View.APPLICATION;
    private DiagnosticsDataSource.ManagedApplicationPage managedApplications =
            DiagnosticsDataSource.ManagedApplicationPage.unsupported();
    private long generation;
    private int visibleLimit;
    private boolean requestInFlight;
    private boolean aggregateComplete;
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
                new DiagnosticsUiMapper(),
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
            throw new IllegalArgumentException("queryPageSize must be between 1 and 200");
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
            managedApplicationsComplete = view != View.APPLICATION;
            applicationMappingStarted = false;
            loading = new State(
                    view, Status.LOADING, Collections.emptyList(), 0, false, "",
                    requestGeneration);
            state = loading;
        }
        emit(loading);
        if (view == View.HOOK_TARGET) {
            requestHookStates(requestGeneration);
        } else {
            if (view == View.APPLICATION) {
                requestManagedApplications(requestGeneration);
            }
            requestAggregatePage(requestGeneration, view, 0);
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

    private void requestAggregatePage(long requestGeneration, View view, int offset) {
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
        }
        DiagnosticsQuery.AggregateQuery query = aggregateQuery(view, offset);
        CompletableFuture<DiagnosticsQuery.AggregatePage> future;
        try {
            future = dataSource.queryAggregatesAsync(query);
        } catch (RuntimeException error) {
            finishError(requestGeneration, view, "query_failed");
            return;
        }
        if (future == null) {
            finishError(requestGeneration, view, "query_failed");
            return;
        }
        future.whenComplete((page, failure) ->
                handleAggregatePage(requestGeneration, view, offset, page, failure));
    }

    private void handleAggregatePage(
            long requestGeneration,
            View view,
            int requestedOffset,
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

        int nextOffset = -1;
        List<DiagnosticsQuery.AggregateRow> rowsToMap = null;
        String pagingError = "";
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
            if (aggregateRows.size() > MAX_AGGREGATE_ROWS - page.items.size()) {
                pagingError = "result_limit_exceeded";
            } else {
                aggregateRows.addAll(page.items);
                if (page.hasMore) {
                    if (page.nextOffset <= requestedOffset) {
                        pagingError = "invalid_page_cursor";
                    } else {
                        nextOffset = page.nextOffset;
                    }
                } else {
                    rowsToMap = new ArrayList<>(aggregateRows);
                }
            }
        }
        if (!pagingError.isEmpty()) {
            finishError(requestGeneration, view, pagingError);
        } else if (nextOffset >= 0) {
            requestAggregatePage(requestGeneration, view, nextOffset);
        } else if (rowsToMap != null) {
            finishAggregateMapping(requestGeneration, view, rowsToMap);
        }
    }

    private void finishAggregateMapping(
            long requestGeneration,
            View view,
            List<DiagnosticsQuery.AggregateRow> rows) {
        if (view == View.APPLICATION) {
            synchronized (lock) {
                if (!isCurrentLocked(requestGeneration, view)) return;
                aggregateComplete = true;
            }
            tryStartApplicationMapping(requestGeneration);
            return;
        }
        List<? extends DiagnosticsUiMapper.UiRow> mapped;
        try {
            mapped = mapper.mapBehaviors(rows);
        } catch (RuntimeException error) {
            finishError(requestGeneration, view, "mapping_failed");
            return;
        }
        finishContent(requestGeneration, view, mapped);
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
        finishContent(requestGeneration, View.APPLICATION, mapped);
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
            finishContent(requestGeneration, View.HOOK_TARGET, mapped);
        });
    }

    private void finishContent(
            long requestGeneration,
            View view,
            List<? extends DiagnosticsUiMapper.UiRow> mapped) {
        State next;
        synchronized (lock) {
            if (!isCurrentLocked(requestGeneration, view)) return;
            allRows = immutableRows(mapped);
            requestInFlight = false;
            visibleLimit = Math.min(uiPageSize, allRows.size());
            next = allRows.isEmpty()
                    ? new State(view, Status.EMPTY, Collections.emptyList(), 0, false, "",
                            requestGeneration)
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
            managedApplicationsComplete = false;
            applicationMappingStarted = false;
            String errorCode = normalizeError(requestedErrorCode);
            error = new State(view, Status.ERROR, Collections.emptyList(), 0, false,
                    errorCode, requestGeneration);
            state = error;
        }
        emit(error);
    }

    private DiagnosticsQuery.AggregateQuery aggregateQuery(View view, int offset) {
        DiagnosticsQuery.AggregateQuery.Builder builder =
                DiagnosticsQuery.AggregateQuery.builder()
                        .limit(queryPageSize)
                        .offset(offset)
                        .dimension(DiagnosticsQuery.AggregateDimension.BEHAVIOR)
                        .dimension(DiagnosticsQuery.AggregateDimension.RESULT);
        if (view == View.APPLICATION) {
            builder.dimension(DiagnosticsQuery.AggregateDimension.APPLICATION);
        }
        return builder.build();
    }

    private State contentStateLocked() {
        int count = Math.min(visibleLimit, allRows.size());
        return new State(
                selectedView,
                Status.CONTENT,
                allRows.subList(0, count),
                allRows.size(),
                count < allRows.size(),
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
