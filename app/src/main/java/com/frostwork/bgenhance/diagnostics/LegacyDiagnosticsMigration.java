package com.frostwork.bgenhance.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import com.frostwork.bgenhance.FeatureIds;
import java.util.Map;

/** Reads the former SharedPreferences counters for one idempotent database migration. */
final class LegacyDiagnosticsMigration {
    static final String PREFERENCES_NAME = "hook_diagnostics";
    private static final long MAX_LEGACY_COUNT = Integer.MAX_VALUE;

    private LegacyDiagnosticsMigration() {}

    static DiagnosticsWriteBatch read(Context context) {
        DiagnosticsWriteBatch.Builder out = DiagnosticsWriteBatch.builder()
                .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY);
        if (context == null) return out.build();
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        Map<String, ?> values = preferences.getAll();
        for (String featureId : FeatureIds.all()) {
            String prefix = "stats." + featureId + ".";
            long observed = count(values.get(prefix + "observed"));
            long blocked = Math.min(observed, count(values.get(prefix + "blocked")));
            long failOpen = Math.min(
                    observed - blocked, count(values.get(prefix + "fail_open")));
            DiagnosticsWriteBatch.LegacyFeatureDelta delta =
                    new DiagnosticsWriteBatch.LegacyFeatureDelta(
                            featureId,
                            observed,
                            blocked,
                            failOpen,
                            timestamp(values.get(prefix + "last_at")),
                            string(values.get(prefix + "last_pkg")),
                            string(values.get(prefix + "last_process")),
                            string(values.get(prefix + "last_scene")),
                            string(values.get(prefix + "last_reason")),
                            string(values.get(prefix + "last_result")));
            if (delta.isStorable()) out.addLegacyFeatureDelta(delta);
        }
        return out.build();
    }

    private static long count(Object value) {
        if (!(value instanceof Number)) return 0L;
        return Math.min(MAX_LEGACY_COUNT, Math.max(0L, ((Number) value).longValue()));
    }

    private static long timestamp(Object value) {
        if (!(value instanceof Number)) return 0L;
        long timestamp = Math.max(0L, ((Number) value).longValue());
        return Math.min(timestamp, System.currentTimeMillis() + 300_000L);
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : "";
    }
}
