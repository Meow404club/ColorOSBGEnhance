package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed wake result; throttling state is intentionally owned by a later runtime adapter. */
public final class WakeDecision {
    public enum Action {
        ALLOW_SYSTEM,
        ALLOW_WAKE,
        THROTTLE_WAKE,
        BLOCK_WAKE
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final long cooldownMs;
    private final DecisionTrace trace;

    WakeDecision(
            Action effectiveAction,
            Action policyAction,
            long cooldownMs,
            DecisionTrace trace) {
        this.effectiveAction = Objects.requireNonNull(effectiveAction, "effectiveAction");
        this.policyAction = Objects.requireNonNull(policyAction, "policyAction");
        this.cooldownMs = cooldownMs;
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public Action effectiveAction() { return effectiveAction; }
    public Action policyAction() { return policyAction; }
    public long cooldownMs() { return cooldownMs; }
    public DecisionTrace trace() { return trace; }
    public boolean blocksWake() { return effectiveAction == Action.BLOCK_WAKE; }
}
