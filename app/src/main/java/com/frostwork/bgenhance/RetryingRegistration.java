package com.frostwork.bgenhance;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Serializes a fallible registration and retries it with bounded exponential backoff. */
final class RetryingRegistration {
    interface Attempt {
        void register() throws Throwable;
    }

    interface Scheduler {
        ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);
    }

    interface Observer {
        void onFirstFailure(Throwable error);
        void onRecovery();
    }

    private final Scheduler scheduler;
    private final Observer observer;
    private final long initialDelayMs;
    private final long maximumDelayMs;
    private long nextDelayMs;
    private Attempt attempt;
    private ScheduledFuture<?> pendingRetry;
    private boolean registered;
    private boolean failureReported;

    RetryingRegistration(
            Scheduler scheduler,
            Observer observer,
            long initialDelayMs,
            long maximumDelayMs) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.observer = Objects.requireNonNull(observer, "observer");
        if (initialDelayMs <= 0L) throw new IllegalArgumentException("initialDelayMs <= 0");
        if (maximumDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("maximumDelayMs < initialDelayMs");
        }
        this.initialDelayMs = initialDelayMs;
        this.maximumDelayMs = maximumDelayMs;
        nextDelayMs = initialDelayMs;
    }

    synchronized void ensure(Attempt nextAttempt) {
        attempt = Objects.requireNonNull(nextAttempt, "attempt");
        if (registered || pendingRetry != null) return;
        tryRegister();
    }

    synchronized boolean isRegistered() {
        return registered;
    }

    synchronized boolean hasPendingRetry() {
        return pendingRetry != null;
    }

    private void tryRegister() {
        try {
            attempt.register();
            registered = true;
            pendingRetry = null;
            nextDelayMs = initialDelayMs;
            if (failureReported) notifyRecovery();
            failureReported = false;
        } catch (Throwable error) {
            if (!failureReported) notifyFailure(error);
            failureReported = true;
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        long delayMs = nextDelayMs;
        nextDelayMs = delayMs >= maximumDelayMs || delayMs > Long.MAX_VALUE / 2L
                ? maximumDelayMs
                : Math.min(maximumDelayMs, delayMs * 2L);
        try {
            pendingRetry = scheduler.schedule(
                    this::runRetry, delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            pendingRetry = null;
        }
    }

    private void runRetry() {
        synchronized (this) {
            pendingRetry = null;
            if (registered || attempt == null) return;
            tryRegister();
        }
    }

    private void notifyFailure(Throwable error) {
        try {
            observer.onFirstFailure(error);
        } catch (Throwable ignored) {
            // Registration state must not depend on logging availability.
        }
    }

    private void notifyRecovery() {
        try {
            observer.onRecovery();
        } catch (Throwable ignored) {
            // Registration state must not depend on logging availability.
        }
    }
}
