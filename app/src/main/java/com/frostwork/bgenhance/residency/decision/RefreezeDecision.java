package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed post-wake refreeze result. */
public final class RefreezeDecision {
    public enum Action {
        ALLOW_SYSTEM,
        SCHEDULE_REFREEZE,
        DISABLE_REFREEZE
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final long delayMs;
    private final DecisionTrace trace;

    RefreezeDecision(
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
}
