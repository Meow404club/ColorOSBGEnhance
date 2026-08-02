package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.HookTargetIds;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public final class DiagnosticsHookCatalogTest {
    @Test
    public void catalogCoversEveryHookTargetExactlyOnce() {
        Set<String> expected = new LinkedHashSet<>(HookTargetIds.all());
        Set<String> actual = new LinkedHashSet<>();
        Set<String> stableIds = new LinkedHashSet<>();

        for (DiagnosticsHookCatalog.Entry entry : DiagnosticsHookCatalog.all()) {
            assertTrue(actual.add(entry.hookTargetId));
            assertTrue(stableIds.add(entry.stableId()));
            assertFalse(entry.expectedProcessId.isEmpty());
            assertFalse(entry.titleKey.isEmpty());
            assertFalse(entry.fallbackTitle.isEmpty());
            assertNotNull(DiagnosticsHookCatalog.find(entry.hookTargetId));
        }

        assertEquals(expected, actual);
        assertEquals(HookTargetIds.all().size(), DiagnosticsHookCatalog.all().size());
    }

    @Test
    public void processOwnershipMatchesInstalledHookProcesses() {
        Set<String> athenaTargets = new LinkedHashSet<>();
        athenaTargets.add(HookTargetIds.ATHENA_PERIODIC);
        athenaTargets.add(HookTargetIds.FAST_CLEAR_DISPATCH);
        athenaTargets.add(HookTargetIds.TOURNAMENT);
        athenaTargets.add(HookTargetIds.MEMORY_GUARD_DISPATCH);

        for (DiagnosticsHookCatalog.Entry entry : DiagnosticsHookCatalog.all()) {
            String expectedProcess = athenaTargets.contains(entry.hookTargetId)
                    ? DiagnosticsHookCatalog.PROCESS_ATHENA
                    : DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER;
            assertEquals(expectedProcess, entry.expectedProcessId);
        }
    }
}
