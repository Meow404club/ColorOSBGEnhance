package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Owns asynchronous loading, filtering and incremental list state for residency apps. */
public final class ResidencyAppsViewModel implements AutoCloseable {
    private static final int PAGE_SIZE = 40;

    public enum Status {
        LOADING,
        CONTENT,
        EMPTY,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        private final Status status;
        private final List<ResidencyAppItem> items;
        private final int filteredCount;
        private final boolean hasMore;

        private State(
                Status status,
                List<ResidencyAppItem> items,
                int filteredCount,
                boolean hasMore) {
            this.status = status;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.filteredCount = filteredCount;
            this.hasMore = hasMore;
        }

        public Status status() { return status; }
        public List<ResidencyAppItem> items() { return items; }
        public int filteredCount() { return filteredCount; }
        public boolean hasMore() { return hasMore; }
    }

    private final ResidencyAppCatalog.Source source;
    private final ResidencyAppCatalog catalog;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private List<ResidencyAppItem> loadedItems = Collections.emptyList();
    private ResidencyAppFilter.ModeFilter modeFilter = ResidencyAppFilter.ModeFilter.ALL;
    private ResidencyAppFilter.StatusFilter statusFilter = ResidencyAppFilter.StatusFilter.ALL;
    private String query = "";
    private boolean showSystemApps;
    private int visibleLimit = PAGE_SIZE;
    private int generation;
    private boolean loading;
    private boolean hasLoaded;
    private boolean closed;
    private Listener listener;

    public ResidencyAppsViewModel(Context context, ResidencyAppCatalog.Source source) {
        this.source = Objects.requireNonNull(source, "source");
        this.catalog = new ResidencyAppCatalog(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor(new NamedThreadFactory());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void load() {
        if (closed) return;
        int requestGeneration = ++generation;
        loading = true;
        publish(new State(Status.LOADING, Collections.emptyList(), 0, false));
        executor.execute(() -> {
            try {
                List<ResidencyAppItem> result = catalog.load(source);
                mainHandler.post(() -> {
                    if (closed || requestGeneration != generation) return;
                    loading = false;
                    hasLoaded = true;
                    loadedItems = result;
                    visibleLimit = PAGE_SIZE;
                    rebuild();
                });
            } catch (RuntimeException exception) {
                mainHandler.post(() -> {
                    if (closed || requestGeneration != generation) return;
                    loading = false;
                    publish(new State(Status.ERROR, Collections.emptyList(), 0, false));
                });
            }
        });
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
        visibleLimit = PAGE_SIZE;
        rebuild();
    }

    public void setModeFilter(ResidencyAppFilter.ModeFilter modeFilter) {
        this.modeFilter = Objects.requireNonNull(modeFilter, "modeFilter");
        visibleLimit = PAGE_SIZE;
        rebuild();
    }

    public void setStatusFilter(ResidencyAppFilter.StatusFilter statusFilter) {
        this.statusFilter = Objects.requireNonNull(statusFilter, "statusFilter");
        visibleLimit = PAGE_SIZE;
        rebuild();
    }

    public void setShowSystemApps(boolean showSystemApps) {
        this.showSystemApps = showSystemApps;
        visibleLimit = PAGE_SIZE;
        rebuild();
    }

    public void loadNextPage() {
        if (closed || loading || !hasLoaded) return;
        visibleLimit += PAGE_SIZE;
        rebuild();
    }

    private void rebuild() {
        if (closed) return;
        if (loading || !hasLoaded) return;
        if (loadedItems.isEmpty()) {
            publish(new State(Status.EMPTY, Collections.emptyList(), 0, false));
            return;
        }
        List<ResidencyAppItem> filtered = ResidencyAppFilter.apply(
                loadedItems, source, query, modeFilter, statusFilter, showSystemApps);
        if (filtered.isEmpty()) {
            publish(new State(Status.EMPTY, Collections.emptyList(), 0, false));
            return;
        }
        int count = Math.min(visibleLimit, filtered.size());
        publish(new State(
                Status.CONTENT,
                filtered.subList(0, count),
                filtered.size(),
                count < filtered.size()));
    }

    private void publish(State state) {
        Listener current = listener;
        if (current != null) current.onStateChanged(state);
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        listener = null;
        executor.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ResidencyAppsLoader");
            thread.setDaemon(true);
            return thread;
        }
    }
}
