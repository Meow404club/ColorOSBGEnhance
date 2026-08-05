package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.Objects;

/** Common immutable metadata carried by every typed residency context. */
public abstract class ResidencyContext {
    private final ResidencyTarget target;
    private final String featureId;
    private final String hookTargetId;

    protected ResidencyContext(
            ResidencyTarget target,
            String featureId,
            String hookTargetId) {
        this.target = Objects.requireNonNull(target, "target");
        this.featureId = featureId == null ? "" : featureId;
        this.hookTargetId = hookTargetId == null ? "" : hookTargetId;
    }

    public ResidencyTarget target() { return target; }
    public String featureId() { return featureId; }
    public String hookTargetId() { return hookTargetId; }
}
