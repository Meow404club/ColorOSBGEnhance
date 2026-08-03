package com.frostwork.bgenhance.diagnostics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Fixed-capacity, non-blocking TTL gate for normalized user-visible events. */
public final class DiagnosticsEventDeduplicator {
    public static final int DEFAULT_CAPACITY = 512;
    public static final long DEFAULT_TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);

    private static final int BUCKET_SIZE = 4;
    private static final int MAX_CAS_ATTEMPTS = 32;

    private final AtomicReferenceArray<Bucket> buckets;
    private final int bucketMask;
    private final long ttlNanos;
    private final Ticker ticker;

    public DiagnosticsEventDeduplicator() {
        this(DEFAULT_CAPACITY, DEFAULT_TTL_NANOS, System::nanoTime);
    }

    public DiagnosticsEventDeduplicator(int capacity, long ttlNanos) {
        this(capacity, ttlNanos, System::nanoTime);
    }

    DiagnosticsEventDeduplicator(int capacity, long ttlNanos, Ticker ticker) {
        if (capacity < BUCKET_SIZE || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least " + BUCKET_SIZE);
        }
        if (ttlNanos <= 0L) throw new IllegalArgumentException("ttlNanos must be positive");
        buckets = new AtomicReferenceArray<>(capacity / BUCKET_SIZE);
        bucketMask = buckets.length() - 1;
        this.ttlNanos = ttlNanos;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Returns DUPLICATE only when the same stable semantic key is proven live in this instance. */
    public Outcome evaluate(NormalizedDiagnosticsEvent event) {
        try {
            DiagnosticsEventSemanticKey key = DiagnosticsEventSemanticKey.from(event);
            if (key == null) return Outcome.FAIL_OPEN;
            long now = ticker.readNanos();
            if (now < 0L) return Outcome.FAIL_OPEN;
            int bucketIndex = mixedHash(key.hashCode()) & bucketMask;
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                Bucket observed = buckets.get(bucketIndex);
                Bucket current = observed == null ? Bucket.empty() : observed;
                int existingIndex = current.indexOf(key);
                if (existingIndex >= 0
                        && isWithinTtl(now, current.entryAt(existingIndex).acceptedAtNanos)) {
                    if (buckets.get(bucketIndex) == observed) return Outcome.DUPLICATE;
                    continue;
                }
                Bucket updated = current.withAccepted(key, now, ttlNanos);
                if (buckets.compareAndSet(bucketIndex, observed, updated)) {
                    return Outcome.ACCEPTED;
                }
            }
            return Outcome.FAIL_OPEN;
        } catch (Throwable ignored) {
            return Outcome.FAIL_OPEN;
        }
    }

    public boolean shouldRecord(NormalizedDiagnosticsEvent event) {
        return evaluate(event) != Outcome.DUPLICATE;
    }

    int occupiedSlots() {
        int occupied = 0;
        for (int index = 0; index < buckets.length(); index++) {
            Bucket bucket = buckets.get(index);
            if (bucket != null) occupied += bucket.size();
        }
        return occupied;
    }

    private boolean isWithinTtl(long now, long acceptedAt) {
        return now >= acceptedAt && now - acceptedAt < ttlNanos;
    }

    private static int mixedHash(int value) {
        int hash = value;
        hash ^= hash >>> 16;
        hash *= 0x7FEB352D;
        hash ^= hash >>> 15;
        hash *= 0x846CA68B;
        return hash ^ (hash >>> 16);
    }

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        FAIL_OPEN
    }

    @FunctionalInterface
    interface Ticker {
        long readNanos();
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

        private int indexOf(DiagnosticsEventSemanticKey key) {
            for (int index = 0; index < entries.length; index++) {
                if (entries[index].key.equals(key)) return index;
            }
            return -1;
        }

        private Bucket withAccepted(
                DiagnosticsEventSemanticKey key, long now, long ttlNanos) {
            Entry[] live = new Entry[BUCKET_SIZE];
            int liveCount = 0;
            for (Entry entry : entries) {
                if (entry.key.equals(key)) continue;
                if (now < entry.acceptedAtNanos
                        || now - entry.acceptedAtNanos >= ttlNanos) {
                    continue;
                }
                live[liveCount++] = entry;
            }
            Entry accepted = new Entry(key, now);
            if (liveCount < BUCKET_SIZE) {
                Entry[] copy = new Entry[liveCount + 1];
                System.arraycopy(live, 0, copy, 0, liveCount);
                copy[liveCount] = accepted;
                return new Bucket(copy);
            }
            int oldest = 0;
            for (int index = 1; index < liveCount; index++) {
                if (live[index].acceptedAtNanos < live[oldest].acceptedAtNanos) {
                    oldest = index;
                }
            }
            live[oldest] = accepted;
            return new Bucket(live);
        }
    }

    private static final class Entry {
        private final DiagnosticsEventSemanticKey key;
        private final long acceptedAtNanos;

        private Entry(DiagnosticsEventSemanticKey key, long acceptedAtNanos) {
            this.key = Objects.requireNonNull(key, "key");
            this.acceptedAtNanos = acceptedAtNanos;
        }
    }
}
