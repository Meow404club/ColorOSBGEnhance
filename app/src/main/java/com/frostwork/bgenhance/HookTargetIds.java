package com.frostwork.bgenhance;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stable identifiers for hook installation and support diagnostics. */
public final class HookTargetIds {
    private HookTargetIds() {}

    public static final String ATHENA_PERIODIC = "athena_periodic";
    public static final String OGUARD_KILL = "oguard_kill";
    public static final String OGUARD_FORCE_STOP = "oguard_force_stop";
    public static final String FAST_CLEAR_DISPATCH = "fast_clear_dispatch";
    public static final String TOURNAMENT = "tournament";
    public static final String MEMORY_GUARD_DISPATCH = "memory_guard_dispatch";
    public static final String OSENSE_GROUP_CLEAN = "osense_group_clean";
    public static final String HANS_FREEZE_DISPATCH = "hans_freeze_dispatch";

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            ATHENA_PERIODIC,
            OGUARD_KILL,
            OGUARD_FORCE_STOP,
            FAST_CLEAR_DISPATCH,
            TOURNAMENT,
            MEMORY_GUARD_DISPATCH,
            OSENSE_GROUP_CLEAN,
            HANS_FREEZE_DISPATCH
    ));

    public static List<String> all() {
        return ALL;
    }
}
