package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed result for a process-termination action. */
public final class KillDecision {
    public enum Action {
        ALLOW_SYSTEM,
        BLOCK_ACTION
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final DecisionTrace trace;

    KillDecision(Action effectiveAction, Action policyAction, DecisionTrace trace) {
        this.effectiveAction = Objects.requireNonNull(effectiveAction, "effectiveAction");
        this.policyAction = Objects.requireNonNull(policyAction, "policyAction");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public Action effectiveAction() { return effectiveAction; }
    public Action policyAction() { return policyAction; }
    public DecisionTrace trace() { return trace; }
    public boolean blocksAction() { return effectiveAction == Action.BLOCK_ACTION; }
}
