package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsTargetIds;
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
            assertTrue(stableIds.add(entry.stableId()));
            assertFalse(entry.expectedProcessId.isEmpty());
            assertFalse(entry.titleKey.isEmpty());
            assertFalse(entry.fallbackTitle.isEmpty());
            assertNotNull(DiagnosticsHookCatalog.find(
                    entry.expectedProcessId, entry.hookTargetId));
            if (entry.kind == DiagnosticsHookCatalog.Kind.HOOK_TARGET) {
                assertTrue(actual.add(entry.hookTargetId));
                assertNotNull(DiagnosticsHookCatalog.find(entry.hookTargetId));
            }
        }

        assertEquals(expected, actual);
        assertEquals(HookTargetIds.all().size(), DiagnosticsHookCatalog.physicalHooks().size());
        assertEquals(4, DiagnosticsHookCatalog.runtimeRows().size());
        assertEquals(HookTargetIds.all().size() + 4, DiagnosticsHookCatalog.all().size());
    }

    @Test
    public void processOwnershipMatchesInstalledHookProcesses() {
        Set<String> athenaTargets = new LinkedHashSet<>();
        athenaTargets.add(HookTargetIds.ATHENA_PERIODIC);
        athenaTargets.add(HookTargetIds.FAST_CLEAR_DISPATCH);
        athenaTargets.add(HookTargetIds.TOURNAMENT);
        athenaTargets.add(HookTargetIds.MEMORY_GUARD_DISPATCH);

        for (DiagnosticsHookCatalog.Entry entry : DiagnosticsHookCatalog.physicalHooks()) {
            String expectedProcess = athenaTargets.contains(entry.hookTargetId)
                    ? DiagnosticsHookCatalog.PROCESS_ATHENA
                    : DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER;
            assertEquals(expectedProcess, entry.expectedProcessId);
            assertEquals(expectedProcess,
                    HookTargetIds.expectedProcessId(entry.hookTargetId));
        }
        assertEquals("", HookTargetIds.expectedProcessId("unknown_target"));
    }

    @Test
    public void runtimeRowsCoverProcessAndConfigForBothProcesses() {
        Set<String> expected = new LinkedHashSet<>();
        expected.add(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER
                + ":" + DiagnosticsTargetIds.RUNTIME_PROCESS);
        expected.add(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER
                + ":" + DiagnosticsTargetIds.RUNTIME_CONFIG);
        expected.add(DiagnosticsHookCatalog.PROCESS_ATHENA
                + ":" + DiagnosticsTargetIds.RUNTIME_PROCESS);
        expected.add(DiagnosticsHookCatalog.PROCESS_ATHENA
                + ":" + DiagnosticsTargetIds.RUNTIME_CONFIG);

        Set<String> actual = new LinkedHashSet<>();
        for (DiagnosticsHookCatalog.Entry entry : DiagnosticsHookCatalog.runtimeRows()) {
            assertTrue(entry.isRuntime());
            actual.add(entry.expectedProcessId + ":" + entry.hookTargetId);
        }

        assertEquals(expected, actual);
    }
}
