package com.frostwork.bgenhance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ModuleApplicationSyncTest {
    @Test
    public void connectedSyncErrorRetriesEvenWhenRevisionAlreadyMatches() {
        assertTrue(ModuleApplication.shouldRetryConfigSync(true, 8, 8, "commit_failed"));
    }

    @Test
    public void staleRevisionRetriesWithoutAnExplicitError() {
        assertTrue(ModuleApplication.shouldRetryConfigSync(true, 7, 8, ""));
    }

    @Test
    public void successfulCurrentRevisionDoesNotRetry() {
        assertFalse(ModuleApplication.shouldRetryConfigSync(true, 8, 8, ""));
        assertFalse(ModuleApplication.shouldRetryConfigSync(true, 9, 8, null));
    }

    @Test
    public void disconnectedServiceNeverSchedulesSyncWork() {
        assertFalse(ModuleApplication.shouldRetryConfigSync(false, 7, 8, "disconnected"));
    }
}
