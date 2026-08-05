package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.residency.ResidencyAppCatalog;
import com.frostwork.bgenhance.ui.residency.ResidencyAppItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds an asynchronously loaded managed-application catalog to a backend data source. */
final class ManagedCatalogDiagnosticsDataSource implements DiagnosticsDataSource {
    private enum RequestKind { MANAGED_APPLICATIONS, UID_RESOLUTION }

    interface ManagedApplicationLoader {
        List<ManagedApplication> load();
    }

    interface UidResolutionLoader {
        UidResolutionPage load(Set<Integer> uids);
    }

    private final DiagnosticsDataSource delegate;
    private final ManagedApplicationLoader loader;
    private final UidResolutionLoader uidResolutionLoader;
    private final Runnable uidResolutionCloser;
    private final ExecutorService catalogExecutor;
    private final String currentBootId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Object requestLock = new Object();
    private QueuedRequest managedRequest;
    private QueuedRequest uidRequest;
    private QueuedRequest activeRequest;
    private boolean drainScheduled;
    private long requestSequence;

    static DiagnosticsDataSource create(
            Context context, ResidencyDiagnosticsBackend backend) {
        Context requested = Objects.requireNonNull(context, "context");
        Context applicationContext = requested.getApplicationContext();
        Context stableContext = applicationContext == null ? requested : applicationContext;
        DiagnosticsDataSource delegate = new BackendDiagnosticsDataSource(backend);
        ResidencyAppCatalog catalog = new ResidencyAppCatalog(stableContext);
        DiagnosticsUidResolver uidResolver = new DiagnosticsUidResolver(stableContext);
        return new ManagedCatalogDiagnosticsDataSource(
                delegate,
                () -> toManagedApplications(catalog.load(ResidencyAppCatalog.Source.MANAGED)),
                uidResolver::resolve,
                uidResolver::close,
                newCatalogExecutor(),
                delegate.currentBootId());
    }

    ManagedCatalogDiagnosticsDataSource(
            DiagnosticsDataSource delegate,
            ManagedApplicationLoader loader,
            ExecutorService catalogExecutor,
            String currentBootId) {
        this(delegate, loader, ignored -> UidResolutionPage.unsupported(),
                () -> {}, catalogExecutor, currentBootId);
    }

    ManagedCatalogDiagnosticsDataSource(
            DiagnosticsDataSource delegate,
            ManagedApplicationLoader loader,
            UidResolutionLoader uidResolutionLoader,
            Runnable uidResolutionCloser,
            ExecutorService catalogExecutor,
            String currentBootId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.uidResolutionLoader = Objects.requireNonNull(
                uidResolutionLoader, "uidResolutionLoader");
        this.uidResolutionCloser = Objects.requireNonNull(
                uidResolutionCloser, "uidResolutionCloser");
        this.catalogExecutor = Objects.requireNonNull(catalogExecutor, "catalogExecutor");
        this.currentBootId = currentBootId == null ? "" : currentBootId.trim();
    }

