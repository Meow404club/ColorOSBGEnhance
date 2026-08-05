package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.FreezeSource;
import org.junit.Test;

public final class HansSourceClassifierTest {
    @Test
    public void classifiesNormalAndSuperModeForEmptyMarker() {
        assertResolved(FreezeSource.NORMAL, HansSourceClassifier.classify("", false));
        assertResolved(FreezeSource.SUPER, HansSourceClassifier.classify("", true));
    }

    @Test
    public void classifiesKnownFirmwareMarkers() {
        assertResolved(FreezeSource.FAST, HansSourceClassifier.classify("enterFF", false));
        assertResolved(FreezeSource.PRELOAD, HansSourceClassifier.classify("Preload_F", false));
        assertResolved(FreezeSource.SUPER, HansSourceClassifier.classify("Super_F", true));
    }

    @Test
    public void classifiesManagerFallbackMarkersWithoutObjectMode() {
        assertResolved(FreezeSource.NORMAL, HansSourceClassifier.classifyManager(""));
        assertResolved(FreezeSource.FAST, HansSourceClassifier.classifyManager("enterFF"));
        assertResolved(FreezeSource.PRELOAD, HansSourceClassifier.classifyManager("Preload_F"));
        assertResolved(FreezeSource.SUPER, HansSourceClassifier.classifyManager("Super_F"));
        assertFailure(
                HansSourceClassifier.Failure.UNKNOWN_SOURCE,
                HansSourceClassifier.classifyManager("future_freeze_source"));
        assertFailure(
                HansSourceClassifier.Failure.NULL_SOURCE,
                HansSourceClassifier.classifyManager(null));
    }

    @Test
    public void failsOpenWhenSuperFlagConflictsWithFastOrPreloadMarker() {
        assertFailure(
                HansSourceClassifier.Failure.CONFLICTING_SOURCE,
                HansSourceClassifier.classify("enterFF", true));
        assertFailure(
                HansSourceClassifier.Failure.CONFLICTING_SOURCE,
                HansSourceClassifier.classify("Preload_F", true));
        assertFailure(
                HansSourceClassifier.Failure.CONFLICTING_SOURCE,
                HansSourceClassifier.classify("Super_F", false));
    }

    @Test
    public void failsOpenForUnknownOrNullMarkers() {
        assertFailure(
                HansSourceClassifier.Failure.UNKNOWN_SOURCE,
                HansSourceClassifier.classify("future_freeze_source", false));
        assertFailure(
                HansSourceClassifier.Failure.NULL_SOURCE,
                HansSourceClassifier.classify(null, false));
    }

    @Test
    public void matchesFirmwareMarkersExactly() {
        assertFailure(
                HansSourceClassifier.Failure.UNKNOWN_SOURCE,
                HansSourceClassifier.classify(" enterFF", false));
        assertFailure(
                HansSourceClassifier.Failure.UNKNOWN_SOURCE,
                HansSourceClassifier.classify("enterff", false));
        assertFailure(
                HansSourceClassifier.Failure.UNKNOWN_SOURCE,
                HansSourceClassifier.classify(" ", true));
    }

    private static void assertResolved(
            FreezeSource expected,
            HansSourceClassifier.Resolution resolution) {
        assertTrue(resolution.isResolved());
        assertEquals(expected, resolution.sourceOrNull());
        assertNull(resolution.failureOrNull());
    }

    private static void assertFailure(
            HansSourceClassifier.Failure expected,
            HansSourceClassifier.Resolution resolution) {
        assertFalse(resolution.isResolved());
        assertNull(resolution.sourceOrNull());
        assertEquals(expected, resolution.failureOrNull());
    }
}
