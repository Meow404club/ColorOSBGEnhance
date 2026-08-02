package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class DiagnosticsUiMapperTest {
    private final DiagnosticsUiMapper mapper = new DiagnosticsUiMapper();

    @Test
    public void applicationRowsMergeUidsAndPreserveLongCounters() {
        DiagnosticsQuery.AggregateRow passed = row(
                "com.example.reader", 10_101, "KILL", DiagnosticsResult.PASSED,
                3_000_000_000L, 0L, 0L, 3_000_000_000L, 10L);
        DiagnosticsQuery.AggregateRow blocked = row(
                "com.example.reader", 110_101, "FREEZE", DiagnosticsResult.BLOCKED,
                3_000_000_001L, 3_000_000_001L, 0L, 3_000_000_001L, 20L);

        List<DiagnosticsUiMapper.ApplicationRow> rows =
                mapper.mapApplications(Arrays.asList(passed, blocked));

        assertEquals(1, rows.size());
        DiagnosticsUiMapper.ApplicationRow item = rows.get(0);
        assertEquals("application:com.example.reader", item.stableId);
        assertEquals(Arrays.asList(10_101, 110_101), item.observedUids);
        assertEquals(6_000_000_001L, item.totals.observed);
        assertEquals(3_000_000_001L, item.totals.blocked);
        assertEquals(3_000_000_000L, item.totals.passed);
        assertEquals(6_000_000_001L, item.totals.rawHits);
        assertEquals(3_000_000_001L,
                item.behavior(DiagnosticsBehavior.FREEZE).observed);
    }

    @Test
    public void behaviorRowsAlwaysContainTheSixStableFamilies() {
        List<DiagnosticsUiMapper.BehaviorRow> rows = mapper.mapBehaviors(Arrays.asList(
                row("com.example.one", 10_001, "KILL", DiagnosticsResult.BLOCKED,
                        4L, 4L, 0L, 4L, 1L),
                row("com.example.two", 10_002, "RECOVERY", DiagnosticsResult.RECOVERED,
                        2L, 0L, 0L, 2L, 2L),
                row("com.example.three", 10_003, "UNRECOGNIZED", DiagnosticsResult.PASSED,
                        100L, 0L, 0L, 100L, 3L)));

        assertEquals(DiagnosticsBehavior.all().size(), rows.size());
        for (int index = 0; index < DiagnosticsBehavior.all().size(); index++) {
            assertEquals(DiagnosticsBehavior.all().get(index), rows.get(index).behavior);
        }
        assertEquals(4L, rows.get(DiagnosticsBehavior.KILL.ordinal()).metrics.observed);
        assertEquals(2L, rows.get(DiagnosticsBehavior.RECOVERY.ordinal()).metrics.observed);
        assertEquals(0L, rows.get(DiagnosticsBehavior.RESOURCE.ordinal()).metrics.observed);
    }

    @Test
    public void hookRowsUseCatalogIdentityAndKeepRateNumeratorsAsLongs() {
        DiagnosticsHookState state = DiagnosticsHookState.builder()
                .processId(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER)
                .hookTargetId(HookTargetIds.HANS_FAST_FREEZE_DISPATCH)
                .featureId("hans_freeze")
                .installStatus("INSTALLED:entry=1")
                .bootId("boot")
                .lastHookAt(20L)
                .lastEventAt(19L)
                .rawHitCount(4_000_000_000L)
                .parsedCount(3_500_000_000L)
                .identityResolvedCount(3_000_000_000L)
                .configRevision(8)
                .policyRevision(9)
                .updatedAt(21L)
                .build();

        List<DiagnosticsUiMapper.HookTargetRow> rows = mapper.mapHookTargets(
                DiagnosticsDataSource.HookStatePage.success(Collections.singletonList(state)));

        assertEquals(DiagnosticsHookCatalog.all().size(), rows.size());
        DiagnosticsUiMapper.HookTargetRow target = findHook(
                rows, HookTargetIds.HANS_FAST_FREEZE_DISPATCH);
        assertTrue(target.reported);
        assertEquals(DiagnosticsUiMapper.HookStatus.INSTALLED, target.status);
        assertEquals(4_000_000_000L, target.rawHitCount);
        assertEquals(3_500_000_000L, target.parsedCount);
        assertEquals(3_000_000_000L, target.identityResolvedCount);
        assertTrue(target.countsConsistent);

        DiagnosticsUiMapper.HookTargetRow missing = findHook(rows, HookTargetIds.OGUARD_KILL);
        assertFalse(missing.reported);
        assertEquals(DiagnosticsUiMapper.HookStatus.MISSING, missing.status);
    }

    @Test(expected = IllegalArgumentException.class)
    public void failedHookPageCannotBeMappedAsAnEmptySuccess() {
        mapper.mapHookTargets(DiagnosticsDataSource.HookStatePage.failure(
                "database_unavailable"));
    }

    private static DiagnosticsUiMapper.HookTargetRow findHook(
            List<DiagnosticsUiMapper.HookTargetRow> rows, String hookTargetId) {
        for (DiagnosticsUiMapper.HookTargetRow row : rows) {
            if (hookTargetId.equals(row.hookTargetId)) return row;
        }
        throw new AssertionError("missing Hook row " + hookTargetId);
    }

    private static DiagnosticsQuery.AggregateRow row(
            String packageName,
            int uid,
            String behavior,
            String result,
            long eventCount,
            long blockedCount,
            long failOpenCount,
            long rawHitCount,
            long lastAt) {
        return new DiagnosticsQuery.AggregateRow(
                packageName,
                uid,
                "feature",
                "hook",
                behavior,
                "source",
                "decision",
                result,
                rawHitCount,
                eventCount,
                blockedCount,
                failOpenCount,
                eventCount,
                eventCount,
                eventCount,
                1L,
                lastAt);
    }
}
