package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Fixed-capacity elapsed-time gate for Packet and Alarm wake throttling. */
public final class WakeThrottleState {
    static final int DEFAULT_CAPACITY = 2_048;
    private static final int MAX_PROBES = 4;
    private static final int MAX_CAS_ATTEMPTS = 32;

    private final AtomicReferenceArray<Entry> entries;
    private final int mask;
    private final int probeCount;

    public WakeThrottleState() {
        this(DEFAULT_CAPACITY);
    }

    WakeThrottleState(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a positive power of two");
        }
        entries = new AtomicReferenceArray<>(capacity);
        mask = capacity - 1;
        probeCount = Math.min(capacity, MAX_PROBES);
    }

    public Outcome evaluate(
            int uid,
            WakeSource source,
            long configRevision,
            long policyRevision,
            long cooldownMs,
            long nowElapsedMs) {
        if (uid < 0
                || source == null
                || configRevision < 0L
                || policyRevision < 0L
                || cooldownMs <= 0L
                || nowElapsedMs < 0L) {
            return Outcome.FAIL_OPEN;
        }

        int start = mixedHash(uid, source) & mask;
        attempts:
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            int replacementIndex = -1;
            Entry replacementValue = null;
            for (int probe = 0; probe < probeCount; probe++) {
                int index = (start + probe) & mask;
                Entry current = entries.get(index);
                if (current == null) {
                    if (replacementIndex < 0) replacementIndex = index;
                    continue;
                }
                if (!current.matches(uid, source)) continue;
                if (current.configRevision != configRevision
                        || current.policyRevision != policyRevision) {
                    replacementIndex = index;
                    replacementValue = current;
                    break;
                }
                if (nowElapsedMs >= current.lastAllowedElapsedMs
                        && nowElapsedMs - current.lastAllowedElapsedMs < cooldownMs) {
                    return Outcome.THROTTLED;
                }
                Entry updated = new Entry(
                        uid, source, configRevision, policyRevision, nowElapsedMs);
                if (entries.compareAndSet(index, current, updated)) {
                    return Outcome.ALLOWED;
                }
                continue attempts;
            }

            if (replacementIndex < 0) {
                replacementIndex = start;
                replacementValue = entries.get(start);
            } else if (replacementValue == null) {
                replacementValue = entries.get(replacementIndex);
                if (replacementValue != null) continue;
            }
            Entry inserted = new Entry(
                    uid, source, configRevision, policyRevision, nowElapsedMs);
            if (entries.compareAndSet(replacementIndex, replacementValue, inserted)) {
                return Outcome.ALLOWED;
            }
        }
        return Outcome.FAIL_OPEN;
    }

    int occupiedSlots() {
        int occupied = 0;
        for (int index = 0; index < entries.length(); index++) {
            if (entries.get(index) != null) occupied++;
        }
        return occupied;
    }

    private static int mixedHash(int uid, WakeSource source) {
        int hash = uid * 0x9E3779B9 + source.ordinal() * 0x7F4A7C15;
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        return hash ^ (hash >>> 13);
    }

    public enum Outcome {
        ALLOWED,
        THROTTLED,
        FAIL_OPEN;

        public boolean blocksWake() { return this == THROTTLED; }
    }

    private static final class Entry {
        private final int uid;
        private final WakeSource source;
        private final long configRevision;
        private final long policyRevision;
        private final long lastAllowedElapsedMs;

        private Entry(
                int uid,
                WakeSource source,
                long configRevision,
                long policyRevision,
                long lastAllowedElapsedMs) {
            this.uid = uid;
            this.source = Objects.requireNonNull(source, "source");
            this.configRevision = configRevision;
            this.policyRevision = policyRevision;
            this.lastAllowedElapsedMs = lastAllowedElapsedMs;
        }

        private boolean matches(int candidateUid, WakeSource candidateSource) {
            return uid == candidateUid && source == candidateSource;
        }
    }
}
