package com.frostwork.bgenhance.residency.model;

import java.util.Objects;

/** Allow, throttle or block policy for a high-frequency wake channel. */
public final class WakeChannelPolicy {
    private final WakeMode mode;
    private final long cooldownMs;

    public WakeChannelPolicy(WakeMode mode, long cooldownMs) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.cooldownMs = cooldownMs;
    }

    public static WakeChannelPolicy allow() {
        return new WakeChannelPolicy(WakeMode.ALLOW, 0L);
    }

    public WakeMode mode() { return mode; }
    public long cooldownMs() { return cooldownMs; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WakeChannelPolicy)) return false;
        WakeChannelPolicy that = (WakeChannelPolicy) other;
        return cooldownMs == that.cooldownMs && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, cooldownMs);
    }
}
