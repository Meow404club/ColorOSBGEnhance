package com.frostwork.bgenhance.ui.diagnostics;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable user-visible behavior families used by the diagnostics UI. */
public enum DiagnosticsBehavior {
    KILL("diagnostics_behavior_kill", "后台清理"),
    FREEZE("diagnostics_behavior_freeze", "后台冻结"),
    WAKE("diagnostics_behavior_wake", "后台唤醒"),
    RESOURCE("diagnostics_behavior_resource", "后台资源"),
    REFREEZE("diagnostics_behavior_refreeze", "再次冻结"),
    RECOVERY("diagnostics_behavior_recovery", "状态恢复");

    private static final List<DiagnosticsBehavior> ALL =
            Collections.unmodifiableList(Arrays.asList(values()));
    private static final Map<String, DiagnosticsBehavior> BY_WIRE_ID;

    static {
        LinkedHashMap<String, DiagnosticsBehavior> values = new LinkedHashMap<>();
        for (DiagnosticsBehavior behavior : ALL) {
            values.put(behavior.wireId, behavior);
        }
        BY_WIRE_ID = Collections.unmodifiableMap(values);
    }

    public final String wireId;
    public final String titleKey;
    public final String fallbackTitle;

    DiagnosticsBehavior(String titleKey, String fallbackTitle) {
        this.wireId = name();
        this.titleKey = titleKey;
        this.fallbackTitle = fallbackTitle;
    }

    public static List<DiagnosticsBehavior> all() {
        return ALL;
    }

    public static DiagnosticsBehavior fromWireId(String wireId) {
        return wireId == null ? null : BY_WIRE_ID.get(wireId);
    }
}
