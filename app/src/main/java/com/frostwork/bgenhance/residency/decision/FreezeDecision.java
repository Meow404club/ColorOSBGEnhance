package com.frostwork.bgenhance.residency.decision;

import java.util.Objects;

/** Typed result for a freeze gate and its optional state-transition timing. */
public final class FreezeDecision {
    public enum Action {
        ALLOW_SYSTEM,
        APPLY_CUSTOM_TIMING,
        BLOCK_FREEZE
    }

    private final Action effectiveAction;
    private final Action policyAction;
    private final long rToMDelayMs;
    private final long mToFDelayMs;
    private final DecisionTrace trace;

    FreezeDecision(
            Action effectiveAction,
            Action policyAction,
            long rToMDelayMs,
            long mToFDelayMs,
            DecisionTrace trace) {
        this.effectiveAction = Objects.requireNonNull(effectiveAction, "effectiveAction");
        this.policyAction = Objects.requireNonNull(policyAction, "policyAction");
        this.rToMDelayMs = rToMDelayMs;
        this.mToFDelayMs = mToFDelayMs;
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public Action effectiveAction() { return effectiveAction; }
    public Action policyAction() { return policyAction; }
    public long rToMDelayMs() { return rToMDelayMs; }
    public long mToFDelayMs() { return mToFDelayMs; }
    public DecisionTrace trace() { return trace; }
    public boolean blocksFreeze() { return effectiveAction == Action.BLOCK_FREEZE; }
}
