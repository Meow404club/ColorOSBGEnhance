package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.diagnostics.DiagnosticsBehaviorClassifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable user-visible behavior families used by the diagnostics UI. */
public enum DiagnosticsBehavior {
    KILL(DiagnosticsBehaviorClassifier.KILL,
            "diagnostics_behavior_kill", "后台清理"),
    FREEZE(DiagnosticsBehaviorClassifier.FREEZE,
            "diagnostics_behavior_freeze", "后台冻结"),
    WAKE(DiagnosticsBehaviorClassifier.WAKE,
            "diagnostics_behavior_wake", "后台唤醒"),
    RESOURCE(DiagnosticsBehaviorClassifier.RESOURCE,
            "diagnostics_behavior_resource", "后台资源"),
    REFREEZE(DiagnosticsBehaviorClassifier.REFREEZE,
            "diagnostics_behavior_refreeze", "再次冻结"),
    RECOVERY(DiagnosticsBehaviorClassifier.RECOVERY,
            "diagnostics_behavior_recovery", "状态恢复"),
    UNCLASSIFIED(DiagnosticsBehaviorClassifier.UNCLASSIFIED,
            "diagnostics_behavior_unclassified", "未分类");

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

    DiagnosticsBehavior(String wireId, String titleKey, String fallbackTitle) {
        this.wireId = wireId;
        this.titleKey = titleKey;
        this.fallbackTitle = fallbackTitle;
    }

    public static List<DiagnosticsBehavior> all() {
        return ALL;
    }

    public static DiagnosticsBehavior fromWireId(String wireId) {
        DiagnosticsBehavior behavior = wireId == null ? null : BY_WIRE_ID.get(wireId);
        return behavior == null ? UNCLASSIFIED : behavior;
    }
}
