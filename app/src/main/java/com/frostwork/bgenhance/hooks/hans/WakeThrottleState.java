package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Fixed-capacity elapsed-time gate for Packet and Alarm wake throttling. */
public final class WakeThrottleState {
    static final int DEFAULT_CAPACITY = 2_048;
    private static final int BUCKET_SIZE = 4;
    private static final int MAX_CAS_ATTEMPTS = 32;

    private final AtomicReferenceArray<Bucket> buckets;
    private final AtomicReference<Version> latestVersion =
            new AtomicReference<>(Version.initial());
    private final int bucketMask;

    public WakeThrottleState() {
        this(DEFAULT_CAPACITY);
    }

    WakeThrottleState(int capacity) {
        if (capacity < BUCKET_SIZE || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least " + BUCKET_SIZE);
        }
        int bucketCount = capacity / BUCKET_SIZE;
        buckets = new AtomicReferenceArray<>(bucketCount);
        bucketMask = bucketCount - 1;
    }

    public Outcome evaluate(
            int uid,
            WakeSource source,
            long configRevision,
            long policyRevision,
            long identityGeneration,
            long cooldownMs,
            long nowElapsedMs) {
        if (uid < 0
                || source == null
                || configRevision < 0L
                || policyRevision < 0L
                || identityGeneration < 0L
                || cooldownMs <= 0L
                || nowElapsedMs < 0L) {
            return Outcome.FAIL_OPEN;
        }

        Version requestedVersion = new Version(
                configRevision, policyRevision, identityGeneration);
        if (!admitVersion(requestedVersion)) return Outcome.FAIL_OPEN;

        int bucketIndex = bucketIndex(uid, source);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            if (!requestedVersion.equals(latestVersion.get())) {
                return Outcome.FAIL_OPEN;
            }
            Bucket observed = buckets.get(bucketIndex);
            Bucket current = observed;
            if (current == null) current = Bucket.empty();
            int entryIndex = current.indexOf(uid, source);
            Bucket updated;
            if (entryIndex >= 0) {
                Entry existing = current.entryAt(entryIndex);
                int versionOrder = requestedVersion.compareTo(existing.version);
                if (versionOrder < 0) return Outcome.FAIL_OPEN;
                if (versionOrder == 0
                        && nowElapsedMs >= existing.lastAllowedElapsedMs
                        && nowElapsedMs - existing.lastAllowedElapsedMs < cooldownMs) {
                    return Outcome.THROTTLED;
                }
                updated = current.withEntry(entryIndex, new Entry(
                        uid, source, requestedVersion, nowElapsedMs));
            } else {
                updated = current.withInserted(new Entry(
                        uid, source, requestedVersion, nowElapsedMs));
            }
            if (!requestedVersion.equals(latestVersion.get())) {
                return Outcome.FAIL_OPEN;
            }
            if (buckets.compareAndSet(bucketIndex, observed, updated)) {
                return Outcome.ALLOWED;
            }
        }
        return Outcome.FAIL_OPEN;
    }

    int occupiedSlots() {
        int occupied = 0;
        for (int index = 0; index < buckets.length(); index++) {
            Bucket bucket = buckets.get(index);
            if (bucket != null) occupied += bucket.size();
        }
        return occupied;
    }

    private boolean admitVersion(Version requested) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Version current = latestVersion.get();
            int order = requested.compareTo(current);
            if (order < 0) return false;
            if (order == 0) return true;
            if (latestVersion.compareAndSet(current, requested)) return true;
        }
        return false;
    }

    private int bucketIndex(int uid, WakeSource source) {
        return mixedHash(uid, source) & bucketMask;
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

    private static final class Bucket {
        private static final Bucket EMPTY = new Bucket(new Entry[0]);
        private final Entry[] entries;

        private Bucket(Entry[] entries) {
            this.entries = entries;
        }

        private static Bucket empty() { return EMPTY; }
        private int size() { return entries.length; }
        private Entry entryAt(int index) { return entries[index]; }

        private int indexOf(int uid, WakeSource source) {
            for (int index = 0; index < entries.length; index++) {
                if (entries[index].matches(uid, source)) return index;
            }
            return -1;
        }

        private Bucket withEntry(int index, Entry entry) {
            Entry[] copy = entries.clone();
            copy[index] = entry;
            return new Bucket(copy);
        }

        private Bucket withInserted(Entry entry) {
            if (entries.length < BUCKET_SIZE) {
                Entry[] copy = new Entry[entries.length + 1];
                System.arraycopy(entries, 0, copy, 0, entries.length);
                copy[entries.length] = entry;
                return new Bucket(copy);
            }
            int victim = secondaryHash(entry.uid, entry.source) & (BUCKET_SIZE - 1);
            return withEntry(victim, entry);
        }

        private static int secondaryHash(int uid, WakeSource source) {
            int hash = Integer.rotateLeft(uid, 13) ^ source.ordinal() * 0x27D4EB2D;
            return hash ^ (hash >>> 15);
        }
    }

    private static final class Entry {
        private final int uid;
        private final WakeSource source;
        private final Version version;
        private final long lastAllowedElapsedMs;

        private Entry(
                int uid,
                WakeSource source,
                Version version,
                long lastAllowedElapsedMs) {
            this.uid = uid;
            this.source = Objects.requireNonNull(source, "source");
            this.version = Objects.requireNonNull(version, "version");
            this.lastAllowedElapsedMs = lastAllowedElapsedMs;
        }

        private boolean matches(int candidateUid, WakeSource candidateSource) {
            return uid == candidateUid && source == candidateSource;
        }
    }

    private static final class Version implements Comparable<Version> {
        private final long configRevision;
        private final long policyRevision;
        private final long identityGeneration;

        private Version(
                long configRevision, long policyRevision, long identityGeneration) {
            this.configRevision = configRevision;
            this.policyRevision = policyRevision;
            this.identityGeneration = identityGeneration;
        }

        private static Version initial() {
            return new Version(-1L, -1L, -1L);
        }

        @Override
        public int compareTo(Version other) {
            int generationOrder = Long.compare(identityGeneration, other.identityGeneration);
            if (generationOrder != 0) return generationOrder;
            int configOrder = Long.compare(configRevision, other.configRevision);
            if (configOrder != 0) return configOrder;
            return Long.compare(policyRevision, other.policyRevision);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Version)) return false;
            Version that = (Version) other;
            return configRevision == that.configRevision
                    && policyRevision == that.policyRevision
                    && identityGeneration == that.identityGeneration;
        }

        @Override
        public int hashCode() {
            return Objects.hash(configRevision, policyRevision, identityGeneration);
        }
    }
}
