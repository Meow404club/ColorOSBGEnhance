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
                COOLDOWN_MS, 10_001L));
        assertEquals(WakeThrottleState.Outcome.ALLOWED, state.evaluate(
                UID, WakeSource.PACKET, CONFIG_REVISION + 1L, POLICY_REVISION + 1L,
                COOLDOWN_MS, 10_002L));
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
    public void storageNeverExceedsItsFixedCapacity() {
        WakeThrottleState state = new WakeThrottleState(8);
        for (int index = 0; index < 1_000; index++) {
            WakeThrottleState.Outcome outcome = state.evaluate(
                    UID + index,
                    (index & 1) == 0 ? WakeSource.PACKET : WakeSource.ALARM,
                    CONFIG_REVISION,
                    POLICY_REVISION,
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
                -1, WakeSource.PACKET, 1L, 1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, null, 1L, 1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, -1L, 1L, 1L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, 1L, 1L, 0L, 1L));
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN, state.evaluate(
                UID, WakeSource.PACKET, 1L, 1L, 1L, -1L));
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
            new WakeThrottleState(3);
            throw new AssertionError("non-power-of-two capacity accepted");
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
                COOLDOWN_MS,
                nowElapsedMs);
    }
}
