package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class DiagnosticsStableIdsTest {
    @Test
    public void idsRemainUniqueWhenPreviousAssignmentsCollide() {
        DiagnosticsUiMapper.UiRow first = () -> "row:first";
        DiagnosticsUiMapper.UiRow second = () -> "row:second";
        Map<String, Long> previous = new LinkedHashMap<>();
        previous.put(first.stableId(), 17L);
        previous.put(second.stableId(), 17L);

        Map<String, Long> ids = DiagnosticsStableIds.reconcile(
                previous, Arrays.asList(first, second));

        assertNotEquals(ids.get(first.stableId()), ids.get(second.stableId()));
    }

    @Test
    public void activeIdsSurviveRefreshAndReordering() {
        DiagnosticsUiMapper.UiRow first = () -> "row:first";
        DiagnosticsUiMapper.UiRow second = () -> "row:second";
        List<DiagnosticsUiMapper.UiRow> initial = Arrays.asList(first, second);
        Map<String, Long> before = DiagnosticsStableIds.reconcile(null, initial);

        Map<String, Long> after = DiagnosticsStableIds.reconcile(
                before, Arrays.asList(second, first));

        assertEquals(before.get(first.stableId()), after.get(first.stableId()));
        assertEquals(before.get(second.stableId()), after.get(second.stableId()));
    }
}
