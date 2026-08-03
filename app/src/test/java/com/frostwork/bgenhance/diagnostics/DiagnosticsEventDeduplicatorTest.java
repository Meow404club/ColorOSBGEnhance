package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class DiagnosticsEventDeduplicatorTest {
    private static final long TTL = 500L;

    @Test
    public void semanticKeyIgnoresHookPathAndTransportMetadata() {
        NormalizedDiagnosticsEvent first = event(10_123, "com.example.target")
                .timestampMillis(1_000L)
                .bootId("boot-one")
                .processId("system_server")
                .featureId("upper_feature")
                .hookTargetId("upper_hook")
                .scene("upper_scene")
                .reasonCode(1)
                .configRevision(2)
                .policyRevision(3)
                .mode("CUSTOM")
                .failOpenReason("first_error")
                .latencyMicros(10L)
                .rawHitCount(1)
                .identityResolved(true)
                .eventToken("sender-one:1")
                .build();
        NormalizedDiagnosticsEvent second = first.copyBuilder()
                .timestampMillis(2_000L)
                .bootId("boot-two")
                .processId("athena")
                .featureId("lower_feature")
                .hookTargetId("lower_hook")
                .scene("lower_scene")
                .reasonCode(99)
                .configRevision(20)
                .policyRevision(30)
                .mode("ACTIVE")
                .failOpenReason("second_error")
                .latencyMicros(999L)
                .rawHitCount(7)
                .identityResolved(false)
                .eventToken("sender-two:9")
                .build();

        DiagnosticsEventSemanticKey firstKey = DiagnosticsEventSemanticKey.from(first);
        DiagnosticsEventSemanticKey secondKey = DiagnosticsEventSemanticKey.from(second);

        assertEquals(firstKey, secondKey);
        assertEquals(firstKey.hashCode(), secondKey.hashCode());
    }

    @Test
    public void semanticKeySeparatesTargetBehaviorSourceAndOutcome() {
        NormalizedDiagnosticsEvent base = event(10_123, "com.example.target").build();
        DiagnosticsEventSemanticKey key = DiagnosticsEventSemanticKey.from(base);

        assertNotEquals(key, DiagnosticsEventSemanticKey.from(
                base.copyBuilder().uid(10_124).build()));
        assertNotEquals(key, DiagnosticsEventSemanticKey.from(
                base.copyBuilder().packageName("com.example.other").build()));
        assertNotEquals(key, DiagnosticsEventSemanticKey.from(
                base.copyBuilder().behavior("WAKE").build()));
        assertNotEquals(key, DiagnosticsEventSemanticKey.from(
                base.copyBuilder().source("ALARM").build()));
        assertNotEquals(key, DiagnosticsEventSemanticKey.from(
                base.copyBuilder().decision("ALLOW_SYSTEM").build()));
        assertNotEquals(key, DiagnosticsEventSemanticKey.from(
                base.copyBuilder().result("passed").build()));
    }

    @Test
    public void missingStableTargetIdentityIsNeverDeduplicated() {
        NormalizedDiagnosticsEvent unidentified = event(-1, "").build();
        FakeTicker ticker = new FakeTicker(100L);
        DiagnosticsEventDeduplicator deduplicator = deduplicator(8, ticker);

        assertNull(DiagnosticsEventSemanticKey.from(unidentified));
        assertEquals(DiagnosticsEventDeduplicator.Outcome.FAIL_OPEN,
                deduplicator.evaluate(unidentified));
        assertEquals(DiagnosticsEventDeduplicator.Outcome.FAIL_OPEN,
                deduplicator.evaluate(unidentified));
    }

    @Test
    public void ttlUsesInjectedMonotonicTimeAndAcceptsAtBoundary() {
        FakeTicker ticker = new FakeTicker(1_000L);
        DiagnosticsEventDeduplicator deduplicator = deduplicator(8, ticker);
        NormalizedDiagnosticsEvent event = event(10_123, "com.example.target").build();

        assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                deduplicator.evaluate(event));
        ticker.set(1_000L + TTL - 1L);
        assertEquals(DiagnosticsEventDeduplicator.Outcome.DUPLICATE,
                deduplicator.evaluate(event));
        ticker.set(1_000L + TTL);
        assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                deduplicator.evaluate(event));
    }

    @Test
    public void concurrentThreadsHaveOneAcceptedSemanticEvent() throws Exception {
        FakeTicker ticker = new FakeTicker(5_000L);
        DiagnosticsEventDeduplicator deduplicator = deduplicator(8, ticker);
        NormalizedDiagnosticsEvent event = event(10_123, "com.example.target").build();
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DiagnosticsEventDeduplicator.Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return deduplicator.evaluate(event);
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            int duplicate = 0;
            for (Future<DiagnosticsEventDeduplicator.Outcome> future : futures) {
                DiagnosticsEventDeduplicator.Outcome outcome =
                        future.get(5L, TimeUnit.SECONDS);
                if (outcome == DiagnosticsEventDeduplicator.Outcome.ACCEPTED) accepted++;
                if (outcome == DiagnosticsEventDeduplicator.Outcome.DUPLICATE) duplicate++;
            }
            assertEquals(1, accepted);
            assertEquals(workers - 1, duplicate);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void capacityIsFixedAndCollisionsEvictFailOpen() {
        FakeTicker ticker = new FakeTicker(10_000L);
        DiagnosticsEventDeduplicator deduplicator = deduplicator(4, ticker);

        for (int index = 0; index < 100; index++) {
            assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                    deduplicator.evaluate(event(20_000 + index, "pkg" + index).build()));
        }

        assertEquals(4, deduplicator.occupiedSlots());
    }

    @Test
    public void clockRollbackAndClockFailureRecordFailOpen() {
        FakeTicker ticker = new FakeTicker(10_000L);
        DiagnosticsEventDeduplicator deduplicator = deduplicator(8, ticker);
        NormalizedDiagnosticsEvent event = event(10_123, "com.example.target").build();
        assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                deduplicator.evaluate(event));

        ticker.set(1_000L);
        assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                deduplicator.evaluate(event));
        assertEquals(DiagnosticsEventDeduplicator.Outcome.DUPLICATE,
                deduplicator.evaluate(event));

        DiagnosticsEventDeduplicator failing = new DiagnosticsEventDeduplicator(
                8, TTL, () -> { throw new IllegalStateException("clock unavailable"); });
        assertEquals(DiagnosticsEventDeduplicator.Outcome.FAIL_OPEN,
                failing.evaluate(event));
        assertTrue(failing.shouldRecord(event));
    }

    @Test
    public void processOrRestartBoundaryStartsWithASeparateCacheInstance() {
        FakeTicker ticker = new FakeTicker(1_000L);
        NormalizedDiagnosticsEvent event = event(10_123, "com.example.target").build();
        DiagnosticsEventDeduplicator firstProcess = deduplicator(8, ticker);
        DiagnosticsEventDeduplicator restartedProcess = deduplicator(8, ticker);

        assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                firstProcess.evaluate(event));
        assertEquals(DiagnosticsEventDeduplicator.Outcome.DUPLICATE,
                firstProcess.evaluate(event));
        assertEquals(DiagnosticsEventDeduplicator.Outcome.ACCEPTED,
                restartedProcess.evaluate(event));
    }

    private static DiagnosticsEventDeduplicator deduplicator(
            int capacity, FakeTicker ticker) {
        return new DiagnosticsEventDeduplicator(capacity, TTL, ticker::read);
    }

    private static NormalizedDiagnosticsEvent.Builder event(int uid, String packageName) {
        return NormalizedDiagnosticsEvent.builder()
                .timestampMillis(1_700_000_000_000L)
                .bootId("boot-42")
                .processId("system_server")
                .featureId("hans_freeze")
                .hookTargetId("hans_freeze_dispatch")
                .behavior("FREEZE")
                .source("FAST")
                .packageName(packageName)
                .uid(uid)
                .decision("BLOCK_FREEZE")
                .result("blocked");
    }

    private static final class FakeTicker {
        private final AtomicLong now;

        private FakeTicker(long now) {
            this.now = new AtomicLong(now);
        }

        private long read() { return now.get(); }
        private void set(long value) { now.set(value); }
    }
}
