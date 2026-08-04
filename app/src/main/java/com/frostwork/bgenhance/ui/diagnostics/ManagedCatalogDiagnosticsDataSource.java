package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.residency.ResidencyAppCatalog;
import com.frostwork.bgenhance.ui.residency.ResidencyAppItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds an asynchronously loaded managed-application catalog to a backend data source. */
final class ManagedCatalogDiagnosticsDataSource implements DiagnosticsDataSource {
    interface ManagedApplicationLoader {
        List<ManagedApplication> load();
    }

    private final DiagnosticsDataSource delegate;
    private final ManagedApplicationLoader loader;
    private final ExecutorService catalogExecutor;
    private final String currentBootId;
    private final AtomicBoolean closed = new AtomicBoolean();

    static DiagnosticsDataSource create(
            Context context, ResidencyDiagnosticsBackend backend) {
        Context requested = Objects.requireNonNull(context, "context");
        Context applicationContext = requested.getApplicationContext();
        Context stableContext = applicationContext == null ? requested : applicationContext;
        DiagnosticsDataSource delegate = new BackendDiagnosticsDataSource(backend);
        ResidencyAppCatalog catalog = new ResidencyAppCatalog(stableContext);
        return new ManagedCatalogDiagnosticsDataSource(
                delegate,
                () -> toManagedApplications(catalog.load(ResidencyAppCatalog.Source.MANAGED)),
                newCatalogExecutor(),
                delegate.currentBootId());
    }

    ManagedCatalogDiagnosticsDataSource(
            DiagnosticsDataSource delegate,
            ManagedApplicationLoader loader,
            ExecutorService catalogExecutor,
            String currentBootId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.loader = Objects.requireNonNull(loader, "loader");
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
        if (closed.get()) {
            result.complete(ManagedApplicationPage.failure("catalog_closed"));
            return result;
        }
        try {
            catalogExecutor.execute(() -> {
                if (closed.get()) {
                    result.complete(ManagedApplicationPage.failure("catalog_closed"));
                    return;
                }
                try {
                    List<ManagedApplication> applications = loader.load();
                    result.complete(ManagedApplicationPage.success(applications));
                } catch (Throwable error) {
                    result.complete(ManagedApplicationPage.failure(
                            closed.get() ? "catalog_closed" : "catalog_unavailable"));
                }
            });
        } catch (RuntimeException rejected) {
            result.complete(ManagedApplicationPage.failure("catalog_closed"));
        }
        return result;
    }

    @Override
    public String currentBootId() {
        return currentBootId;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        catalogExecutor.shutdownNow();
        try {
            delegate.close();
        } catch (RuntimeException ignored) {
            // Closing UI-owned diagnostics resources is best effort.
        }
    }

    boolean isCatalogExecutorShutdown() {
        return catalogExecutor.isShutdown();
    }

    private static List<ManagedApplication> toManagedApplications(
            List<ResidencyAppItem> items) {
        ArrayList<ManagedApplication> result = new ArrayList<>();
        if (items == null) return result;
        for (ResidencyAppItem item : items) {
            if (item == null) continue;
            result.add(new ManagedApplication(item.packageName(), item.label()));
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
}
