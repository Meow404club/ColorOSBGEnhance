package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.HookTargetIds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable metadata for every physical Hook target shown by diagnostics. */
public final class DiagnosticsHookCatalog {
    public static final String PROCESS_SYSTEM_SERVER = "system_server";
    public static final String PROCESS_ATHENA = "athena";

    public static final class Entry {
        public final String hookTargetId;
        public final String expectedProcessId;
        public final String titleKey;
        public final String fallbackTitle;

        private Entry(
                String hookTargetId,
                String expectedProcessId,
                String titleKey,
                String fallbackTitle) {
            this.hookTargetId = requireText(hookTargetId, "hookTargetId");
            this.expectedProcessId = requireText(expectedProcessId, "expectedProcessId");
            this.titleKey = requireText(titleKey, "titleKey");
            this.fallbackTitle = requireText(fallbackTitle, "fallbackTitle");
        }

        public String stableId() {
            return "hook:" + expectedProcessId + ":" + hookTargetId;
        }
    }

    private static final List<Entry> ALL;
    private static final Map<String, Entry> BY_TARGET;

    static {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(athena(
                HookTargetIds.ATHENA_PERIODIC,
                "diagnostics_hook_athena_periodic",
                "定期后台清理"));
        entries.add(system(
                HookTargetIds.OGUARD_KILL,
                "diagnostics_hook_oguard_kill",
                "后台进程清理"));
        entries.add(system(
                HookTargetIds.OGUARD_FORCE_STOP,
                "diagnostics_hook_oguard_force_stop",
                "后台应用强制停止"));
        entries.add(athena(
                HookTargetIds.FAST_CLEAR_DISPATCH,
                "diagnostics_hook_fast_clear",
                "场景快速清理"));
        entries.add(athena(
                HookTargetIds.TOURNAMENT,
                "diagnostics_hook_tournament",
                "游戏场景清理"));
        entries.add(athena(
                HookTargetIds.MEMORY_GUARD_DISPATCH,
                "diagnostics_hook_memory_guard",
                "低内存清理"));
        entries.add(system(
                HookTargetIds.OSENSE_GROUP_CLEAN,
                "diagnostics_hook_osense_group_clean",
                "应用分组清理"));
        entries.add(system(
                HookTargetIds.OSENSE_PROCESS_KILL,
                "diagnostics_hook_osense_process_kill",
                "单应用内存清理"));
        entries.add(system(
                HookTargetIds.HANS_FREEZE_DISPATCH,
                "diagnostics_hook_hans_freeze",
                "后台冻结"));
        entries.add(system(
                HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
                "diagnostics_hook_hans_fast_freeze",
                "快速冻结"));
        entries.add(system(
                HookTargetIds.HANS_WAKE_DISPATCH,
                "diagnostics_hook_hans_wake",
                "后台应用唤醒"));
        entries.add(system(
                HookTargetIds.ALARM_WAKE_DELIVERY,
                "diagnostics_hook_alarm_wake",
                "定时唤醒"));
        entries.add(system(
                HookTargetIds.HANS_R_TO_M_TIMING,
                "diagnostics_hook_hans_freeze_timing",
                "冻结准备时机"));
        entries.add(system(
                HookTargetIds.HANS_M_TO_F_TIMING,
                "diagnostics_hook_hans_refreeze",
                "再次冻结执行"));
        entries.add(system(
                HookTargetIds.HANS_CORE_EXEMPTION,
                "diagnostics_hook_hans_core_exemption",
                "完整冻结豁免"));
        entries.add(system(
                HookTargetIds.HANS_LCD_ON_EXEMPTION,
                "diagnostics_hook_hans_lcd_exemption",
                "亮屏冻结豁免"));
        entries.add(system(
                HookTargetIds.HANS_RESOURCE_DISPATCH,
                "diagnostics_hook_hans_resource",
                "后台资源保留"));
        entries.add(system(
                HookTargetIds.HANS_ACTIVE_RECOVERY,
                "diagnostics_hook_hans_recovery",
                "后台状态恢复"));

        LinkedHashMap<String, Entry> byTarget = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (byTarget.put(entry.hookTargetId, entry) != null) {
                throw new IllegalStateException("duplicate Hook target: " + entry.hookTargetId);
            }
        }
        Set<String> expected = new LinkedHashSet<>(HookTargetIds.all());
        if (!expected.equals(byTarget.keySet())) {
            throw new IllegalStateException("diagnostics Hook catalog is incomplete");
        }
        ALL = Collections.unmodifiableList(entries);
        BY_TARGET = Collections.unmodifiableMap(byTarget);
    }

    private DiagnosticsHookCatalog() {}

    public static List<Entry> all() {
        return ALL;
    }

    public static Entry find(String hookTargetId) {
        return hookTargetId == null ? null : BY_TARGET.get(hookTargetId);
    }

    private static Entry athena(String target, String titleKey, String title) {
        return new Entry(target, PROCESS_ATHENA, titleKey, title);
    }

    private static Entry system(String target, String titleKey, String title) {
        return new Entry(target, PROCESS_SYSTEM_SERVER, titleKey, title);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