    @Override
    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query) {
        return delegate.queryAggregatesAsync(query);
    }

    @Override
    public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregateSnapshotAsync(
            DiagnosticsQuery.AggregateQuery query, int maxRows) {
        return delegate.queryAggregateSnapshotAsync(query, maxRows);
    }

    @Override
    public CompletableFuture<HookStatePage> queryHookStatesAsync(int limit) {
        return delegate.queryHookStatesAsync(limit);
    }

    @Override
    public CompletableFuture<ManagedApplicationPage> queryManagedApplicationsAsync() {
        CompletableFuture<ManagedApplicationPage> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> pending.remove(result));
        boolean accepted = enqueue(
                RequestKind.MANAGED_APPLICATIONS,
                result,
                () -> {
                    if (result.isDone()) return;
                    try {
                        List<ManagedApplication> applications = loader.load();
                        result.complete(ManagedApplicationPage.success(applications));
                    } catch (Throwable error) {
                        result.complete(ManagedApplicationPage.failure(
                                closed.get() ? "catalog_closed" : "catalog_unavailable"));
                    }
                });
        if (!accepted) {
            result.complete(ManagedApplicationPage.failure("catalog_closed"));
        }
        return result;
    }

    @Override
    public CompletableFuture<UidResolutionPage> resolveUidsAsync(Set<Integer> uids) {
        CompletableFuture<UidResolutionPage> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> pending.remove(result));
        LinkedHashSet<Integer> requested = new LinkedHashSet<>();
        if (uids != null) {
            for (Integer uid : uids) {
                if (uid != null && uid >= 0) requested.add(uid);
            }
        }
        boolean accepted = enqueue(
                RequestKind.UID_RESOLUTION,
                result,
                () -> {
                    if (result.isDone()) return;
                    try {
                        UidResolutionPage page = uidResolutionLoader.load(requested);
                        result.complete(page == null
                                ? UidResolutionPage.failure("identity_unavailable") : page);
                    } catch (Throwable error) {
                        result.complete(UidResolutionPage.failure(
                                closed.get() ? "catalog_closed" : "identity_unavailable"));
                    }
                });
        if (!accepted) {
            result.complete(UidResolutionPage.failure("catalog_closed"));
        }
        return result;
    }

    @Override
    public String currentBootId() {
        return currentBootId;
    }

    @Override
    public void close() {
        List<CompletableFuture<?>> toCancel;
        synchronized (requestLock) {
            if (!closed.compareAndSet(false, true)) return;
            toCancel = new ArrayList<>(pending);
            managedRequest = null;
            uidRequest = null;
            activeRequest = null;
            drainScheduled = false;
        }
        cancelFutures(toCancel);
        catalogExecutor.shutdownNow();
        pending.clear();
        try {
            uidResolutionCloser.run();
        } catch (RuntimeException ignored) {
            // Closing UI-owned package identity resources is best effort.
        }
        try {
            delegate.close();
        } catch (RuntimeException ignored) {
            // Closing UI-owned diagnostics resources is best effort.
        }
    }

    boolean isCatalogExecutorShutdown() {
        return catalogExecutor.isShutdown();
    }

    private boolean enqueue(
            RequestKind kind, CompletableFuture<?> future, Runnable action) {
        boolean scheduleDrain = false;
        ArrayList<CompletableFuture<?>> superseded = new ArrayList<>(2);
        synchronized (requestLock) {
            if (closed.get()) return false;
            pending.add(future);
            QueuedRequest request = new QueuedRequest(
                    kind, ++requestSequence, future, action);
            QueuedRequest previous = kind == RequestKind.MANAGED_APPLICATIONS
                    ? managedRequest : uidRequest;
            if (previous != null) superseded.add(previous.future);
            if (activeRequest != null && activeRequest.kind == kind) {
                superseded.add(activeRequest.future);
            }
            if (kind == RequestKind.MANAGED_APPLICATIONS) {
                managedRequest = request;
            } else {
                uidRequest = request;
            }
            if (!drainScheduled) {
                drainScheduled = true;
                scheduleDrain = true;
            }
        }
        cancelFutures(superseded);
        if (!scheduleDrain) return true;
        try {
            catalogExecutor.execute(this::drainRequests);
            return true;
        } catch (RuntimeException rejected) {
            ArrayList<CompletableFuture<?>> rejectedRequests = new ArrayList<>(2);
            synchronized (requestLock) {
                drainScheduled = false;
                if (managedRequest != null) {
                    rejectedRequests.add(managedRequest.future);
                }
                if (uidRequest != null) {
                    rejectedRequests.add(uidRequest.future);
                }
                managedRequest = null;
                uidRequest = null;
            }
            cancelFutures(rejectedRequests);
            return false;
        }
    }

    private static void cancelFutures(List<CompletableFuture<?>> futures) {
        for (CompletableFuture<?> future : futures) {
            if (future != null) future.cancel(false);
        }
    }

    private void drainRequests() {
        while (true) {
            QueuedRequest request;
            synchronized (requestLock) {
                if (closed.get()) {
                    drainScheduled = false;
                    activeRequest = null;
                    return;
                }
                request = takeNextRequestLocked();
                if (request == null) {
                    drainScheduled = false;
                    activeRequest = null;
                    return;
                }
                activeRequest = request;
            }
            try {
                if (!request.future.isDone()) request.action.run();
            } catch (Throwable error) {
                request.future.completeExceptionally(error);
            } finally {
                synchronized (requestLock) {
                    if (activeRequest == request) activeRequest = null;
                }
            }
        }
    }

    private QueuedRequest takeNextRequestLocked() {
        if (managedRequest == null && uidRequest == null) return null;
        QueuedRequest result;
        if (managedRequest == null) {
            result = uidRequest;
            uidRequest = null;
        } else if (uidRequest == null) {
            result = managedRequest;
            managedRequest = null;
        } else if (managedRequest.sequence <= uidRequest.sequence) {
            result = managedRequest;
            managedRequest = null;
        } else {
            result = uidRequest;
            uidRequest = null;
        }
        return result;
    }

    private static List<ManagedApplication> toManagedApplications(
            List<ResidencyAppItem> items) {
        ArrayList<ManagedApplication> result = new ArrayList<>();
        if (items == null) return result;
        for (ResidencyAppItem item : items) {
            if (item == null) continue;
            result.add(new ManagedApplication(
                    item.packageName(), item.label(), !item.safety().isRestricted()));
        }
        return result;
    }

    private static ExecutorService newCatalogExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DiagnosticsManagedApps");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class QueuedRequest {
        final RequestKind kind;
        final long sequence;
        final CompletableFuture<?> future;
        final Runnable action;

        QueuedRequest(
                RequestKind kind,
                long sequence,
                CompletableFuture<?> future,
                Runnable action) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.sequence = sequence;
            this.future = Objects.requireNonNull(future, "future");
            this.action = Objects.requireNonNull(action, "action");
        }
    }
}
