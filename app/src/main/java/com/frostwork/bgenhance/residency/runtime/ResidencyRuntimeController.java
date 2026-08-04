package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import java.util.Collections;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates config and package identity snapshots without doing platform work on hook threads. */
public final class ResidencyRuntimeController {
    static final long INITIAL_IDENTITY_RETRY_DELAY_MS = 2_000L;
    static final long MAX_IDENTITY_RETRY_DELAY_MS = 60_000L;
    private static final StaleIdentityBuildException STALE_IDENTITY_BUILD =
            new StaleIdentityBuildException();
    private static final RetryScheduler NO_RETRY_SCHEDULER =
            (command, delayMs) -> null;
    private static final Executor DEFAULT_LISTENER_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceRuntimeObserver");
        thread.setDaemon(true);
        return thread;
    });
    private final Executor identityExecutor;
    private final Executor listenerExecutor;
    private final RetryScheduler retryScheduler;
    private final CopyOnWriteArrayList<ListenerRegistration> stateListeners =
            new CopyOnWriteArrayList<>();
    private final Object listenerLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<ResidencyRuntimeState> state =
            new AtomicReference<>(new ResidencyRuntimeState(
                    RuntimeConfigSnapshot.unavailable(0L),
                    ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    0L,
                    "not_initialized"));
    private PackageUidSource packageUidSource;
    private RetryHandle pendingIdentityRetry;
    private long nextIdentityRetryDelayMs = INITIAL_IDENTITY_RETRY_DELAY_MS;

    public ResidencyRuntimeController(Executor identityExecutor) {
        this(identityExecutor, DEFAULT_LISTENER_EXECUTOR, NO_RETRY_SCHEDULER);
    }

    public ResidencyRuntimeController(
            Executor identityExecutor, RetryScheduler retryScheduler) {
        this(identityExecutor, DEFAULT_LISTENER_EXECUTOR, retryScheduler);
    }

    ResidencyRuntimeController(Executor identityExecutor, Executor listenerExecutor) {
        this(identityExecutor, listenerExecutor, NO_RETRY_SCHEDULER);
    }

    ResidencyRuntimeController(
            Executor identityExecutor,
            Executor listenerExecutor,
            RetryScheduler retryScheduler) {
        this.identityExecutor = Objects.requireNonNull(identityExecutor, "identityExecutor");
        this.listenerExecutor = Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
    }

    public void addStateListener(StateListener listener) {
        StateListener value = Objects.requireNonNull(listener, "listener");
        ListenerRegistration registration;
        synchronized (listenerLock) {
            for (ListenerRegistration existing : stateListeners) {
                if (existing.listener == value) return;
            }
            registration = new ListenerRegistration(value);
            stateListeners.add(registration);
        }
        dispatch(registration, state.get());
    }

    public void removeStateListener(StateListener listener) {
        if (listener == null) return;
        synchronized (listenerLock) {
            for (ListenerRegistration registration : stateListeners) {
                if (registration.listener != listener) continue;
                registration.deactivate();
                stateListeners.remove(registration);
                return;
            }
        }
    }

    public synchronized void updateConfig(RuntimeConfigSnapshot snapshot) {
        RuntimeConfigSnapshot next = Objects.requireNonNull(snapshot, "snapshot");
        cancelIdentityRetry(true);
        long token = generation.incrementAndGet();
        ResidencyRuntimeState current = state.get();
        if (!next.available()) {
            publish(next, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    token, "config_unavailable");
            return;
        }

        ResidencyIdentityIndex currentIndex = current.identityIndex();
        if (!currentIndex.isInvalidated()
                && currentIndex.policyRevision() == next.policyRevision()
                && current.snapshot().policiesByPackage().equals(next.policiesByPackage())) {
            publish(next, currentIndex, ResidencyRuntimeState.Status.READY, token, "");
            return;
        }
        rebuild(next, token);
    }

    public synchronized void setPackageUidSource(PackageUidSource source) {
        cancelIdentityRetry(true);
        packageUidSource = Objects.requireNonNull(source, "source");
        RuntimeConfigSnapshot snapshot = state.get().snapshot();
        long token = generation.incrementAndGet();
        if (!snapshot.available()) {
            publish(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    token, "config_unavailable");
            return;
        }
        rebuild(snapshot, token);
    }

    public synchronized void invalidateIdentities() {
        cancelIdentityRetry(true);
        RuntimeConfigSnapshot snapshot = state.get().snapshot();
        long token = generation.incrementAndGet();
        if (!snapshot.available()) {
            publish(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    token, "config_unavailable");
            return;
        }
        rebuild(snapshot, token);
    }

    private void rebuild(RuntimeConfigSnapshot snapshot, long token) {
        if (snapshot.policiesByPackage().isEmpty()) {
            ResidencyIdentityIndex empty = ResidencyIdentityIndex.build(
                    snapshot, Collections.emptyMap());
            cancelIdentityRetry(true);
            publish(snapshot, empty, ResidencyRuntimeState.Status.READY, token, "");
            return;
        }
        PackageUidSource source = packageUidSource;
        if (source == null) {
            publish(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.IDENTITY_SOURCE_UNAVAILABLE,
                    token, "package_uid_source_unavailable");
            return;
        }
        publish(snapshot, ResidencyIdentityIndex.invalidated(),
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                token, "");
        try {
            identityExecutor.execute(() -> buildIdentities(snapshot, source, token));
        } catch (Throwable error) {
            failBuildIfCurrent(snapshot, source, token, error);
        }
    }

    private void buildIdentities(
            RuntimeConfigSnapshot snapshot, PackageUidSource source, long token) {
        if (generation.get() != token) return;
        try {
            PackageUidSource guardedSource = new PackageUidSource() {
                @Override
                public boolean hasCompleteUidEnumeration() {
                    throwIfStale(token);
                    return source.hasCompleteUidEnumeration();
                }

                @Override
                public OptionalInt findUid(String packageName) {
                    throwIfStale(token);
                    return source.findUid(packageName);
                }

                @Override
                public Set<String> findPackagesForUid(int uid) {
                    throwIfStale(token);
                    return source.findPackagesForUid(uid);
                }
            };
            ResidencyIdentityIndex index = new ResidencyIdentityIndexBuilder(
                    guardedSource).build(snapshot);
            publishReadyIfCurrent(snapshot, index, token);
        } catch (StaleIdentityBuildException ignored) {
            // A newer generation already published fail-open state and owns the next rebuild.
        } catch (Throwable error) {
            failBuildIfCurrent(snapshot, source, token, error);
        }
    }

    private void throwIfStale(long token) {
        if (generation.get() != token) throw STALE_IDENTITY_BUILD;
    }

    private synchronized void publishReadyIfCurrent(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            long token) {
        if (generation.get() != token) return;
        cancelIdentityRetry(true);
        publish(snapshot, index, ResidencyRuntimeState.Status.READY, token, "");
    }

    private synchronized void failBuildIfCurrent(
            RuntimeConfigSnapshot snapshot,
            PackageUidSource source,
            long token,
            Throwable error) {
        if (generation.get() != token || packageUidSource != source) return;
        publish(snapshot, ResidencyIdentityIndex.invalidated(),
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_FAILED,
                token, errorText(error));
        scheduleIdentityRetry(snapshot, source, token);
    }

    private void scheduleIdentityRetry(
            RuntimeConfigSnapshot snapshot,
            PackageUidSource source,
            long failedToken) {
        if (pendingIdentityRetry != null) return;
        long delayMs = nextIdentityRetryDelayMs;
        nextIdentityRetryDelayMs = delayMs >= MAX_IDENTITY_RETRY_DELAY_MS
                || delayMs > Long.MAX_VALUE / 2L
                ? MAX_IDENTITY_RETRY_DELAY_MS
                : Math.min(MAX_IDENTITY_RETRY_DELAY_MS, delayMs * 2L);
        try {
            pendingIdentityRetry = retryScheduler.schedule(
                    () -> retryIdentityBuild(snapshot, source, failedToken), delayMs);
        } catch (Throwable ignored) {
            pendingIdentityRetry = null;
        }
    }

    private synchronized void retryIdentityBuild(
            RuntimeConfigSnapshot snapshot,
            PackageUidSource source,
            long failedToken) {
        pendingIdentityRetry = null;
        ResidencyRuntimeState current = state.get();
        if (generation.get() != failedToken
                || packageUidSource != source
                || current.snapshot() != snapshot
                || current.status() != ResidencyRuntimeState.Status.IDENTITY_REBUILD_FAILED) {
            return;
        }
        long token = generation.incrementAndGet();
        rebuild(snapshot, token);
    }

    private void cancelIdentityRetry(boolean resetBackoff) {
        RetryHandle pending = pendingIdentityRetry;
        pendingIdentityRetry = null;
        if (pending != null) {
            try {
                pending.cancel();
            } catch (Throwable ignored) {
                // Cancellation is advisory; generation checks still reject stale retries.
            }
        }
        if (resetBackoff) nextIdentityRetryDelayMs = INITIAL_IDENTITY_RETRY_DELAY_MS;
    }

    private void publish(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            ResidencyRuntimeState.Status status,
            long token,
            String error) {
        ResidencyRuntimeState next =
                new ResidencyRuntimeState(snapshot, index, status, token, error);
        state.set(next);
        for (ListenerRegistration registration : stateListeners) {
            dispatch(registration, next);
        }
    }

    public ResidencyRuntimeState state() { return state.get(); }

    public KillDecision decideKill(KillContext context) {
        return state.get().decisionEngine().decideKill(context);
    }

    public FreezeDecision decideFreeze(FreezeContext context) {
        return state.get().decisionEngine().decideFreeze(context);
    }

    public WakeDecision decideWake(WakeContext context) {
        return state.get().decisionEngine().decideWake(context);
    }

    public ResourceDecision decideResource(ResourceContext context) {
        return state.get().decisionEngine().decideResource(context);
    }

    public RefreezeDecision decideRefreeze(RefreezeContext context) {
        return state.get().decisionEngine().decideRefreeze(context);
    }

    private void dispatch(
            ListenerRegistration registration, ResidencyRuntimeState next) {
        try {
            listenerExecutor.execute(() -> registration.deliver(next));
        } catch (Throwable ignored) {
            // Runtime publication remains valid even when observer dispatch is unavailable.
        }
    }

    public interface StateListener {
        void onStateChanged(ResidencyRuntimeState state);
    }

    public interface RetryScheduler {
        RetryHandle schedule(Runnable command, long delayMs);
    }

    public interface RetryHandle {
        void cancel();
    }

    private static final class ListenerRegistration {
        private final StateListener listener;
        private long lastDeliveredGeneration = -1L;
        private ResidencyRuntimeState.Status lastDeliveredStatus;
        private boolean active = true;

        private ListenerRegistration(StateListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        private void deliver(ResidencyRuntimeState next) {
            synchronized (this) {
                if (!active || next.generation() < lastDeliveredGeneration) return;
                if (next.generation() == lastDeliveredGeneration
                        && !advancesSameGeneration(next.status())) {
                    return;
                }
                lastDeliveredGeneration = next.generation();
                lastDeliveredStatus = next.status();
            }
            try {
                listener.onStateChanged(next);
            } catch (Throwable ignored) {
                // Observers cannot interfere with atomic runtime publication.
            }
        }

        private synchronized void deactivate() {
            active = false;
        }

        private boolean advancesSameGeneration(ResidencyRuntimeState.Status nextStatus) {
            return lastDeliveredStatus
                    == ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING
                    && nextStatus
                    != ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING;
        }
    }

    private static String errorText(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + message);
    }

    private static final class StaleIdentityBuildException extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() { return this; }
    }
}
