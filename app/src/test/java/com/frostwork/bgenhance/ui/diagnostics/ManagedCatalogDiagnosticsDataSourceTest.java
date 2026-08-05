package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ManagedCatalogDiagnosticsDataSourceTest {
    @Test
    public void closeCompletesQueuedIdentityRequestWithoutLeavingFuturePending()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ManagedCatalogDiagnosticsDataSource source =
                new ManagedCatalogDiagnosticsDataSource(
                        new Delegate(),
                        () -> Collections.emptyList(),
                        uids -> {
                            loaderStarted.countDown();
                            try {
                                releaseLoader.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return DiagnosticsDataSource.UidResolutionPage.success(
                                    Collections.emptyMap());
                        },
                        () -> {},
                        executor,
                        "boot");
        try {
            CompletableFuture<DiagnosticsDataSource.UidResolutionPage> first =
                    source.resolveUidsAsync(Collections.singleton(10_001));
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<DiagnosticsDataSource.UidResolutionPage> queued =
                    source.resolveUidsAsync(Collections.singleton(10_002));

            source.close();
            assertTrue(first.isDone());
            assertTrue(queued.isDone());
            assertTrue(first.isCancelled() || first.isCompletedExceptionally());
            assertTrue(queued.isCancelled() || queued.isCompletedExceptionally());
        } finally {
            releaseLoader.countDown();
            source.close();
        }
    }

    @Test
    public void newerRequestReplacesQueuedRequestOfTheSameKind() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ManagedCatalogDiagnosticsDataSource source =
                new ManagedCatalogDiagnosticsDataSource(
                        new Delegate(),
                        () -> Collections.emptyList(),
                        uids -> {
                            int call = calls.incrementAndGet();
                            if (call == 1) {
                                firstStarted.countDown();
                                try {
                                    releaseFirst.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return DiagnosticsDataSource.UidResolutionPage.success(
                                    Collections.emptyMap());
                        },
                        () -> {},
                        executor,
                        "boot");
        try {
            CompletableFuture<DiagnosticsDataSource.UidResolutionPage> first =
                    source.resolveUidsAsync(Collections.singleton(10_001));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<DiagnosticsDataSource.UidResolutionPage> second =
                    source.resolveUidsAsync(Collections.singleton(10_002));
            CompletableFuture<DiagnosticsDataSource.UidResolutionPage> third =
                    source.resolveUidsAsync(Collections.singleton(10_003));

            releaseFirst.countDown();
            assertTrue(third.get(5, TimeUnit.SECONDS).isSuccessful());
            assertTrue(first.isCancelled() || first.isCompletedExceptionally());
            assertTrue(second.isCancelled() || second.isCompletedExceptionally());
            assertEquals(2, calls.get());
        } finally {
            releaseFirst.countDown();
            source.close();
        }
    }

    private static final class Delegate implements DiagnosticsDataSource {
        @Override
        public CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
                DiagnosticsQuery.AggregateQuery query) {
            return CompletableFuture.completedFuture(
                    new DiagnosticsQuery.AggregatePage(
                            Collections.emptyList(), 0, false, ""));
        }

        @Override
        public CompletableFuture<HookStatePage> queryHookStatesAsync(int limit) {
            return CompletableFuture.completedFuture(HookStatePage.success(
                    Collections.emptyList()));
        }
    }
}
