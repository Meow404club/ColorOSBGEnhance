package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class WakeThrottleStateTest {
    private static final int UID = 10_801;
    private static final long CONFIG_REVISION = 21L;
    private static final long POLICY_REVISION = 13L;
    private static final long IDENTITY_GENERATION = 8L;
    private static final long COOLDOWN_MS = 60_000L;

    @Test
    public void firstWakeIsAllowedAndCooldownWakeIsThrottled() {
        WakeThrottleState state = new WakeThrottleState();

        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 1_000L));
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                evaluate(state, WakeSource.PACKET, 60_999L));
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 61_000L));
    }

    @Test
    public void packetAndAlarmHaveIndependentWindows() {
        WakeThrottleState state = new WakeThrottleState();

        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 5_000L));
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.ALARM, 5_000L));
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                evaluate(state, WakeSource.PACKET, 5_001L));
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                evaluate(state, WakeSource.ALARM, 5_001L));
    }

    @Test
    public void revisionChangesStartFreshWindows() {
        WakeThrottleState state = new WakeThrottleState();
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 10_000L));

        assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                UID, WakeSource.PACKET, CONFIG_REVISION + 1L, POLICY_REVISION,
                IDENTITY_GENERATION, COOLDOWN_MS, 10_001L));
        assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                UID, WakeSource.PACKET, CONFIG_REVISION + 1L, POLICY_REVISION + 1L,
                IDENTITY_GENERATION, COOLDOWN_MS, 10_002L));
    }

    @Test
    public void elapsedClockRollbackDoesNotExtendAWindowForever() {
        WakeThrottleState state = new WakeThrottleState();
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 50_000L));
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 1_000L));
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                evaluate(state, WakeSource.PACKET, 1_001L));
    }

    @Test
    public void concurrentFirstWakeHasOneWinner() throws Exception {
        WakeThrottleState state = new WakeThrottleState();
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WakeThrottleState.Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return evaluate(state, WakeSource.PACKET, 20_000L);
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();

            int allowed = 0;
            int throttled = 0;
            for (Future<WakeThrottleState.Outcome> future : futures) {
                WakeThrottleState.Outcome outcome = future.get(5L, TimeUnit.SECONDS);
                if (outcome == WakeThrottleState.Outcome.ALLOWED) allowed++;
                if (outcome == WakeThrottleState.Outcome.THROTTLED) throttled++;
            }
            assertEquals(1, allowed);
            assertEquals(workers - 1, throttled);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentFirstWakeHasOneWinnerWhenBucketIsFull() throws Exception {
        WakeThrottleState state = new WakeThrottleState(4);
        for (int index = 0; index < 4; index++) {
            assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                    UID + index,
                    index == 0 ? WakeSource.ALARM : WakeSource.PACKET,
                    CONFIG_REVISION,
                    POLICY_REVISION,
                    IDENTITY_GENERATION,
                    COOLDOWN_MS,
                    1_000L));
        }

        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WakeThrottleState.Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return state.evaluate(
                            UID + 100,
                            WakeSource.ALARM,
                            CONFIG_REVISION,
                            POLICY_REVISION,
                            IDENTITY_GENERATION,
                            COOLDOWN_MS,
                            2_000L);
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
            int allowed = 0;
            int throttled = 0;
            for (Future<WakeThrottleState.Outcome> future : futures) {
                WakeThrottleState.Outcome outcome = future.get(5L, TimeUnit.SECONDS);
                if (outcome == WakeThrottleState.Outcome.ALLOWED) allowed++;
                if (outcome == WakeThrottleState.Outcome.THROTTLED) throttled++;
            }
            assertEquals(1, allowed);
            assertEquals(workers - 1, throttled);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void staleVersionsCannotReplaceCurrentWindows() {
        WakeThrottleState state = new WakeThrottleState();
        assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                UID, WakeSource.PACKET, 22L, 14L, 9L,
                COOLDOWN_MS, 10_000L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, 21L, 13L, 8L,
                COOLDOWN_MS, 10_001L));
        assertEquals(WakeThrottleState.Outcome.THROTTLED, state.evaluate(
                UID, WakeSource.PACKET, 22L, 14L, 9L,
                COOLDOWN_MS, 10_002L));
    }

    @Test
    public void identityRebuildStartsFreshWindowAndRejectsLateOldGeneration() {
        WakeThrottleState state = new WakeThrottleState();
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 10_000L));
        assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                UID, WakeSource.PACKET, CONFIG_REVISION, POLICY_REVISION,
                IDENTITY_GENERATION + 1L, COOLDOWN_MS, 10_001L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN,
                evaluate(state, WakeSource.PACKET, 10_002L));
        assertEquals(WakeThrottleState.Outcome.THROTTLED, state.evaluate(
                UID, WakeSource.PACKET, CONFIG_REVISION, POLICY_REVISION,
                IDENTITY_GENERATION + 1L, COOLDOWN_MS, 10_003L));
    }

    @Test
    public void configAdvanceBeforeThrottleReturnMakesOldRequestFailOpen() throws Exception {
        assertVersionAdvanceInvalidatesPendingThrottle(
                CONFIG_REVISION + 1L, POLICY_REVISION, IDENTITY_GENERATION);
    }

    @Test
    public void policyAdvanceBeforeThrottleReturnMakesOldRequestFailOpen() throws Exception {
        assertVersionAdvanceInvalidatesPendingThrottle(
                CONFIG_REVISION, POLICY_REVISION + 1L, IDENTITY_GENERATION);
    }

    @Test
    public void identityAdvanceBeforeThrottleReturnMakesOldRequestFailOpen() throws Exception {
        assertVersionAdvanceInvalidatesPendingThrottle(
                CONFIG_REVISION, POLICY_REVISION, IDENTITY_GENERATION + 1L);
    }

    @Test
    public void storageNeverExceedsItsFixedCapacity() {
        WakeThrottleState state = new WakeThrottleState(8);
        for (int index = 0; index < 1_000; index++) {
            WakeThrottleState.Outcome outcome = state.evaluate(
                    UID + index,
                    (index & 1) == 0 ? WakeSource.PACKET : WakeSource.ALARM,
                    CONFIG_REVISION,
                    POLICY_REVISION,
                    IDENTITY_GENERATION,
                    COOLDOWN_MS,
                    index);
            assertFalse(outcome.blocksWake());
        }
        assertTrue(state.occupiedSlots() <= 8);
    }

    @Test
    public void malformedInputsFailOpen() {
        WakeThrottleState state = new WakeThrottleState();
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                -1, WakeSource.PACKET, 1L, 1L, 1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, null, 1L, 1L, 1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, -1L, 1L, 1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, 1L, 1L, -1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, 1L, 1L, 1L, 0L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, 1L, 1L, 1L, 1L, -1L));
    }

    @Test
    public void constructorRejectsUnboundedOrAmbiguousSizes() {
        try {
            new WakeThrottleState(0);
            throw new AssertionError("zero capacity accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            new WakeThrottleState(2);
            throw new AssertionError("undersized capacity accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static WakeThrottleState.Outcome evaluate(
            WakeThrottleState state, WakeSource source, long nowElapsedMs) {
        return state.evaluate(
                UID,
                source,
                CONFIG_REVISION,
                POLICY_REVISION,
                IDENTITY_GENERATION,
                COOLDOWN_MS,
                nowElapsedMs);
    }

    private static void assertVersionAdvanceInvalidatesPendingThrottle(
            long nextConfigRevision,
            long nextPolicyRevision,
            long nextIdentityGeneration) throws Exception {
        CountDownLatch throttleCandidateReached = new CountDownLatch(1);
        CountDownLatch resumeOldRequest = new CountDownLatch(1);
        WakeThrottleState state = new WakeThrottleState(
                WakeThrottleState.DEFAULT_CAPACITY,
                () -> {
                    throttleCandidateReached.countDown();
                    awaitOrFail(resumeOldRequest);
                });
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                evaluate(state, WakeSource.PACKET, 10_000L));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<WakeThrottleState.Outcome> oldRequest =
                executor.submit(() -> evaluate(state, WakeSource.PACKET, 10_001L));
        try {
            assertTrue(throttleCandidateReached.await(5L, TimeUnit.SECONDS));
            assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                    UID,
                    WakeSource.PACKET,
                    nextConfigRevision,
                    nextPolicyRevision,
                    nextIdentityGeneration,
                    COOLDOWN_MS,
                    10_002L));

            resumeOldRequest.countDown();
            assertEquals(
                    WakeThrottleState.Outcome.FAIL_OPEN,
                    oldRequest.get(5L, TimeUnit.SECONDS));
            assertEquals(WakeThrottleState.Outcome.THROTTLED, state.evaluate(
                    UID,
                    WakeSource.PACKET,
                    nextConfigRevision,
                    nextPolicyRevision,
                    nextIdentityGeneration,
                    COOLDOWN_MS,
                    10_003L));
        } finally {
            resumeOldRequest.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for controlled interleaving");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
