package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.R;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class DiagnosticsActivityMappingTest {
    @Test
    public void timingTargetsUseIndependentUserFacingSupportRows() throws Exception {
        Map<String, Integer> supportTitles = supportTitles();

        assertEquals(Integer.valueOf(R.string.hans_r_to_m_timing_support_title),
                supportTitles.get(HookTargetIds.HANS_R_TO_M_TIMING));
        assertEquals(Integer.valueOf(R.string.hans_m_to_f_timing_support_title),
                supportTitles.get(HookTargetIds.HANS_M_TO_F_TIMING));
    }

    @Test
    public void exemptionTargetsUseIndependentUserFacingSupportRows() throws Exception {
        Map<String, Integer> supportTitles = supportTitles();

        assertEquals(Integer.valueOf(R.string.hans_core_exemption_support_title),
                supportTitles.get(HookTargetIds.HANS_CORE_EXEMPTION));
        assertEquals(Integer.valueOf(R.string.hans_lcd_on_exemption_support_title),
                supportTitles.get(HookTargetIds.HANS_LCD_ON_EXEMPTION));
    }

    @Test
    public void wakeControlHasUserFacingSupportAndRuntimeRows() throws Exception {
        assertEquals(Integer.valueOf(R.string.hans_wake_support_title),
                supportTitles().get(HookTargetIds.HANS_WAKE_DISPATCH));
        assertEquals(Integer.valueOf(R.string.hans_wake_stats_title),
                statTitles().get(FeatureIds.HANS_WAKE));
    }

    @Test
    public void everyStableIdentifierHasExactlyOneDiagnosticsRow() throws Exception {
        assertEquals(new HashSet<>(HookTargetIds.all()), supportTitles().keySet());
        assertEquals(new HashSet<>(FeatureIds.all()), statTitles().keySet());
    }

    @Test
    public void incompleteCorePrerequisiteHasDedicatedUserFacingStatus() {
        assertEquals(
                R.string.support_freeze_gate_incomplete,
                DiagnosticsActivity.supportStatusMessageRes(
                        "UNSUPPORTED_FREEZE_GATE_INCOMPLETE"));
        assertEquals(
                R.string.support_unavailable,
                DiagnosticsActivity.supportStatusMessageRes(
                        "UNSUPPORTED_MISSING_METHOD"));
    }

    @Test
    public void timingSupportRowsAreNotIncludedInRuntimeStatistics() throws Exception {
        Field groupsField = DiagnosticsActivity.class.getDeclaredField("STAT_GROUPS");
        groupsField.setAccessible(true);
        List<?> groups = (List<?>) groupsField.get(null);

        for (Object group : groups) {
            Field itemsField = group.getClass().getDeclaredField("items");
            itemsField.setAccessible(true);
            for (Object item : (List<?>) itemsField.get(group)) {
                Field titleField = item.getClass().getDeclaredField("titleRes");
                titleField.setAccessible(true);
                int titleRes = titleField.getInt(item);
                assertFalse(titleRes == R.string.hans_r_to_m_timing_support_title);
                assertFalse(titleRes == R.string.hans_m_to_f_timing_support_title);
                assertFalse(titleRes == R.string.hans_core_exemption_support_title);
                assertFalse(titleRes == R.string.hans_lcd_on_exemption_support_title);
            }
        }
    }

    private static Map<String, Integer> supportTitles() throws Exception {
        Field supportsField = DiagnosticsActivity.class.getDeclaredField("SUPPORTS");
        supportsField.setAccessible(true);
        List<?> supports = (List<?>) supportsField.get(null);
        Map<String, Integer> result = new HashMap<>();
        for (Object support : supports) {
            Field idField = support.getClass().getDeclaredField("hookTargetId");
            Field titleField = support.getClass().getDeclaredField("titleRes");
            idField.setAccessible(true);
            titleField.setAccessible(true);
            result.put((String) idField.get(support), titleField.getInt(support));
        }
        assertEquals("duplicate support target", supports.size(), result.size());
        return result;
    }

    private static Map<String, Integer> statTitles() throws Exception {
        Field groupsField = DiagnosticsActivity.class.getDeclaredField("STAT_GROUPS");
        groupsField.setAccessible(true);
        List<?> groups = (List<?>) groupsField.get(null);
        Map<String, Integer> result = new HashMap<>();
        int itemCount = 0;
        for (Object group : groups) {
            Field itemsField = group.getClass().getDeclaredField("items");
            itemsField.setAccessible(true);
            for (Object item : (List<?>) itemsField.get(group)) {
                itemCount++;
                Field idField = item.getClass().getDeclaredField("featureId");
                Field titleField = item.getClass().getDeclaredField("titleRes");
                idField.setAccessible(true);
                titleField.setAccessible(true);
                result.put((String) idField.get(item), titleField.getInt(item));
            }
        }
        assertEquals("duplicate statistics feature", itemCount, result.size());
        return result;
    }
}
