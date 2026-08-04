package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.FeatureIds;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class DiagnosticsBehaviorClassifierTest {
    @Test
    public void allTwentyFiveFeaturesMapToTheSixKnownBehaviors() {
        assertEquals(25, FeatureIds.all().size());
        assertEquals(25, new LinkedHashSet<>(FeatureIds.all()).size());
        Set<String> mappedBehaviors = new LinkedHashSet<>();

        for (String featureId : FeatureIds.all()) {
            String behavior = DiagnosticsBehaviorClassifier.forFeatureId(featureId);
            assertNotEquals(featureId, DiagnosticsBehaviorClassifier.UNCLASSIFIED, behavior);
            assertTrue(featureId,
                    DiagnosticsBehaviorClassifier.knownBehaviors().contains(behavior));
            mappedBehaviors.add(behavior);
        }

        assertEquals(new LinkedHashSet<>(DiagnosticsBehaviorClassifier.knownBehaviors()),
                mappedBehaviors);
    }

    @Test
    public void unknownFeaturesAndStoredBehaviorsAreExplicitlyUnclassified() {
        assertEquals(DiagnosticsBehaviorClassifier.UNCLASSIFIED,
                DiagnosticsBehaviorClassifier.forFeatureId("future_feature"));
        assertEquals(DiagnosticsBehaviorClassifier.UNCLASSIFIED,
                DiagnosticsBehaviorClassifier.normalizeStoredBehavior("FUTURE_BEHAVIOR"));
    }

    @Test
    public void exactFeatureBehaviorContractIsExhaustive() {
        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        add(expected, DiagnosticsBehaviorClassifier.KILL,
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
        add(expected, DiagnosticsBehaviorClassifier.FREEZE,
                FeatureIds.HANS,
                FeatureIds.HANS_FREEZE_TIMING,
                FeatureIds.HANS_FREEZE_EXEMPTION,
                FeatureIds.SUPER_FREEZE);
        add(expected, DiagnosticsBehaviorClassifier.WAKE, FeatureIds.HANS_WAKE);
        add(expected, DiagnosticsBehaviorClassifier.RESOURCE, FeatureIds.HANS_RESOURCE);
        add(expected, DiagnosticsBehaviorClassifier.REFREEZE, FeatureIds.HANS_REFREEZE);
        add(expected, DiagnosticsBehaviorClassifier.RECOVERY, FeatureIds.HANS_RECOVERY);

        assertEquals(new LinkedHashSet<>(FeatureIds.all()), expected.keySet());
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(),
                    DiagnosticsBehaviorClassifier.forFeatureId(entry.getKey()));
        }
    }

    private static void add(
            Map<String, String> destination, String behavior, String... features) {
        for (String feature : features) destination.put(feature, behavior);
    }
}
