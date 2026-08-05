package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.Objects;

/** Normalized source attempting to wake a frozen application. */
public final class WakeContext extends ResidencyContext {
    private final WakeSource source;

    public WakeContext(ResidencyTarget target, WakeSource source) {
        this(target, source, "", "");
    }

    public WakeContext(
            ResidencyTarget target,
            WakeSource source,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.source = Objects.requireNonNull(source, "source");
    }

    public WakeSource source() { return source; }
}
