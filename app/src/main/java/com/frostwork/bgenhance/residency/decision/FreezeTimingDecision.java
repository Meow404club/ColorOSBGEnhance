package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed result for one HANS state-transition timing query. */
public final class FreezeTimingDecision {
    public enum Action {
        FOLLOW_SYSTEM,
        APPLY_CUSTOM_TIMING
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final long delayMs;
    private final DecisionTrace trace;

    FreezeTimingDecision(
            Action effectiveAction,
            Action policyAction,
            long delayMs,
            DecisionTrace trace) {
        this.effectiveAction = Objects.requireNonNull(effectiveAction, "effectiveAction");
        this.policyAction = Objects.requireNonNull(policyAction, "policyAction");
        this.delayMs = delayMs;
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public Action effectiveAction() { return effectiveAction; }
    public Action policyAction() { return policyAction; }
    public long delayMs() { return delayMs; }
    public DecisionTrace trace() { return trace; }
    public boolean appliesCustomTiming() {
        return effectiveAction == Action.APPLY_CUSTOM_TIMING;
    }
}
