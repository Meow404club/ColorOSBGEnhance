package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.UidPolicyScope;
import java.util.Objects;

/** One HANS state-transition timing query and its UID-wide policy scope. */
public final class FreezeTimingContext extends ResidencyContext {
    public enum Transition {
        R_TO_M(UidPolicyScope.R_TO_M_TIMING),
        M_TO_F(UidPolicyScope.M_TO_F_TIMING);

        private final UidPolicyScope uidPolicyScope;

        Transition(UidPolicyScope uidPolicyScope) {
            this.uidPolicyScope = uidPolicyScope;
        }

        public UidPolicyScope uidPolicyScope() { return uidPolicyScope; }
    }

    private final Transition transition;

    public FreezeTimingContext(ResidencyTarget target, Transition transition) {
        this(target, transition, "", "");
    }

    public FreezeTimingContext(
            ResidencyTarget target,
            Transition transition,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.transition = Objects.requireNonNull(transition, "transition");
    }

    public Transition transition() { return transition; }
    public UidPolicyScope uidPolicyScope() { return transition.uidPolicyScope(); }
}
