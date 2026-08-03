package com.frostwork.bgenhance.diagnostics;

import java.util.Objects;

/** Stable user-event identity that deliberately excludes Hook and transport metadata. */
public final class DiagnosticsEventSemanticKey {
    private final int uid;
    private final String packageName;
    private final String behavior;
    private final String source;
    private final String decision;
    private final String result;

    private DiagnosticsEventSemanticKey(NormalizedDiagnosticsEvent event) {
        uid = event.uid;
        packageName = event.packageName;
        behavior = event.behavior;
        source = event.source;
        decision = event.decision;
        result = event.result;
    }

    /**
     * Returns {@code null} when an event lacks a stable target identity. Such events must be
     * recorded fail-open instead of risking an unrelated semantic collision.
     */
    public static DiagnosticsEventSemanticKey from(NormalizedDiagnosticsEvent event) {
        if (event == null || !event.isStorable()) return null;
        if (event.uid < 0 && event.packageName.isEmpty()) return null;
        return new DiagnosticsEventSemanticKey(event);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DiagnosticsEventSemanticKey)) return false;
        DiagnosticsEventSemanticKey that = (DiagnosticsEventSemanticKey) other;
        return uid == that.uid
                && packageName.equals(that.packageName)
                && behavior.equals(that.behavior)
                && source.equals(that.source)
                && decision.equals(that.decision)
                && result.equals(that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, packageName, behavior, source, decision, result);
    }
}
