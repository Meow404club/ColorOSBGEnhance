package com.frostwork.bgenhance.hooks.osense;

/** Reason an OSense process identity could not be resolved safely. */
public enum OsenseProcessIdentityFailure {
    NULL_PROCESS_INFO,
    ACCESSOR_MISSING,
    REFLECTION_FAILED,
    INVALID_UID,
    INVALID_PID,
    INVALID_PROCESS_NAME,
    INCOMPLETE_PACKAGE_IDENTITY,
    PACKAGE_NAME_CONFLICT
}
