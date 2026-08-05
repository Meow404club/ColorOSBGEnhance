package com.frostwork.bgenhance.residency.decision;

/** Describes whether a policy decision changes the underlying system behavior. */
public enum DecisionDisposition {
    ENFORCED,
    PASS_THROUGH,
    DRY_RUN,
    FAIL_OPEN
}
