package com.frostwork.bgenhance.residency.model;

import java.util.Objects;

/** Optional per-source delay before a temporarily woken application can freeze again. */
public final class RefreezePolicy {
    private final RefreezeMode mode;
    private final long defaultDelayMs;
    private final long packetDelayMs;
    private final long alarmDelayMs;
    private final long binderDelayMs;
    private final long serviceDelayMs;
    private final long broadcastDelayMs;
    private final long jobDelayMs;

    public RefreezePolicy(
            RefreezeMode mode,
            long defaultDelayMs,
            long packetDelayMs,
            long alarmDelayMs,
            long binderDelayMs,
            long serviceDelayMs,
            long broadcastDelayMs,
            long jobDelayMs) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.defaultDelayMs = defaultDelayMs;
        this.packetDelayMs = packetDelayMs;
        this.alarmDelayMs = alarmDelayMs;
        this.binderDelayMs = binderDelayMs;
        this.serviceDelayMs = serviceDelayMs;
        this.broadcastDelayMs = broadcastDelayMs;
        this.jobDelayMs = jobDelayMs;
    }

    public static RefreezePolicy followSystem() {
        return new RefreezePolicy(RefreezeMode.SYSTEM, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public RefreezeMode mode() { return mode; }
    public long defaultDelayMs() { return defaultDelayMs; }
    public long packetDelayMs() { return packetDelayMs; }
    public long alarmDelayMs() { return alarmDelayMs; }
    public long binderDelayMs() { return binderDelayMs; }
    public long serviceDelayMs() { return serviceDelayMs; }
    public long broadcastDelayMs() { return broadcastDelayMs; }
    public long jobDelayMs() { return jobDelayMs; }

    public long delayFor(WakeSource source) {
        Objects.requireNonNull(source, "source");
        switch (source) {
            case PACKET: return packetDelayMs;
            case ALARM: return alarmDelayMs;
            case ASYNC_BINDER:
            case SYNC_BINDER:
            case TRANSACTION_BINDER: return binderDelayMs;
            case SERVICE: return serviceDelayMs;
            case BROADCAST: return broadcastDelayMs;
            case JOB_SYNC: return jobDelayMs;
            default: return defaultDelayMs;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RefreezePolicy)) return false;
        RefreezePolicy that = (RefreezePolicy) other;
        return defaultDelayMs == that.defaultDelayMs
                && packetDelayMs == that.packetDelayMs
                && alarmDelayMs == that.alarmDelayMs
                && binderDelayMs == that.binderDelayMs
                && serviceDelayMs == that.serviceDelayMs
                && broadcastDelayMs == that.broadcastDelayMs
                && jobDelayMs == that.jobDelayMs
                && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, defaultDelayMs, packetDelayMs, alarmDelayMs, binderDelayMs,
                serviceDelayMs, broadcastDelayMs, jobDelayMs);
    }
}
