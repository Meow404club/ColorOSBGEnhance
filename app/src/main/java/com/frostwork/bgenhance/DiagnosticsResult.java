package com.frostwork.bgenhance;

/** Stable result identifiers shared by decision reporting and diagnostics storage. */
public final class DiagnosticsResult {
    private DiagnosticsResult() {}

    public static final String PASSED = "passed";
    public static final String BLOCKED = "blocked";
    public static final String OBSERVED = "observed";
    public static final String CANDIDATE_FILTERED = "candidate_filtered";
    public static final String FAIL_OPEN = "fail_open";
    public static final String CONFIG_UNAVAILABLE = "config_unavailable";
    public static final String THROTTLE_ALLOWED = "throttle_allowed";
    public static final String THROTTLE_BLOCKED = "throttle_blocked";
    public static final String RUNTIME_FAIL_OPEN = "runtime_fail_open";
    public static final String RECOVERED = "recovered";
    public static final String NO_ACTION_NEEDED = "no_action_needed";
    public static final String SAFETY_ABORTED = "safety_aborted";
    public static final String APPLIED = "applied";

    public static boolean countsAsBlocked(String result) {
        return BLOCKED.equals(result)
                || CANDIDATE_FILTERED.equals(result)
                || THROTTLE_BLOCKED.equals(result);
    }

    public static boolean countsAsFailOpen(String result) {
        return CONFIG_UNAVAILABLE.equals(result)
                || FAIL_OPEN.equals(result)
                || RUNTIME_FAIL_OPEN.equals(result);
    }

    /** Counts only fail-open outcomes that indicate an internal or configuration fault. */
    public static boolean countsAsAnomalousFailOpen(String result, String decision) {
        if (CONFIG_UNAVAILABLE.equals(result) || RUNTIME_FAIL_OPEN.equals(result)) {
            return true;
        }
        if (!FAIL_OPEN.equals(result)) return false;
        return !isProtectiveFailOpenDecision(decision);
    }

    public static boolean isProtectiveFailOpenDecision(String decision) {
        return "MASTER_DISABLED".equals(decision)
                || "RESIDENCY_DISABLED".equals(decision)
                || "IDENTITY_UNKNOWN".equals(decision)
                || "IDENTITY_UID_MISMATCH".equals(decision)
                || "SHARED_UID_CONFLICT".equals(decision)
                || "SYSTEM_UID".equals(decision);
    }

    public static boolean isKnown(String result) {
        return PASSED.equals(result)
                || BLOCKED.equals(result)
                || OBSERVED.equals(result)
                || CANDIDATE_FILTERED.equals(result)
                || countsAsFailOpen(result)
                || THROTTLE_ALLOWED.equals(result)
                || THROTTLE_BLOCKED.equals(result)
                || RECOVERED.equals(result)
                || NO_ACTION_NEEDED.equals(result)
                || SAFETY_ABORTED.equals(result)
                || APPLIED.equals(result);
    }
}
