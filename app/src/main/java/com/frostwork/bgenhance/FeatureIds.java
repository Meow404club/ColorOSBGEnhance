package com.frostwork.bgenhance;

import java.util.Arrays;
import java.util.List;

public final class FeatureIds {
    private FeatureIds() {}

    public static final String ATHENA_PERIODIC = "athena_periodic";
    public static final String OGUARD_KILL = "oguard_kill";
    public static final String OGUARD_FORCE_STOP = "oguard_force_stop";
    public static final String FAST_CAMERA_START = "fast_clear_camera_start";
    public static final String FAST_CAMERA_OPERATION = "fast_clear_camera_operation";
    public static final String FAST_GAME_START = "fast_clear_game_start";
    public static final String FAST_AI = "fast_clear_ai";
    public static final String TOURNAMENT = "tournament";
    public static final String MEMORY_FORCE = "memory_guard_force";
    public static final String MEMORY_OVER = "memory_guard_over";
    public static final String NIRVANA = "nirvana";
    public static final String CAMERA_LOW_FREE = "camera_low_free";
    public static final String GAME_LOW_FREE = "game_low_free";
    public static final String HANS = "hans_freeze";
    public static final String SUPER_FREEZE = "super_freeze";
    public static final String ANDROID_FREEZE = "android_freeze";

    public static List<String> all() {
        return Arrays.asList(
                ATHENA_PERIODIC, OGUARD_KILL, OGUARD_FORCE_STOP,
                FAST_CAMERA_START, FAST_CAMERA_OPERATION, FAST_GAME_START, FAST_AI,
                TOURNAMENT, MEMORY_FORCE, MEMORY_OVER,
                NIRVANA, CAMERA_LOW_FREE, GAME_LOW_FREE,
                HANS, SUPER_FREEZE, ANDROID_FREEZE
        );
    }
}
