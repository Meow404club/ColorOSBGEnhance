package com.frostwork.bgenhance;

/** Reserved diagnostics rows that describe runtime state rather than installed Hook methods. */
public final class DiagnosticsTargetIds {
    private DiagnosticsTargetIds() {}

    public static final String RUNTIME_PROCESS = "runtime_process";
    public static final String RUNTIME_CONFIG = "runtime_config";

    public static boolean isKnown(String value) {
        return HookTargetIds.all().contains(value)
                || RUNTIME_PROCESS.equals(value)
                || RUNTIME_CONFIG.equals(value);
    }
}
