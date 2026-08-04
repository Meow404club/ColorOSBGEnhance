package com.frostwork.bgenhance.diagnostics;

import com.frostwork.bgenhance.FeatureIds;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable mapping between Hook features and user-visible diagnostics behavior families. */
public final class DiagnosticsBehaviorClassifier {
    public static final String KILL = "KILL";
    public static final String FREEZE = "FREEZE";
    public static final String WAKE = "WAKE";
    public static final String RESOURCE = "RESOURCE";
    public static final String REFREEZE = "REFREEZE";
    public static final String RECOVERY = "RECOVERY";
    public static final String UNCLASSIFIED = "UNCLASSIFIED";

    private static final List<String> KNOWN_BEHAVIORS = Collections.unmodifiableList(
            Arrays.asList(KILL, FREEZE, WAKE, RESOURCE, REFREEZE, RECOVERY));
    private static final Set<String> KNOWN_BEHAVIOR_SET = Collections.unmodifiableSet(
            new LinkedHashSet<>(KNOWN_BEHAVIORS));
    private static final Map<String, String> FEATURE_BEHAVIORS;

    static {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        register(values, KILL,
                FeatureIds.ATHENA_PERIODIC,
                FeatureIds.OGUARD_KILL,
                FeatureIds.OGUARD_FORCE_STOP,
                FeatureIds.FAST_CAMERA_START,
                FeatureIds.FAST_CAMERA_OPERATION,
                FeatureIds.FAST_GAME_START,
                FeatureIds.FAST_AI,
                FeatureIds.FAST_CLEAR_OTHER,
                FeatureIds.TOURNAMENT,
                FeatureIds.MEMORY_FORCE,
                FeatureIds.MEMORY_OVER,
                FeatureIds.MEMORY_GUARD_OTHER,
                FeatureIds.ATHENA_OTHER,
                FeatureIds.NIRVANA,
                FeatureIds.CAMERA_LOW_FREE,
                FeatureIds.GAME_LOW_FREE,
                FeatureIds.OSENSE_PER_PROCESS);
        register(values, FREEZE,
                FeatureIds.HANS,
                FeatureIds.HANS_FREEZE_TIMING,
                FeatureIds.HANS_FREEZE_EXEMPTION,
                FeatureIds.SUPER_FREEZE);
        register(values, WAKE, FeatureIds.HANS_WAKE);
        register(values, RESOURCE, FeatureIds.HANS_RESOURCE);
        register(values, REFREEZE, FeatureIds.HANS_REFREEZE);
        register(values, RECOVERY, FeatureIds.HANS_RECOVERY);
        FEATURE_BEHAVIORS = Collections.unmodifiableMap(values);
    }

    private DiagnosticsBehaviorClassifier() {}

    public static List<String> knownBehaviors() {
        return KNOWN_BEHAVIORS;
    }

    public static String forFeatureId(String featureId) {
        String behavior = FEATURE_BEHAVIORS.get(DiagnosticsValues.id(featureId));
        return behavior == null ? UNCLASSIFIED : behavior;
    }

    public static String normalizeStoredBehavior(String behavior) {
        String normalized = DiagnosticsValues.id(behavior);
        return KNOWN_BEHAVIOR_SET.contains(normalized) ? normalized : UNCLASSIFIED;
    }

    private static void register(
            Map<String, String> values, String behavior, String... featureIds) {
        for (String featureId : featureIds) {
            String previous = values.put(featureId, behavior);
            if (previous != null) {
                throw new IllegalStateException("duplicate diagnostics feature mapping: " + featureId);
            }
        }
    }
}
