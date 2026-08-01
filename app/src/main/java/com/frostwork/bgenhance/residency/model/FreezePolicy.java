package com.frostwork.bgenhance.residency.model;

import java.util.Objects;

/** Per-application HANS timing, source and exemption policy. */
public final class FreezePolicy {
    private final boolean allowNormalFreeze;
    private final boolean allowFastFreeze;
    private final boolean allowSuperFreeze;
    private final boolean allowPreloadFreeze;
    private final boolean customTiming;
    private final long rToMDelayMs;
    private final long mToFDelayMs;
    private final boolean fullExemption;
    private final boolean coreExemption;
    private final boolean lcdOnExemption;

    public FreezePolicy(
            boolean allowNormalFreeze,
            boolean allowFastFreeze,
            boolean allowSuperFreeze,
            boolean allowPreloadFreeze,
            boolean customTiming,
            long rToMDelayMs,
            long mToFDelayMs,
            boolean fullExemption,
            boolean coreExemption,
            boolean lcdOnExemption) {
        this.allowNormalFreeze = allowNormalFreeze;
        this.allowFastFreeze = allowFastFreeze;
        this.allowSuperFreeze = allowSuperFreeze;
        this.allowPreloadFreeze = allowPreloadFreeze;
        this.customTiming = customTiming;
        this.rToMDelayMs = rToMDelayMs;
        this.mToFDelayMs = mToFDelayMs;
        this.fullExemption = fullExemption;
        this.coreExemption = coreExemption;
        this.lcdOnExemption = lcdOnExemption;
    }

    public static FreezePolicy followSystem() {
        return new FreezePolicy(
                true, true, true, true, false, 0L, 0L, false, false, false);
    }

    public boolean allows(FreezeSource source) {
        Objects.requireNonNull(source, "source");
        switch (source) {
            case NORMAL: return allowNormalFreeze;
            case FAST: return allowFastFreeze;
            case SUPER: return allowSuperFreeze;
            case PRELOAD: return allowPreloadFreeze;
            default: throw new AssertionError(source);
        }
    }

    public boolean allowNormalFreeze() { return allowNormalFreeze; }
    public boolean allowFastFreeze() { return allowFastFreeze; }
    public boolean allowSuperFreeze() { return allowSuperFreeze; }
    public boolean allowPreloadFreeze() { return allowPreloadFreeze; }
    public boolean customTiming() { return customTiming; }
    public long rToMDelayMs() { return rToMDelayMs; }
    public long mToFDelayMs() { return mToFDelayMs; }
    public boolean fullExemption() { return fullExemption; }
    public boolean coreExemption() { return coreExemption; }
    public boolean lcdOnExemption() { return lcdOnExemption; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FreezePolicy)) return false;
        FreezePolicy that = (FreezePolicy) other;
        return allowNormalFreeze == that.allowNormalFreeze
                && allowFastFreeze == that.allowFastFreeze
                && allowSuperFreeze == that.allowSuperFreeze
                && allowPreloadFreeze == that.allowPreloadFreeze
                && customTiming == that.customTiming
                && rToMDelayMs == that.rToMDelayMs
                && mToFDelayMs == that.mToFDelayMs
                && fullExemption == that.fullExemption
                && coreExemption == that.coreExemption
                && lcdOnExemption == that.lcdOnExemption;
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowNormalFreeze, allowFastFreeze, allowSuperFreeze,
                allowPreloadFreeze, customTiming, rToMDelayMs, mToFDelayMs, fullExemption,
                coreExemption, lcdOnExemption);
    }
}
