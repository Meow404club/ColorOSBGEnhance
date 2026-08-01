package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.Objects;

/** Wake source used to choose the post-wake refreeze delay. */
public final class RefreezeContext extends ResidencyContext {
    private final WakeSource wakeSource;

    public RefreezeContext(ResidencyTarget target, WakeSource wakeSource) {
        this(target, wakeSource, "", "");
    }

    public RefreezeContext(
            ResidencyTarget target,
            WakeSource wakeSource,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.wakeSource = Objects.requireNonNull(wakeSource, "wakeSource");
    }

    public WakeSource wakeSource() { return wakeSource; }
}
