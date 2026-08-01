package com.frostwork.bgenhance.residency.runtime;

/** Result of resolving package and UID hints against one policy revision. */
public enum IdentityResolutionStatus {
    NOT_EVALUATED,
    EXACT_PACKAGE,
    UID_SINGLE_RULE,
    SHARED_UID_SAME_POLICY,
    SHARED_UID_CONFLICT,
    UNMANAGED,
    UNKNOWN,
    SYSTEM_UID,
    UID_MISMATCH
}
