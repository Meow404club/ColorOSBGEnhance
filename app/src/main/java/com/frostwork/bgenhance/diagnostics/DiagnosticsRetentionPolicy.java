package com.frostwork.bgenhance.diagnostics;

/** Explicit bounds for the private diagnostics database and its in-memory writer. */
public final class DiagnosticsRetentionPolicy {
    private static final int MAX_ROW_LIMIT = 65_536;
    public static final int MAX_AGGREGATE_SNAPSHOT_ROWS = MAX_ROW_LIMIT;
    private static final int MAX_QUEUE_CAPACITY = 8_192;
    public static final int MAX_ACCEPTED_BATCH_ITEMS = 256;
    private static final int MAX_EMERGENCY_CLEANUP_ROWS = 8_192;
    private static final long MIN_DATABASE_BYTES = 1L * 1_024L * 1_024L;
    private static final long MAX_DATABASE_BYTES = 64L * 1_024L * 1_024L;

    public static final long DEFAULT_RETENTION_MILLIS = 14L * DiagnosticsValues.DAY_MILLIS;
    public static final int DEFAULT_RECENT_EVENT_LIMIT = 4_096;
    public static final int DEFAULT_AGGREGATE_ROW_LIMIT = 8_192;
    public static final int DEFAULT_RAW_AGGREGATE_ROW_LIMIT = 2_048;
    public static final int DEFAULT_HOOK_STATE_LIMIT = 256;
    public static final int DEFAULT_FEATURE_TOTAL_LIMIT = 256;
    public static final int DEFAULT_BATCH_CURSOR_LIMIT = 128;
    public static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final long DEFAULT_FLUSH_DELAY_MILLIS = 500L;
    public static final long DEFAULT_MAINTENANCE_INTERVAL_MILLIS = 15L * 60L * 1_000L;
    public static final int DEFAULT_EMERGENCY_CLEANUP_ROW_LIMIT = 512;
    public static final long DEFAULT_MAX_DATABASE_BYTES = 12L * 1_024L * 1_024L;

    public final long retentionMillis;
    public final int recentEventLimit;
    public final int aggregateRowLimit;
    public final int rawAggregateRowLimit;
    public final int hookStateLimit;
    public final int featureTotalLimit;
    public final int batchCursorLimit;
    public final int queueCapacity;
    public final int batchSize;
    public final long flushDelayMillis;
    public final long maintenanceIntervalMillis;
    public final int emergencyCleanupRowLimit;
    public final long maxDatabaseBytes;

    private DiagnosticsRetentionPolicy(Builder builder) {
        retentionMillis = positive(builder.retentionMillis, "retentionMillis");
        recentEventLimit = positiveAtMost(
                builder.recentEventLimit, MAX_ROW_LIMIT, "recentEventLimit");
        aggregateRowLimit = positiveAtMost(
                builder.aggregateRowLimit, MAX_ROW_LIMIT, "aggregateRowLimit");
        rawAggregateRowLimit = positiveAtMost(
                builder.rawAggregateRowLimit, MAX_ROW_LIMIT, "rawAggregateRowLimit");
        hookStateLimit = positiveAtMost(
                builder.hookStateLimit, MAX_ROW_LIMIT, "hookStateLimit");
        featureTotalLimit = positiveAtMost(
                builder.featureTotalLimit, MAX_ROW_LIMIT, "featureTotalLimit");
        batchCursorLimit = positiveAtMost(
                builder.batchCursorLimit, MAX_ROW_LIMIT, "batchCursorLimit");
        queueCapacity = positiveAtMost(
                builder.queueCapacity, MAX_QUEUE_CAPACITY, "queueCapacity");
        batchSize = positiveAtMost(
                builder.batchSize, MAX_ACCEPTED_BATCH_ITEMS, "batchSize");
        flushDelayMillis = nonNegative(builder.flushDelayMillis, "flushDelayMillis");
        maintenanceIntervalMillis = positive(
                builder.maintenanceIntervalMillis, "maintenanceIntervalMillis");
        emergencyCleanupRowLimit = positiveAtMost(
                builder.emergencyCleanupRowLimit,
                MAX_EMERGENCY_CLEANUP_ROWS,
                "emergencyCleanupRowLimit");
        maxDatabaseBytes = positive(builder.maxDatabaseBytes, "maxDatabaseBytes");
        if (batchSize > queueCapacity) {
            throw new IllegalArgumentException("batchSize exceeds queueCapacity");
        }
        if (maxDatabaseBytes < MIN_DATABASE_BYTES || maxDatabaseBytes > MAX_DATABASE_BYTES) {
            throw new IllegalArgumentException("maxDatabaseBytes is outside the supported range");
        }
        if ((maxDatabaseBytes & 4_095L) != 0L) {
            throw new IllegalArgumentException("maxDatabaseBytes must be page aligned");
        }
    }

    public static DiagnosticsRetentionPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int positiveAtMost(int value, int maximum, String name) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    public static final class Builder {
        private long retentionMillis = DEFAULT_RETENTION_MILLIS;
        private int recentEventLimit = DEFAULT_RECENT_EVENT_LIMIT;
        private int aggregateRowLimit = DEFAULT_AGGREGATE_ROW_LIMIT;
        private int rawAggregateRowLimit = DEFAULT_RAW_AGGREGATE_ROW_LIMIT;
        private int hookStateLimit = DEFAULT_HOOK_STATE_LIMIT;
        private int featureTotalLimit = DEFAULT_FEATURE_TOTAL_LIMIT;
        private int batchCursorLimit = DEFAULT_BATCH_CURSOR_LIMIT;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private long flushDelayMillis = DEFAULT_FLUSH_DELAY_MILLIS;
        private long maintenanceIntervalMillis = DEFAULT_MAINTENANCE_INTERVAL_MILLIS;
        private int emergencyCleanupRowLimit = DEFAULT_EMERGENCY_CLEANUP_ROW_LIMIT;
        private long maxDatabaseBytes = DEFAULT_MAX_DATABASE_BYTES;

        public Builder retentionMillis(long value) { retentionMillis = value; return this; }
        public Builder recentEventLimit(int value) { recentEventLimit = value; return this; }
        public Builder aggregateRowLimit(int value) { aggregateRowLimit = value; return this; }
        public Builder rawAggregateRowLimit(int value) {
            rawAggregateRowLimit = value;
            return this;
        }
        public Builder hookStateLimit(int value) { hookStateLimit = value; return this; }
        public Builder featureTotalLimit(int value) { featureTotalLimit = value; return this; }
        public Builder batchCursorLimit(int value) { batchCursorLimit = value; return this; }
        public Builder queueCapacity(int value) { queueCapacity = value; return this; }
        public Builder batchSize(int value) { batchSize = value; return this; }
        public Builder flushDelayMillis(long value) { flushDelayMillis = value; return this; }
        public Builder maintenanceIntervalMillis(long value) {
            maintenanceIntervalMillis = value;
            return this;
        }
        public Builder emergencyCleanupRowLimit(int value) {
            emergencyCleanupRowLimit = value;
            return this;
        }
        public Builder maxDatabaseBytes(long value) { maxDatabaseBytes = value; return this; }

        public DiagnosticsRetentionPolicy build() {
            return new DiagnosticsRetentionPolicy(this);
        }
    }
}
