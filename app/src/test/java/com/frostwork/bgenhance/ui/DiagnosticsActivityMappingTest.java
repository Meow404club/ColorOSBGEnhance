package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.R;
import java.lang.reflect.Field;
import java.util.HashMap;
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
        return result;
    }
}
