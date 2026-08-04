package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.BuildConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
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

        List<DiagnosticsUiMapper.ApplicationRow> rows = mapper.mapApplications(
                Collections.singletonList(new DiagnosticsDataSource.ManagedApplication(
                        "com.example.reader", "Reader")),
                Arrays.asList(passed, blocked));

        assertEquals(1, rows.size());
        DiagnosticsUiMapper.ApplicationRow item = rows.get(0);
        assertEquals("application:com.example.reader", item.stableId);
        assertEquals("Reader", item.displayName);
        assertEquals(Arrays.asList(10_101, 110_101), item.observedUids);
        assertEquals(6_000_000_001L, item.totals.observed);
        assertEquals(3_000_000_001L, item.totals.blocked);
        assertEquals(3_000_000_000L, item.totals.passed);
        assertEquals(6_000_000_001L, item.totals.rawHits);
        assertEquals(3_000_000_001L,
                item.behavior(DiagnosticsBehavior.FREEZE).observed);
    }

    @Test
    public void managedApplicationsKeepZeroRowsWithoutDroppingHistoricalOrUnknownEvents() {
        List<DiagnosticsUiMapper.ApplicationRow> rows = mapper.mapApplications(
                Arrays.asList(
                        new DiagnosticsDataSource.ManagedApplication(
                                "com.example.zero", "Zero Events"),
                        new DiagnosticsDataSource.ManagedApplication(
                                "com.example.active", "Active App")),
                Arrays.asList(
                        row("com.example.active", 10_001, "WAKE", DiagnosticsResult.PASSED,
                                3L, 0L, 0L, 3L, 2L),
                        row("com.example.unmanaged", 10_002, "KILL", DiagnosticsResult.BLOCKED,
                                9L, 9L, 0L, 9L, 3L),
                        row("", -1, "KILL", DiagnosticsResult.OBSERVED,
                                4L, 0L, 0L, 4L, 4L)));

        assertEquals(4, rows.size());
        assertEquals("com.example.zero", rows.get(0).packageName);
        assertEquals("Zero Events", rows.get(0).displayName);
        assertTrue(rows.get(0).totals.isEmpty());
        assertTrue(rows.get(0).observedUids.isEmpty());
        assertEquals("com.example.active", rows.get(1).packageName);
        assertEquals(3L, rows.get(1).totals.observed);
        assertEquals("com.example.unmanaged", rows.get(2).packageName);
        assertEquals(9L, rows.get(2).totals.observed);
        assertTrue(rows.get(3).unidentified);
        assertEquals("application:@unidentified", rows.get(3).stableId);
        assertEquals(4L, rows.get(3).totals.observedOnly);
    }

    @Test
    public void resultCategoriesDoNotMislabelRecoveryAsNormalPassThrough() {
        List<DiagnosticsUiMapper.BehaviorRow> rows = mapper.mapBehaviors(Arrays.asList(
                row("", -1, "RECOVERY", DiagnosticsResult.PASSED,
                        1L, 0L, 0L, 1L, 1L),
                row("", -1, "RECOVERY", DiagnosticsResult.RECOVERED,
                        2L, 0L, 0L, 2L, 2L),
                row("", -1, "RECOVERY", DiagnosticsResult.NO_ACTION_NEEDED,
                        3L, 0L, 0L, 3L, 3L),
                row("", -1, "RECOVERY", DiagnosticsResult.SAFETY_ABORTED,
                        4L, 0L, 0L, 4L, 4L),
                row("", -1, "RECOVERY", "future_result",
                        5L, 0L, 0L, 5L, 5L)));

        DiagnosticsUiMapper.Metrics metrics =
                rows.get(DiagnosticsBehavior.RECOVERY.ordinal()).metrics;
        assertEquals(1L, metrics.passed);
        assertEquals(2L, metrics.recovered);
        assertEquals(3L, metrics.noActionNeeded);
        assertEquals(4L, metrics.safetyAborted);
        assertEquals(5L, metrics.unknownResult);
    }

    @Test
    public void behaviorRowsContainKnownFamiliesAndPreserveUnknownCounts() {
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
        assertEquals(100L,
                rows.get(DiagnosticsBehavior.UNCLASSIFIED.ordinal()).metrics.observed);
    }

    @Test
    public void sharedSnapshotTotalsEqualTheSumOfEveryBehaviorRow() {
        RecentBehaviorStats snapshot = RecentBehaviorStats.fromAggregateRows(
                        Arrays.asList(
                                row("", -1, "KILL", DiagnosticsResult.BLOCKED,
                                        5L, 5L, 0L, 5L, 1L),
                                row("", -1, "FUTURE_BEHAVIOR", DiagnosticsResult.FAIL_OPEN,
                                        3L, 0L, 3L, 3L, 2L)));

        long observed = 0L;
        long blocked = 0L;
        long failOpen = 0L;
        for (DiagnosticsUiMapper.BehaviorRow row : mapper.mapBehaviors(snapshot)) {
            observed += row.metrics.observed;
            blocked += row.metrics.blocked;
            failOpen += row.metrics.failOpen;
        }

        assertEquals(snapshot.observed, observed);
        assertEquals(snapshot.blocked, blocked);
        assertEquals(snapshot.failOpen, failOpen);
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

    @Test
    public void currentBootFilteringKeepsRuntimeRowsAndRejectsOldState() {
        List<DiagnosticsHookState> states = Arrays.asList(
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
                        "old_boot", "INSTALLED:old", 100L),
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
                        "current_boot", "INSTALLED:current", 5L),
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        HookTargetIds.OGUARD_KILL,
                        "old_boot", "INSTALLED:old", 200L),
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_PROCESS,
                        "current_boot", currentStage(), 7L),
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_CONFIG,
                        "current_boot", "CONFIG_READY", 8L),
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_IDENTITY,
                        "current_boot", "IDENTITY_READY", 8L),
                hookState(DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_PROCESS,
                        "current_boot", currentStage(), 9L),
                hookState(DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_CONFIG,
                        "current_boot", "CONFIG_READY", 10L),
                hookState(DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_IDENTITY,
                        "current_boot", "IDENTITY_READY", 10L));

        List<DiagnosticsUiMapper.HookTargetRow> rows = mapper.mapHookTargets(
                DiagnosticsDataSource.HookStatePage.success(states), "current_boot");

        DiagnosticsUiMapper.HookTargetRow current = findHook(
                rows,
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                HookTargetIds.HANS_FAST_FREEZE_DISPATCH);
        assertTrue(current.reported);
        assertEquals(5L, current.rawHitCount);
        assertEquals("current_boot", current.bootId);
        assertFalse(findHook(rows,
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                HookTargetIds.OGUARD_KILL).reported);

        for (DiagnosticsHookCatalog.Entry runtime : DiagnosticsHookCatalog.runtimeRows()) {
            DiagnosticsUiMapper.HookTargetRow row = findHook(
                    rows, runtime.expectedProcessId, runtime.hookTargetId);
            assertTrue(row.reported);
            assertEquals(DiagnosticsUiMapper.HookStatus.READY, row.status);
        }
    }

    @Test
    public void identityRuntimeDistinguishesReadyPendingAndFailure() {
        List<DiagnosticsHookState> states = Arrays.asList(
                hookState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_IDENTITY,
                        "current_boot", "PENDING_IDENTITY_REBUILD", 1L),
                hookState(DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_IDENTITY,
                        "current_boot", "FAILED_FAIL_OPEN_IDENTITY_REBUILD", 2L));

        List<DiagnosticsUiMapper.HookTargetRow> rows = mapper.mapHookTargets(
                DiagnosticsDataSource.HookStatePage.success(states), "current_boot");

        assertEquals(DiagnosticsUiMapper.HookStatus.PENDING,
                findHook(rows, DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_IDENTITY).status);
        assertEquals(DiagnosticsUiMapper.HookStatus.FAILED,
                findHook(rows, DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_IDENTITY).status);
    }

    @Test
    public void identityReadyRequiresCurrentConfigAndPolicyRevisions() {
        DiagnosticsUiMapper currentMapper = new DiagnosticsUiMapper(() -> 8, () -> 4L);
        DiagnosticsHookState stale = DiagnosticsHookState.builder()
                .processId(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_IDENTITY)
                .installStatus("IDENTITY_READY")
                .configRevision(8)
                .policyRevision(3)
                .bootId("current_boot")
                .build();

        DiagnosticsUiMapper.HookTargetRow row = findHook(
                currentMapper.mapHookTargets(
                        DiagnosticsDataSource.HookStatePage.success(
                                Collections.singletonList(stale)),
                        "current_boot"),
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                DiagnosticsTargetIds.RUNTIME_IDENTITY);
        assertEquals(DiagnosticsUiMapper.HookStatus.PENDING, row.status);

        DiagnosticsHookState current = DiagnosticsHookState.builder()
                .processId(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_IDENTITY)
                .installStatus("IDENTITY_READY")
                .configRevision(8)
                .policyRevision(4)
                .bootId("current_boot")
                .build();
        row = findHook(
                currentMapper.mapHookTargets(
                        DiagnosticsDataSource.HookStatePage.success(
                                Collections.singletonList(current)),
                        "current_boot"),
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                DiagnosticsTargetIds.RUNTIME_IDENTITY);
        assertEquals(DiagnosticsUiMapper.HookStatus.READY, row.status);
    }

    @Test
    public void configStatusRequiresTheCurrentLocalRevisionAndErrorsTakePriority() {
        DiagnosticsUiMapper revisionEightMapper = new DiagnosticsUiMapper(() -> 8);
        List<DiagnosticsHookState> states = Arrays.asList(
                configState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        "CONFIG_READY", 7),
                configState(DiagnosticsHookCatalog.PROCESS_ATHENA,
                        "CONFIG_READY", 8));

        List<DiagnosticsUiMapper.HookTargetRow> rows = revisionEightMapper.mapHookTargets(
                DiagnosticsDataSource.HookStatePage.success(states), "current_boot");

        assertEquals(DiagnosticsUiMapper.HookStatus.PENDING,
                findHook(rows, DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_CONFIG).status);
        assertEquals(DiagnosticsUiMapper.HookStatus.READY,
                findHook(rows, DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_CONFIG).status);

        rows = revisionEightMapper.mapHookTargets(
                DiagnosticsDataSource.HookStatePage.success(Arrays.asList(
                        configState(DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                                "CONFIG_READY", 9),
                        configState(DiagnosticsHookCatalog.PROCESS_ATHENA,
                                "CONFIG_ERROR", 9))),
                "current_boot");

        assertEquals(DiagnosticsUiMapper.HookStatus.READY,
                findHook(rows, DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                        DiagnosticsTargetIds.RUNTIME_CONFIG).status);
        assertEquals(DiagnosticsUiMapper.HookStatus.FAILED,
                findHook(rows, DiagnosticsHookCatalog.PROCESS_ATHENA,
                        DiagnosticsTargetIds.RUNTIME_CONFIG).status);
    }

    @Test
    public void processEntryBeforeFeatureInstallationRemainsPending() {
        DiagnosticsHookState entry = hookState(
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                DiagnosticsTargetIds.RUNTIME_PROCESS,
                "current_boot",
                "ENTRY_LOADED",
                0L);

        DiagnosticsUiMapper.HookTargetRow row = findHook(
                mapper.mapHookTargets(
                        DiagnosticsDataSource.HookStatePage.success(
                                Collections.singletonList(entry)),
                        "current_boot"),
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                DiagnosticsTargetIds.RUNTIME_PROCESS);

        assertEquals(DiagnosticsUiMapper.HookStatus.PENDING, row.status);
    }

    @Test
    public void runtimeFromAnotherModuleVersionRemainsPending() {
        DiagnosticsHookState stale = hookState(
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                DiagnosticsTargetIds.RUNTIME_PROCESS,
                "current_boot",
                ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE + 1),
                0L);

        DiagnosticsUiMapper.HookTargetRow row = findHook(
                mapper.mapHookTargets(
                        DiagnosticsDataSource.HookStatePage.success(
                                Collections.singletonList(stale)),
                        "current_boot"),
                DiagnosticsHookCatalog.PROCESS_SYSTEM_SERVER,
                DiagnosticsTargetIds.RUNTIME_PROCESS);

        assertEquals(DiagnosticsUiMapper.HookStatus.PENDING, row.status);
    }

    private static String currentStage() {
        return ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE);
    }

    private static DiagnosticsUiMapper.HookTargetRow findHook(
            List<DiagnosticsUiMapper.HookTargetRow> rows, String hookTargetId) {
        for (DiagnosticsUiMapper.HookTargetRow row : rows) {
            if (hookTargetId.equals(row.hookTargetId)) return row;
        }
        throw new AssertionError("missing Hook row " + hookTargetId);
    }

    private static DiagnosticsUiMapper.HookTargetRow findHook(
            List<DiagnosticsUiMapper.HookTargetRow> rows,
            String processId,
            String hookTargetId) {
        for (DiagnosticsUiMapper.HookTargetRow row : rows) {
            if (processId.equals(row.expectedProcessId)
                    && hookTargetId.equals(row.hookTargetId)) return row;
        }
        throw new AssertionError("missing Hook row " + processId + ":" + hookTargetId);
    }

    private static DiagnosticsHookState hookState(
            String processId,
            String hookTargetId,
            String bootId,
            String status,
            long count) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(hookTargetId)
                .installStatus(status)
                .bootId(bootId)
                .rawHitCount(count)
                .parsedCount(count)
                .identityResolvedCount(count)
                .updatedAt(count)
                .build();
    }

    private static DiagnosticsHookState configState(
            String processId, String status, int revision) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus(status)
                .configRevision(revision)
                .bootId("current_boot")
                .build();
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
