package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStats;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class DiagnosticsBehaviorDetailMapperTest {
    private final DiagnosticsUiMapper mapper = new DiagnosticsUiMapper();

    @Test
    public void catalogExposesEveryConcretePathInStableOrder() {
        EnumMap<DiagnosticsBehavior, Integer> expected = new EnumMap<>(
                DiagnosticsBehavior.class);
        expected.put(DiagnosticsBehavior.KILL, 17);
        expected.put(DiagnosticsBehavior.FREEZE, 7);
        expected.put(DiagnosticsBehavior.WAKE, WakeSource.values().length - 1);
        expected.put(DiagnosticsBehavior.RESOURCE, ResourceType.values().length + 1);
        expected.put(DiagnosticsBehavior.REFREEZE, WakeSource.values().length - 1);
        expected.put(DiagnosticsBehavior.RECOVERY, 4);
        expected.put(DiagnosticsBehavior.UNCLASSIFIED, 0);

        Set<String> coveredFeatures = new LinkedHashSet<>();
        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            List<DiagnosticsBehaviorDetailCatalog.Entry> entries =
                    DiagnosticsBehaviorDetailCatalog.entriesFor(behavior);
            assertEquals(behavior.name(), expected.get(behavior).intValue(), entries.size());
            Set<String> stableIds = new LinkedHashSet<>();
            for (DiagnosticsBehaviorDetailCatalog.Entry entry : entries) {
                assertTrue(behavior.name(), stableIds.add(entry.stableId));
                coveredFeatures.addAll(entry.featureIds());
            }
        }
        assertEquals(new LinkedHashSet<>(FeatureIds.all()), coveredFeatures);
    }

    @Test
    public void allWakeResourceFreezeAndRecoveryPathsMapExactlyOnce() {
        EnumMap<DiagnosticsBehavior, List<DiagnosticsQuery.AggregateRow>> rows =
                new EnumMap<>(DiagnosticsBehavior.class);
        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            rows.put(behavior, new ArrayList<>());
        }
        for (String feature : FeatureIds.all()) {
            DiagnosticsBehavior behavior = DiagnosticsBehavior.fromWireId(
                    com.frostwork.bgenhance.diagnostics.DiagnosticsBehaviorClassifier
                            .forFeatureId(feature));
            if (behavior == DiagnosticsBehavior.KILL) {
                rows.get(behavior).add(row(feature, behavior, "", DiagnosticsResult.PASSED, 1L));
            }
        }
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.HANS, DiagnosticsBehavior.FREEZE,
                FreezeSource.NORMAL.name(), DiagnosticsResult.PASSED, 1L));
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.HANS, DiagnosticsBehavior.FREEZE,
                FreezeSource.FAST.name(), DiagnosticsResult.PASSED, 1L));
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.HANS, DiagnosticsBehavior.FREEZE,
                FreezeSource.PRELOAD.name(), DiagnosticsResult.PASSED, 1L));
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.SUPER_FREEZE, DiagnosticsBehavior.FREEZE,
                FreezeSource.SUPER.name(), DiagnosticsResult.PASSED, 1L));
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.HANS_FREEZE_TIMING, DiagnosticsBehavior.FREEZE,
                "R_TO_M", DiagnosticsResult.APPLIED, 1L));
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.HANS_FREEZE_TIMING, DiagnosticsBehavior.FREEZE,
                "M_TO_F", DiagnosticsResult.APPLIED, 1L));
        rows.get(DiagnosticsBehavior.FREEZE).add(row(
                FeatureIds.HANS_FREEZE_EXEMPTION, DiagnosticsBehavior.FREEZE,
                "LCD_ON", DiagnosticsResult.APPLIED, 1L));
        for (WakeSource source : WakeSource.values()) {
            if (source == WakeSource.INTERNAL_OPERATION) continue;
            rows.get(DiagnosticsBehavior.WAKE).add(row(
                    FeatureIds.HANS_WAKE, DiagnosticsBehavior.WAKE,
                    source.name(), DiagnosticsResult.PASSED, 1L));
            rows.get(DiagnosticsBehavior.REFREEZE).add(row(
                    FeatureIds.HANS_REFREEZE, DiagnosticsBehavior.REFREEZE,
                    source.name(), DiagnosticsResult.PASSED, 1L));
        }
        rows.get(DiagnosticsBehavior.RESOURCE).add(row(
                FeatureIds.HANS_RESOURCE, DiagnosticsBehavior.RESOURCE,
                "NETWORK", DiagnosticsResult.PASSED, 1L));
        for (ResourceType resource : ResourceType.values()) {
            rows.get(DiagnosticsBehavior.RESOURCE).add(row(
                    FeatureIds.HANS_RESOURCE, DiagnosticsBehavior.RESOURCE,
                    resource.name(), DiagnosticsResult.PASSED, 1L));
        }
        String[] recoveryResults = {
            DiagnosticsResult.RECOVERED,
            DiagnosticsResult.NO_ACTION_NEEDED,
            DiagnosticsResult.SAFETY_ABORTED,
            DiagnosticsResult.RUNTIME_FAIL_OPEN
        };
        for (String result : recoveryResults) {
            rows.get(DiagnosticsBehavior.RECOVERY).add(row(
                    FeatureIds.HANS_RECOVERY, DiagnosticsBehavior.RECOVERY,
                    "", result, 1L));
        }

        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            if (behavior == DiagnosticsBehavior.UNCLASSIFIED) continue;
            List<DiagnosticsUiMapper.BehaviorDetailRow> mapped = mapper.mapBehaviorDetails(
                    behavior, BehaviorDetailStats.success(rows.get(behavior)));
            assertEquals(behavior.name(), rows.get(behavior).size(), mapped.size());
            assertEquals(behavior.name(), rows.get(behavior).size(),
                    DiagnosticsUiMapper.sumDetailMetrics(mapped).observed);
            for (DiagnosticsUiMapper.BehaviorDetailRow detail : mapped) {
                assertFalse(behavior.name(), detail.fallback);
                assertEquals(behavior.name(), 1L, detail.metrics.observed);
            }
        }
    }

    @Test
    public void unknownFeatureAndSourceUseOneFallbackWithoutLosingMetrics() {
        List<DiagnosticsQuery.AggregateRow> rows = new ArrayList<>();
        rows.add(row(FeatureIds.ATHENA_PERIODIC, DiagnosticsBehavior.KILL,
                "", DiagnosticsResult.BLOCKED, 2L));
        rows.add(new DiagnosticsQuery.AggregateRow(
                "", -1, "future_kill_feature", "hook", "KILL", "FUTURE_SOURCE",
                "decision", DiagnosticsResult.RUNTIME_FAIL_OPEN,
                3L, 3L, 0L, 3L, 2L, 7L, 5L, 10L, 20L));

        List<DiagnosticsUiMapper.BehaviorDetailRow> mapped = mapper.mapBehaviorDetails(
                DiagnosticsBehavior.KILL, BehaviorDetailStats.success(rows));

        assertEquals(18, mapped.size());
        DiagnosticsUiMapper.BehaviorDetailRow fallback = mapped.get(mapped.size() - 1);
        assertTrue(fallback.fallback);
        assertEquals(3L, fallback.metrics.observed);
        assertEquals(3L, fallback.metrics.failOpen);
        DiagnosticsUiMapper.Metrics totals = DiagnosticsUiMapper.sumDetailMetrics(mapped);
        assertEquals(5L, totals.observed);
        assertEquals(2L, totals.blocked);
        assertEquals(3L, totals.failOpen);
        assertEquals(5L, totals.rawHits);
    }

    @Test
    public void unclassifiedAlwaysHasAnExplicitRow() {
        List<DiagnosticsUiMapper.BehaviorDetailRow> empty = mapper.mapBehaviorDetails(
                DiagnosticsBehavior.UNCLASSIFIED,
                BehaviorDetailStats.success(Collections.emptyList()));
        assertEquals(1, empty.size());
        assertTrue(empty.get(0).fallback);

        List<DiagnosticsUiMapper.BehaviorDetailRow> populated = mapper.mapBehaviorDetails(
                DiagnosticsBehavior.UNCLASSIFIED,
                BehaviorDetailStats.success(Collections.singletonList(
                        new DiagnosticsQuery.AggregateRow(
                                "", -1, "future", "hook", "FUTURE_BEHAVIOR", "source",
                                "decision", DiagnosticsResult.PASSED,
                                6L, 6L, 0L, 0L, 6L, 0L, 0L, 1L, 2L))));
        assertEquals(1, populated.size());
        assertEquals(6L, populated.get(0).metrics.observed);
    }

    @Test
    public void candidateFilteringIsBlockedAndNeverNormalPassThrough() {
        DiagnosticsQuery.AggregateRow legacy = new DiagnosticsQuery.AggregateRow(
                "", -1, FeatureIds.ATHENA_PERIODIC, "hook",
                DiagnosticsBehavior.KILL.wireId, "", "candidate_filter",
                DiagnosticsResult.CANDIDATE_FILTERED,
                3L, 3L, 0L, 0L, 3L, 0L, 0L, 1L, 2L);

        List<DiagnosticsUiMapper.BehaviorDetailRow> mapped = mapper.mapBehaviorDetails(
                DiagnosticsBehavior.KILL,
                BehaviorDetailStats.success(Collections.singletonList(legacy)));
        DiagnosticsUiMapper.Metrics totals = DiagnosticsUiMapper.sumDetailMetrics(mapped);

        assertEquals(3L, totals.observed);
        assertEquals(3L, totals.blocked);
        assertEquals(0L, totals.passed);
    }

    @Test
    public void appliedTimingAndExemptionResultsAreAdjustedNotPassThrough() {
        List<DiagnosticsQuery.AggregateRow> rows = List.of(
                row(FeatureIds.HANS_FREEZE_TIMING, DiagnosticsBehavior.FREEZE,
                        "R_TO_M", DiagnosticsResult.APPLIED, 2L),
                row(FeatureIds.HANS_FREEZE_EXEMPTION, DiagnosticsBehavior.FREEZE,
                        "LCD_ON", DiagnosticsResult.APPLIED, 3L));

        List<DiagnosticsUiMapper.BehaviorDetailRow> mapped = mapper.mapBehaviorDetails(
                DiagnosticsBehavior.FREEZE, BehaviorDetailStats.success(rows));
        DiagnosticsUiMapper.Metrics totals = DiagnosticsUiMapper.sumDetailMetrics(mapped);

        assertEquals(DiagnosticsBehaviorDetailCatalog.visibleEntryCount(
                DiagnosticsBehavior.FREEZE), mapped.size());
        assertEquals(5L, totals.observed);
        assertEquals(5L, totals.applied);
        assertEquals(0L, totals.passed);
        assertEquals(0L, totals.unknownResult);
        assertFalse(mapped.stream().anyMatch(detail -> detail.fallback));
    }

    private static DiagnosticsQuery.AggregateRow row(
            String feature,
            DiagnosticsBehavior behavior,
            String source,
            String result,
            long count) {
        return new DiagnosticsQuery.AggregateRow(
                "", -1, feature, "hook", behavior.wireId, source, "decision", result,
                count,
                count,
                DiagnosticsResult.countsAsBlocked(result) ? count : 0L,
                DiagnosticsResult.countsAsFailOpen(result) ? count : 0L,
                count, 0L, 0L, 1L, 2L);
    }
}
