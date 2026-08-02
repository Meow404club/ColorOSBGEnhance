package com.frostwork.bgenhance.hooks.alarm;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-capacity weak identity registry for deferred Alarm state.
 *
 * <p>Values must not strongly reference their keys, directly or indirectly. Preserving that
 * invariant is the caller's responsibility; otherwise a value can keep its weak key alive and
 * prevent automatic capacity recovery.
 */
public final class AlarmDeferredRegistry<V> {
    private final int capacity;
    private final ReferenceQueue<Object> collectedKeys = new ReferenceQueue<>();
    private final Map<IdentityWeakReference, V> entries = new HashMap<>();
    private final Object lock = new Object();

    public AlarmDeferredRegistry(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public PutResult putIfAbsent(Object key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (lock) {
            drainCollectedKeys();
            IdentityWeakReference lookup = IdentityWeakReference.forLookup(key);
            if (entries.containsKey(lookup)) return PutResult.PRESENT;
            if (entries.size() >= capacity) return PutResult.FULL;
            entries.put(new IdentityWeakReference(key, collectedKeys), value);
            return PutResult.ADDED;
        }
    }

    public V remove(Object key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            drainCollectedKeys();
            return entries.remove(IdentityWeakReference.forLookup(key));
        }
    }

    int sizeForTest() {
        synchronized (lock) {
            drainCollectedKeys();
            return entries.size();
        }
    }

    boolean enqueueKeyForTest(Object key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            drainCollectedKeys();
            for (IdentityWeakReference reference : entries.keySet()) {
                if (reference.get() != key) continue;
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

    public enum PutResult {
        ADDED,
        PRESENT,
        FULL
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

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object referent = get();
            return referent != null && referent == ((IdentityWeakReference) other).get();
        }
    }
}
