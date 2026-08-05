package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed-capacity weak identity state for one Frozen-to-Middle refreeze cycle. */
public final class HansRefreezeRegistry {
    private static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final ReferenceQueue<Object> collectedKeys = new ReferenceQueue<>();
    private final Map<IdentityWeakReference, Entry> entries = new HashMap<>();
    private final Object lock = new Object();
    private long nextGeneration = 1L;

    public HansRefreezeRegistry() {
        this(DEFAULT_CAPACITY);
    }

    public HansRefreezeRegistry(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public ArmResult arm(
            Object stateMachine, ResidencyTarget target, WakeSource source) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            if (entries.containsKey(lookup)) {
                entries.put(lookup, new Entry(
                        target, source, Stage.ARMED, false,
                        nextGeneration(), -1L, 0, null));
                return ArmResult.REPLACED;
            }
            if (entries.size() >= capacity) return ArmResult.FULL;
            entries.put(
                    new IdentityWeakReference(stateMachine, collectedKeys),
                    new Entry(
                            target, source, Stage.ARMED, false,
                            nextGeneration(), -1L, 0, null));
            return ArmResult.ADDED;
        }
    }

    public boolean activate(Object stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null || entry.stage != Stage.ARMED) {
                entries.remove(lookup);
                return false;
            }
            entries.put(lookup, entry.withStage(Stage.ACTIVE));
            return true;
        }
    }

    public boolean bindState(Object stateMachine, Object state) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        Objects.requireNonNull(state, "state");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null) return false;
            entries.put(lookup, entry.withBoundState(state));
            return true;
        }
    }

    public WakeSource activeSourceOrNull(Object stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            Entry entry = entries.get(IdentityWeakReference.forLookup(stateMachine));
            return entry != null && entry.stage != Stage.ARMED ? entry.source : null;
        }
    }

    public ActiveCycle activeCycleOrNull(Object stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            Entry entry = entries.get(IdentityWeakReference.forLookup(stateMachine));
            if (entry == null || entry.stage == Stage.ARMED) return null;
            return new ActiveCycle(
                    entry.target,
                    entry.source,
                    entry.generation,
                    entry.fastFreezeOwned);
        }
    }

    public ActiveCycle rebindSource(
            Object stateMachine,
            long expectedGeneration,
            ResidencyTarget target,
            WakeSource source) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != expectedGeneration
                    || entry.stage == Stage.ARMED
                    || !sameTarget(entry.target, target)) {
                return null;
            }
            Entry rebound = new Entry(
                    target,
                    source,
                    Stage.ACTIVE,
                    entry.fastFreezeOwned,
                    nextGeneration(),
                    -1L,
                    0,
                    entry.boundState);
            entries.put(lookup, rebound);
            return activeCycle(rebound);
        }
    }

    public boolean markBlocked(
            Object stateMachine, long expectedGeneration, long runtimeGeneration) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != expectedGeneration
                    || entry.stage == Stage.ARMED) {
                return false;
            }
            entries.put(lookup, entry.blocked(runtimeGeneration));
            return true;
        }
    }

    public boolean releaseBlocked(
            Object stateMachine, long expectedGeneration, long runtimeGeneration) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != expectedGeneration
                    || (entry.stage != Stage.BLOCKED
                    && entry.stage != Stage.RECOVERY_QUEUED
                    && entry.stage != Stage.RETRY_SCHEDULED)
                    || entry.blockRuntimeGeneration != runtimeGeneration) {
                return false;
            }
            entries.put(lookup, entry.unblocked());
            return true;
        }
    }

    public boolean markFastFreezeOwned(Object stateMachine) {
        return updateFastFreezeOwnership(stateMachine, true);
    }

    public boolean clearFastFreezeOwned(Object stateMachine) {
        return updateFastFreezeOwnership(stateMachine, false);
    }

    public boolean clearFastFreezeOwnedForState(Object state) {
        if (state == null) return false;
        synchronized (lock) {
            drainCollectedKeys();
            for (Map.Entry<IdentityWeakReference, Entry> mapEntry
                    : new ArrayList<>(entries.entrySet())) {
                Entry entry = mapEntry.getValue();
                if (entry.boundState == null || entry.boundState.get() != state) continue;
                if (!entry.fastFreezeOwned) return true;
                entries.put(mapEntry.getKey(), entry.withFastFreezeOwned(false));
                return true;
            }
            return false;
        }
    }

    public boolean ownsFastFreeze(Object stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            Entry entry = entries.get(IdentityWeakReference.forLookup(stateMachine));
            return entry != null && entry.stage != Stage.ARMED && entry.fastFreezeOwned;
        }
    }

    public List<BlockedCycle> blockedCycles() {
        synchronized (lock) {
            drainCollectedKeys();
            if (entries.isEmpty()) return Collections.emptyList();
            List<BlockedCycle> blocked = new ArrayList<>();
            for (Map.Entry<IdentityWeakReference, Entry> mapEntry : entries.entrySet()) {
                Entry entry = mapEntry.getValue();
                if (entry.stage != Stage.BLOCKED) continue;
                Object stateMachine = mapEntry.getKey().get();
                if (stateMachine == null) continue;
                blocked.add(new BlockedCycle(
                        stateMachine,
                        entry.target,
                        entry.source,
                        entry.generation,
                        entry.retryAttempt));
            }
            return blocked.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(blocked);
        }
    }

    public boolean prepareRecovery(
            Object stateMachine, long generation, long runtimeGeneration) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != generation
                    || entry.stage != Stage.BLOCKED) {
                return false;
            }
            entries.put(lookup, entry.recoveryQueued(runtimeGeneration));
            return true;
        }
    }

    public boolean isRecoveryQueued(
            Object stateMachine, long generation, long runtimeGeneration) {
        if (stateMachine == null) return false;
        synchronized (lock) {
            drainCollectedKeys();
            Entry entry = entries.get(IdentityWeakReference.forLookup(stateMachine));
            return entry != null
                    && entry.generation == generation
                    && entry.stage == Stage.RECOVERY_QUEUED
                    && entry.blockRuntimeGeneration == runtimeGeneration;
        }
    }

    public boolean claimRecovery(
            Object stateMachine, long generation, long runtimeGeneration) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != generation
                    || entry.stage != Stage.RECOVERY_QUEUED
                    || entry.blockRuntimeGeneration != runtimeGeneration) {
                return false;
            }
            entries.put(lookup, entry.unblocked());
            return true;
        }
    }

    public int recoveryFailed(
            Object stateMachine, long generation, long runtimeGeneration) {
        if (stateMachine == null) return -1;
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry != null
                    && entry.generation == generation
                    && entry.stage == Stage.RECOVERY_QUEUED
                    && entry.blockRuntimeGeneration == runtimeGeneration) {
                int nextAttempt = entry.retryAttempt + 1;
                entries.put(lookup, entry.retryBlocked(nextAttempt));
                return nextAttempt;
            }
            return -1;
        }
    }

    public boolean prepareRetry(Object stateMachine, long generation, int attempt) {
        if (stateMachine == null || attempt <= 0) return false;
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != generation
                    || entry.stage != Stage.BLOCKED
                    || entry.retryAttempt != attempt) {
                return false;
            }
            entries.put(lookup, entry.withStage(Stage.RETRY_SCHEDULED));
            return true;
        }
    }

    public boolean retryReady(Object stateMachine, long generation, int attempt) {
        return finishRetrySchedule(stateMachine, generation, attempt);
    }

    public boolean retryScheduleFailed(Object stateMachine, long generation, int attempt) {
        return finishRetrySchedule(stateMachine, generation, attempt);
    }

    private boolean finishRetrySchedule(
            Object stateMachine, long generation, int attempt) {
        if (stateMachine == null || attempt <= 0) return false;
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null
                    || entry.generation != generation
                    || entry.stage != Stage.RETRY_SCHEDULED
                    || entry.retryAttempt != attempt) {
                return false;
            }
            entries.put(lookup, entry.withStage(Stage.BLOCKED));
            return true;
        }
    }

    public void remove(Object stateMachine) {
        if (stateMachine == null) return;
        synchronized (lock) {
            drainCollectedKeys();
            entries.remove(IdentityWeakReference.forLookup(stateMachine));
        }
    }

    int sizeForTest() {
        synchronized (lock) {
            drainCollectedKeys();
            return entries.size();
        }
    }

    boolean enqueueKeyForTest(Object stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            for (IdentityWeakReference reference : entries.keySet()) {
                if (reference.get() != stateMachine) continue;
                reference.clear();
                boolean enqueued = reference.enqueue();
                drainCollectedKeys();
                return enqueued;
            }
            return false;
        }
    }

    private void drainCollectedKeys() {
        IdentityWeakReference collected;
        while ((collected = (IdentityWeakReference) collectedKeys.poll()) != null) {
            entries.remove(collected);
        }
    }

    private boolean updateFastFreezeOwnership(Object stateMachine, boolean owned) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(stateMachine);
            Entry entry = entries.get(lookup);
            if (entry == null || entry.stage == Stage.ARMED) return false;
            entries.put(lookup, entry.withFastFreezeOwned(owned));
            return true;
        }
    }

    private long nextGeneration() {
        long value = nextGeneration++;
        if (nextGeneration <= 0L) nextGeneration = 1L;
        return value;
    }

    private static boolean sameTarget(ResidencyTarget first, ResidencyTarget second) {
        return first.uid() == second.uid()
                && first.packageName().equals(second.packageName());
    }

    private static ActiveCycle activeCycle(Entry entry) {
        return new ActiveCycle(
                entry.target,
                entry.source,
                entry.generation,
                entry.fastFreezeOwned);
    }

    public enum ArmResult {
        ADDED,
        REPLACED,
        FULL;

        public boolean armed() { return this != FULL; }
    }

    private enum Stage {
        ARMED,
        ACTIVE,
        BLOCKED,
        RECOVERY_QUEUED,
        RETRY_SCHEDULED
    }

    private static final class Entry {
        private final ResidencyTarget target;
        private final WakeSource source;
        private final Stage stage;
        private final boolean fastFreezeOwned;
        private final long generation;
        private final long blockRuntimeGeneration;
        private final int retryAttempt;
        private final WeakReference<Object> boundState;

        private Entry(
                ResidencyTarget target,
                WakeSource source,
                Stage stage,
                boolean fastFreezeOwned,
                long generation,
                long blockRuntimeGeneration,
                int retryAttempt,
                WeakReference<Object> boundState) {
            this.target = Objects.requireNonNull(target, "target");
            this.source = Objects.requireNonNull(source, "source");
            this.stage = Objects.requireNonNull(stage, "stage");
            this.fastFreezeOwned = fastFreezeOwned;
            this.generation = generation;
            this.blockRuntimeGeneration = blockRuntimeGeneration;
            this.retryAttempt = retryAttempt;
            this.boundState = boundState;
        }

        private Entry withStage(Stage nextStage) {
            return new Entry(
                    target, source, nextStage, fastFreezeOwned,
                    generation, blockRuntimeGeneration, retryAttempt, boundState);
        }

        private Entry withFastFreezeOwned(boolean owned) {
            return new Entry(
                    target, source, stage, owned,
                    generation, blockRuntimeGeneration, retryAttempt, boundState);
        }

        private Entry withBoundState(Object state) {
            return new Entry(
                    target, source, stage, fastFreezeOwned,
                    generation, blockRuntimeGeneration, retryAttempt,
                    new WeakReference<>(Objects.requireNonNull(state, "state")));
        }

        private Entry blocked(long runtimeGeneration) {
            return new Entry(
                    target, source, Stage.BLOCKED, fastFreezeOwned,
                    generation, runtimeGeneration, 0, boundState);
        }

        private Entry unblocked() {
            return new Entry(
                    target, source, Stage.ACTIVE, fastFreezeOwned,
                    generation, -1L, 0, boundState);
        }

        private Entry recoveryQueued(long runtimeGeneration) {
            return new Entry(
                    target, source, Stage.RECOVERY_QUEUED, fastFreezeOwned,
                    generation, runtimeGeneration, retryAttempt, boundState);
        }

        private Entry retryBlocked(int attempt) {
            return new Entry(
                    target, source, Stage.BLOCKED, fastFreezeOwned,
                    generation, blockRuntimeGeneration, attempt, boundState);
        }
    }

    public static final class ActiveCycle {
        private final ResidencyTarget target;
        private final WakeSource source;
        private final long generation;
        private final boolean fastFreezeOwned;

        private ActiveCycle(
                ResidencyTarget target,
                WakeSource source,
                long generation,
                boolean fastFreezeOwned) {
            this.target = Objects.requireNonNull(target, "target");
            this.source = Objects.requireNonNull(source, "source");
            this.generation = generation;
            this.fastFreezeOwned = fastFreezeOwned;
        }

        public ResidencyTarget target() { return target; }
        public WakeSource source() { return source; }
        public long generation() { return generation; }
        public boolean fastFreezeOwned() { return fastFreezeOwned; }
    }

    public static final class BlockedCycle {
        private final Object stateMachine;
        private final ResidencyTarget target;
        private final WakeSource source;
        private final long generation;
        private final int retryAttempt;

        private BlockedCycle(
                Object stateMachine,
                ResidencyTarget target,
                WakeSource source,
                long generation,
                int retryAttempt) {
            this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
            this.target = Objects.requireNonNull(target, "target");
            this.source = Objects.requireNonNull(source, "source");
            this.generation = generation;
            this.retryAttempt = retryAttempt;
        }

        public Object stateMachine() { return stateMachine; }
        public ResidencyTarget target() { return target; }
        public WakeSource source() { return source; }
        public long generation() { return generation; }
        public int retryAttempt() { return retryAttempt; }
    }

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        private IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        private static IdentityWeakReference forLookup(Object referent) {
            return new IdentityWeakReference(referent, null);
        }

        @Override public int hashCode() { return identityHash; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object referent = get();
            return referent != null && referent == ((IdentityWeakReference) other).get();
        }
    }
}
