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

    public static boolean countsAsBlocked(String result) {
        return BLOCKED.equals(result) || THROTTLE_BLOCKED.equals(result);
    }

    public static boolean countsAsFailOpen(String result) {
        return CONFIG_UNAVAILABLE.equals(result)
                || FAIL_OPEN.equals(result)
                || RUNTIME_FAIL_OPEN.equals(result);
    }
}
