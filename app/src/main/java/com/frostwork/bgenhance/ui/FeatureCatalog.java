package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FeatureCatalog {
    private FeatureCatalog() {}

    public static final String GROUP_FAST_CLEAR = "fast_clear";
    public static final String GROUP_MEMORY = "memory";
    public static final String GROUP_NIRVANA = "nirvana";
    public static final String GROUP_FREEZE = "freeze";

    public enum Risk { RECOMMENDED, MEDIUM, HIGH, VERY_HIGH }

    public static final class FeatureSpec {
        public final String featureId;
        public final String hookTargetId;
        public final String prefKey;
        public final int titleRes;
        public final int summaryRes;
        public final int detailRes;
        public final Risk risk;
        public final boolean experimental;
        public final boolean diagnosticOnly;

        FeatureSpec(String featureId, String hookTargetId, String prefKey,
                    int titleRes, int summaryRes, int detailRes,
                    Risk risk, boolean experimental, boolean diagnosticOnly) {
            this.featureId = featureId;
            this.hookTargetId = hookTargetId;
            this.prefKey = prefKey;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.detailRes = detailRes;
            this.risk = risk;
            this.experimental = experimental;
            this.diagnosticOnly = diagnosticOnly;
        }
    }

    private static final List<FeatureSpec> FAST_CLEAR = Arrays.asList(
            new FeatureSpec(FeatureIds.FAST_CAMERA_START, HookTargetIds.FAST_CLEAR_DISPATCH,
                    ModuleSettings.KEY_FAST_CAMERA_START, R.string.fast_camera_start_title,
                    R.string.fast_camera_start_summary, R.string.fast_camera_start_detail, Risk.HIGH, false, false),
            new FeatureSpec(FeatureIds.FAST_CAMERA_OPERATION, HookTargetIds.FAST_CLEAR_DISPATCH,
                    ModuleSettings.KEY_FAST_CAMERA_OPERATION, R.string.fast_camera_operation_title,
                    R.string.fast_camera_operation_summary,
                    R.string.fast_camera_operation_detail, Risk.HIGH, false, false),
            new FeatureSpec(FeatureIds.FAST_GAME_START, HookTargetIds.FAST_CLEAR_DISPATCH,
                    ModuleSettings.KEY_FAST_GAME_START, R.string.fast_game_start_title,
                    R.string.fast_game_start_summary, R.string.fast_game_start_detail, Risk.HIGH, false, false),
            new FeatureSpec(FeatureIds.FAST_AI, HookTargetIds.FAST_CLEAR_DISPATCH,
                    ModuleSettings.KEY_FAST_AI, R.string.fast_ai_title,
                    R.string.fast_ai_summary, R.string.fast_ai_detail, Risk.HIGH, true, false),
            new FeatureSpec(FeatureIds.TOURNAMENT, HookTargetIds.TOURNAMENT,
                    ModuleSettings.KEY_TOURNAMENT, R.string.tournament_title,
                    R.string.tournament_summary, R.string.tournament_detail, Risk.HIGH, false, false)
    );

    private static final List<FeatureSpec> MEMORY = Arrays.asList(
            new FeatureSpec(FeatureIds.MEMORY_FORCE, HookTargetIds.MEMORY_GUARD_DISPATCH,
                    ModuleSettings.KEY_MEMORY_FORCE, R.string.memory_force_title,
                    R.string.memory_force_summary, R.string.memory_force_detail, Risk.HIGH, false, false),
            new FeatureSpec(FeatureIds.MEMORY_OVER, HookTargetIds.MEMORY_GUARD_DISPATCH,
                    ModuleSettings.KEY_MEMORY_OVER, R.string.memory_over_title,
                    R.string.memory_over_summary, R.string.memory_over_detail, Risk.VERY_HIGH, false, false)
    );

    private static final List<FeatureSpec> NIRVANA = Arrays.asList(
            new FeatureSpec(FeatureIds.NIRVANA, HookTargetIds.OSENSE_PROCESS_KILL,
                    ModuleSettings.KEY_NIRVANA, R.string.nirvana_title,
                    R.string.nirvana_summary, R.string.nirvana_detail, Risk.VERY_HIGH, false, false),
            new FeatureSpec(FeatureIds.CAMERA_LOW_FREE, HookTargetIds.OSENSE_PROCESS_KILL,
                    ModuleSettings.KEY_CAMERA_LOW_FREE, R.string.camera_low_free_title,
                    R.string.camera_low_free_summary, R.string.camera_low_free_detail, Risk.VERY_HIGH, false, false),
            new FeatureSpec(FeatureIds.GAME_LOW_FREE, HookTargetIds.OSENSE_PROCESS_KILL,
                    ModuleSettings.KEY_GAME_LOW_FREE, R.string.game_low_free_title,
                    R.string.game_low_free_summary, R.string.game_low_free_detail, Risk.VERY_HIGH, true, false)
    );

    private static final List<FeatureSpec> FREEZE = Arrays.asList(
            new FeatureSpec(FeatureIds.HANS, HookTargetIds.HANS_FREEZE_DISPATCH,
                    ModuleSettings.KEY_HANS, R.string.hans_title,
                    R.string.hans_summary, R.string.hans_detail, Risk.VERY_HIGH, false, false),
            new FeatureSpec(FeatureIds.SUPER_FREEZE, HookTargetIds.HANS_FREEZE_DISPATCH,
                    ModuleSettings.KEY_SUPER_FREEZE, R.string.super_freeze_title,
                    R.string.super_freeze_summary, R.string.super_freeze_detail, Risk.VERY_HIGH, true, false)
    );

    public static List<FeatureSpec> forGroup(String group) {
        if (GROUP_FAST_CLEAR.equals(group)) return FAST_CLEAR;
        if (GROUP_MEMORY.equals(group)) return MEMORY;
        if (GROUP_NIRVANA.equals(group)) return NIRVANA;
        if (GROUP_FREEZE.equals(group)) return FREEZE;
        return Collections.emptyList();
    }

    public static int titleForGroup(String group) {
        if (GROUP_FAST_CLEAR.equals(group)) return R.string.fast_clear_page_title;
        if (GROUP_MEMORY.equals(group)) return R.string.memory_page_title;
        if (GROUP_NIRVANA.equals(group)) return R.string.nirvana_page_title;
        if (GROUP_FREEZE.equals(group)) return R.string.freeze_page_title;
        return R.string.app_name;
    }

    public static int subtitleForGroup(String group) {
        if (GROUP_FAST_CLEAR.equals(group)) return R.string.fast_clear_page_subtitle;
        if (GROUP_MEMORY.equals(group)) return R.string.memory_page_subtitle;
        if (GROUP_NIRVANA.equals(group)) return R.string.nirvana_page_subtitle;
        if (GROUP_FREEZE.equals(group)) return R.string.freeze_page_subtitle;
        return R.string.page_subtitle;
    }

    public static String groupForPrefKey(String key) {
        for (String group : Arrays.asList(GROUP_FAST_CLEAR, GROUP_MEMORY, GROUP_NIRVANA, GROUP_FREEZE)) {
            for (FeatureSpec spec : forGroup(group)) if (spec.prefKey.equals(key)) return group;
        }
        return "";
    }
}
