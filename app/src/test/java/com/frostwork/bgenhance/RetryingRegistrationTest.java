package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RetryingRegistrationTest {
    @Test
    public void failureKeepsOnePendingRetryAndThenRecovers() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingObserver observer = new RecordingObserver();
        AtomicInteger attempts = new AtomicInteger();
        RetryingRegistration registration = new RetryingRegistration(
                scheduler, observer, 2_000L, 60_000L);
        RetryingRegistration.Attempt attempt = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new NullPointerException("activity manager unavailable");
            }
        };

        registration.ensure(attempt);
        registration.ensure(attempt);

        assertEquals(1, attempts.get());
        assertEquals(1, observer.failures);
        assertEquals(0, observer.recoveries);
        assertEquals(List.of(2_000L), scheduler.delaysMs);
        assertEquals(1, scheduler.pendingCount());
        assertTrue(registration.hasPendingRetry());
        assertFalse(registration.isRegistered());

        scheduler.runNext();

        assertEquals(2, attempts.get());
        assertEquals(1, observer.failures);
        assertEquals(1, observer.recoveries);
        assertEquals(0, scheduler.pendingCount());
        assertFalse(registration.hasPendingRetry());
        assertTrue(registration.isRegistered());
    }

    @Test
    public void repeatedFailuresUseBoundedBackoffWithoutRepeatedReports() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingObserver observer = new RecordingObserver();
        AtomicInteger attempts = new AtomicInteger();
        RetryingRegistration registration = new RetryingRegistration(
                scheduler, observer, 2L, 4L);

        registration.ensure(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("not ready");
        });
        scheduler.runNext();
        scheduler.runNext();
        scheduler.runNext();

        assertEquals(4, attempts.get());
        assertEquals(List.of(2L, 4L, 4L, 4L), scheduler.delaysMs);
        assertEquals(1, observer.failures);
        assertEquals(0, observer.recoveries);
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    public void oddMaximumStillUsesTheIntermediateDoubledDelay() {
        ManualScheduler scheduler = new ManualScheduler();
        RetryingRegistration registration = new RetryingRegistration(
                scheduler, new RecordingObserver(), 2L, 5L);
        registration.ensure(() -> {
            throw new IllegalStateException("not ready");
        });
        scheduler.runNext();
        scheduler.runNext();

        assertEquals(List.of(2L, 4L, 5L), scheduler.delaysMs);
    }

    @Test
    public void multiStepInitializationRetriesUntilEveryStepSucceeds() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingObserver observer = new RecordingObserver();
        RetryingRegistration registration = new RetryingRegistration(
                scheduler, observer, 2L, 8L);
        AtomicInteger sourceAttempts = new AtomicInteger();
        AtomicInteger receiverAttempts = new AtomicInteger();

        registration.ensure(() -> {
            if (sourceAttempts.incrementAndGet() == 1) {
                throw new NullPointerException("package manager unavailable");
            }
            if (receiverAttempts.incrementAndGet() == 1) {
                throw new NullPointerException("activity manager unavailable");
            }
        });

        scheduler.runNext();
        assertEquals(2, sourceAttempts.get());
        assertEquals(1, receiverAttempts.get());
        assertTrue(registration.hasPendingRetry());
        assertFalse(registration.isRegistered());

        scheduler.runNext();
        assertEquals(3, sourceAttempts.get());
        assertEquals(2, receiverAttempts.get());
        assertEquals(1, observer.failures);
        assertEquals(1, observer.recoveries);
        assertFalse(registration.hasPendingRetry());
        assertTrue(registration.isRegistered());
    }

    @Test
    public void invalidDelayBoundsAreRejected() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingObserver observer = new RecordingObserver();
        assertIllegalArgument(() -> new RetryingRegistration(scheduler, observer, 0L, 1L));
        assertIllegalArgument(() -> new RetryingRegistration(scheduler, observer, 2L, 1L));
    }

    private static void assertIllegalArgument(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static final class RecordingObserver implements RetryingRegistration.Observer {
        private int failures;
        private int recoveries;

        @Override
        public void onFirstFailure(Throwable error) {
            failures++;
        }

        @Override
        public void onRecovery() {
            recoveries++;
        }
    }

    private static final class ManualScheduler implements RetryingRegistration.Scheduler {
        private final Queue<ManualFuture> pending = new ArrayDeque<>();
        private final List<Long> delaysMs = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delaysMs.add(unit.toMillis(delay));
            ManualFuture future = new ManualFuture(command);
            pending.add(future);
            return future;
        }

        int pendingCount() {
            return pending.size();
        }

        void runNext() {
            ManualFuture future = pending.remove();
            future.run();
        }
    }

    private static final class ManualFuture implements ScheduledFuture<Object> {
        private final Runnable command;
        private boolean done;
        private boolean cancelled;

        ManualFuture(Runnable command) {
            this.command = command;
        }

        void run() {
            if (cancelled) return;
            command.run();
            done = true;
        }

        @Override public long getDelay(TimeUnit unit) { return 0L; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) return false;
            cancelled = true;
            done = true;
            return true;
        }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return done; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }
}
