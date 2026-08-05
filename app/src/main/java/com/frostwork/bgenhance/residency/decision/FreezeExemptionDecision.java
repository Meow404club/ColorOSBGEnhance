package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed result for one HANS exemption query. */
public final class FreezeExemptionDecision {
    public enum Action {
        FOLLOW_SYSTEM,
        GRANT_EXEMPTION
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final DecisionTrace trace;

    FreezeExemptionDecision(
            Action effectiveAction,
            Action policyAction,
            DecisionTrace trace) {
        this.effectiveAction = Objects.requireNonNull(effectiveAction, "effectiveAction");
        this.policyAction = Objects.requireNonNull(policyAction, "policyAction");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public Action effectiveAction() { return effectiveAction; }
    public Action policyAction() { return policyAction; }
    public DecisionTrace trace() { return trace; }
    public boolean grantsExemption() {
        return effectiveAction == Action.GRANT_EXEMPTION;
    }
}
