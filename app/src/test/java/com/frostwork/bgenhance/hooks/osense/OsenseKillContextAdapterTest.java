package com.frostwork.bgenhance.hooks.osense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.decision.KillContext;
import org.junit.Test;

public final class OsenseKillContextAdapterTest {
    @Test
    public void mapsKnownScenesToTheirExistingFeatureGates() {
        assertSpecific(3, "NirvanaLowFree", 1026,
                FeatureIds.NIRVANA, ModuleSettings.KEY_NIRVANA);
        assertSpecific(3, "CamLowFree", 1028,
                FeatureIds.CAMERA_LOW_FREE, ModuleSettings.KEY_CAMERA_LOW_FREE);
        assertSpecific(3, "GameLowFree", 1029,
                FeatureIds.GAME_LOW_FREE, ModuleSettings.KEY_GAME_LOW_FREE);
        assertSpecific(6, "CamLowFree", 1028,
                FeatureIds.CAMERA_LOW_FREE, ModuleSettings.KEY_CAMERA_LOW_FREE);
        assertSpecific(7, "GameLowFree", 1029,
                FeatureIds.GAME_LOW_FREE, ModuleSettings.KEY_GAME_LOW_FREE);
    }

    @Test
    public void mapsCachedCleanWithinAKnownSceneToThatScene() {
        OsenseKillContextAdapter.Resolution resolution =
                OsenseKillContextAdapter.resolve(
                        new ContextFixture(3, "NirvanaLowFree"), 1024);

        assertTrue(resolution.isResolved());
        assertEquals(FeatureIds.NIRVANA, resolution.mappingOrNull().featureId());
        assertEquals(1024, resolution.mappingOrNull().code());
    }

    @Test
    public void mapsOtherKnownContextTypesToGenericPerProcessProtection() {
        OsenseKillContextAdapter.Resolution resolution =
                OsenseKillContextAdapter.resolve(new ContextFixture(1, null), 1024);

        assertTrue(resolution.isResolved());
        OsenseKillContextAdapter.Mapping mapping = resolution.mappingOrNull();
        assertEquals(FeatureIds.OSENSE_PER_PROCESS, mapping.featureId());
        assertEquals(KillContext.Path.OSENSE_PER_PROCESS, mapping.path());
        assertFalse(mapping.hasFeatureGate());
    }

    @Test
    public void failsOpenForConflictingOrUnknownContextEvidence() {
        assertFailure(OsenseKillContextAdapter.Failure.CONTEXT_MISMATCH,
                new ContextFixture(3, "CamLowFree"), 1026);
        assertFailure(OsenseKillContextAdapter.Failure.CONTEXT_MISMATCH,
                new ContextFixture(1, "LowMem"), 1028);
        assertFailure(OsenseKillContextAdapter.Failure.CONTEXT_MISMATCH,
                new ContextFixture(1, "CamLowFree"), 1024);
        assertFailure(OsenseKillContextAdapter.Failure.INVALID_CONTEXT_TYPE,
                new ContextFixture(10, "future"), 1024);
        assertFailure(OsenseKillContextAdapter.Failure.ACCESSOR_MISSING,
                new MissingSceneFixture(), 1024);
    }

    @Test
    public void prewarmsInheritedContextAccessors() {
        assertTrue(OsenseKillContextAdapter.prepare(ContextFixture.class));
        assertTrue(OsenseKillContextAdapter.resolve(
                new InheritedContextFixture(3, "CamLowFree"), 1028).isResolved());
    }

    private static void assertSpecific(
            int contextType,
            String scene,
            int subReason,
            String featureId,
            String featureKey) {
        OsenseKillContextAdapter.Resolution resolution =
                OsenseKillContextAdapter.resolve(
                        new ContextFixture(contextType, scene), subReason);

        assertTrue(resolution.isResolved());
        OsenseKillContextAdapter.Mapping mapping = resolution.mappingOrNull();
        assertEquals(featureId, mapping.featureId());
        assertEquals(featureKey, mapping.featureKey());
        assertEquals(KillContext.Path.NIRVANA, mapping.path());
        assertEquals(scene, mapping.scene());
    }

    private static void assertFailure(
            OsenseKillContextAdapter.Failure failure,
            Object context,
            int subReason) {
        OsenseKillContextAdapter.Resolution resolution =
                OsenseKillContextAdapter.resolve(context, subReason);
        assertFalse(resolution.isResolved());
        assertEquals(failure, resolution.failureOrNull());
    }

    private static class ContextFixture {
        private final int contextType;
        private final String scene;

        ContextFixture(int contextType, String scene) {
            this.contextType = contextType;
            this.scene = scene;
        }
    }

    private static final class InheritedContextFixture extends ContextFixture {
        InheritedContextFixture(int contextType, String scene) {
            super(contextType, scene);
        }
    }

    private static final class MissingSceneFixture {
        private final int contextType = 1;
    }
}
