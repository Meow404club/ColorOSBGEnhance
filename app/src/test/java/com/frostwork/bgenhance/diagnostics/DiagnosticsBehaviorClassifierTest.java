package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.FeatureIds;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public final class DiagnosticsBehaviorClassifierTest {
    @Test
    public void allTwentyFourFeaturesMapToTheSixKnownBehaviors() {
        assertEquals(24, FeatureIds.all().size());
        assertEquals(24, new LinkedHashSet<>(FeatureIds.all()).size());
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
}
