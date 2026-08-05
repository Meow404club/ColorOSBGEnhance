package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityResolution;
import java.util.Objects;

/** Diagnostic metadata shared by all typed decisions. */
public final class DecisionTrace {
    private final String featureId;
    private final String hookTargetId;
    private final long configRevision;
    private final long policyRevision;
    private final long identityIndexRevision;
    private final long identityGeneration;
    private final ResidencyIdentityResolution identity;
    private final DecisionDisposition disposition;
    private final DecisionReason reason;

    DecisionTrace(
            ResidencyContext context,
            long configRevision,
            long policyRevision,
            long identityIndexRevision,
            long identityGeneration,
            ResidencyIdentityResolution identity,
            DecisionDisposition disposition,
            DecisionReason reason) {
        this.featureId = context.featureId();
        this.hookTargetId = context.hookTargetId();
        this.configRevision = configRevision;
        this.policyRevision = policyRevision;
        this.identityIndexRevision = identityIndexRevision;
        this.identityGeneration = identityGeneration;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String featureId() { return featureId; }
    public String hookTargetId() { return hookTargetId; }
    public long configRevision() { return configRevision; }
    public long policyRevision() { return policyRevision; }
    public long identityIndexRevision() { return identityIndexRevision; }
    public long identityGeneration() { return identityGeneration; }
    public ResidencyIdentityResolution identity() { return identity; }
    public DecisionDisposition disposition() { return disposition; }
    public DecisionReason reason() { return reason; }
    public boolean changesSystemBehavior() { return disposition == DecisionDisposition.ENFORCED; }
}
