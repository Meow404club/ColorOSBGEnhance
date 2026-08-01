package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed result for network restrictions and HANS resource proxying. */
public final class ResourceDecision {
    public enum Action {
        ALLOW_SYSTEM,
        KEEP_RESOURCE
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final DecisionTrace trace;

    ResourceDecision(Action effectiveAction, Action policyAction, DecisionTrace trace) {
        this.effectiveAction = Objects.requireNonNull(effectiveAction, "effectiveAction");
        this.policyAction = Objects.requireNonNull(policyAction, "policyAction");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public Action effectiveAction() { return effectiveAction; }
    public Action policyAction() { return policyAction; }
    public DecisionTrace trace() { return trace; }
    public boolean keepsResource() { return effectiveAction == Action.KEEP_RESOURCE; }
}
