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
    public static final String OSENSE_PROCESS_KILL = "osense_process_kill";
    public static final String HANS_FREEZE_DISPATCH = "hans_freeze_dispatch";
    public static final String HANS_WAKE_DISPATCH = "hans_wake_dispatch";
    public static final String ALARM_WAKE_DELIVERY = "alarm_wake_delivery";
    public static final String HANS_R_TO_M_TIMING = "hans_r_to_m_timing";
    public static final String HANS_M_TO_F_TIMING = "hans_m_to_f_timing";
    public static final String HANS_CORE_EXEMPTION = "hans_core_exemption";
    public static final String HANS_LCD_ON_EXEMPTION = "hans_lcd_on_exemption";

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            ATHENA_PERIODIC,
            OGUARD_KILL,
            OGUARD_FORCE_STOP,
            FAST_CLEAR_DISPATCH,
            TOURNAMENT,
            MEMORY_GUARD_DISPATCH,
            OSENSE_GROUP_CLEAN,
            OSENSE_PROCESS_KILL,
            HANS_FREEZE_DISPATCH,
            HANS_WAKE_DISPATCH,
            ALARM_WAKE_DELIVERY,
            HANS_R_TO_M_TIMING,
            HANS_M_TO_F_TIMING,
            HANS_CORE_EXEMPTION,
            HANS_LCD_ON_EXEMPTION
    ));

    public static List<String> all() {
        return ALL;
    }
}
