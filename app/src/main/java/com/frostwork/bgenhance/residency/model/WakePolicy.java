package com.frostwork.bgenhance.residency.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Wake policy for packet, alarm and component-driven sources. */
public final class WakePolicy {
    private final WakeChannelPolicy packet;
    private final WakeChannelPolicy alarm;
    private final Set<WakeSource> blockedSources;

    public WakePolicy(
            WakeChannelPolicy packet,
            WakeChannelPolicy alarm,
            Set<WakeSource> blockedSources) {
        this.packet = Objects.requireNonNull(packet, "packet");
        this.alarm = Objects.requireNonNull(alarm, "alarm");
        EnumSet<WakeSource> copy = blockedSources == null || blockedSources.isEmpty()
                ? EnumSet.noneOf(WakeSource.class) : EnumSet.copyOf(blockedSources);
        this.blockedSources = Collections.unmodifiableSet(copy);
    }

    public static WakePolicy allowAll() {
        return new WakePolicy(WakeChannelPolicy.allow(), WakeChannelPolicy.allow(),
                Collections.emptySet());
    }

    public WakeMode modeFor(WakeSource source) {
        Objects.requireNonNull(source, "source");
        if (source == WakeSource.PACKET) return packet.mode();
        if (source == WakeSource.ALARM) return alarm.mode();
        return blockedSources.contains(source) ? WakeMode.BLOCK : WakeMode.ALLOW;
    }

    public WakeChannelPolicy packet() { return packet; }
    public WakeChannelPolicy alarm() { return alarm; }
    public Set<WakeSource> blockedSources() { return blockedSources; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WakePolicy)) return false;
        WakePolicy that = (WakePolicy) other;
        return packet.equals(that.packet)
                && alarm.equals(that.alarm)
                && blockedSources.equals(that.blockedSources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packet, alarm, blockedSources);
    }
}
