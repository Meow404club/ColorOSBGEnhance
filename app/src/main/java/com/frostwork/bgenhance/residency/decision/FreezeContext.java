package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.Objects;

/** Freeze source and target presented to the HANS policy layer. */
public final class FreezeContext extends ResidencyContext {
    private final FreezeSource source;

    public FreezeContext(ResidencyTarget target, FreezeSource source) {
        this(target, source, "", "");
    }

    public FreezeContext(
            ResidencyTarget target,
            FreezeSource source,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.source = Objects.requireNonNull(source, "source");
    }

    public FreezeSource source() { return source; }
}
