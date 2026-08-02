package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class HookTargetIdsTest {
    @Test
    public void serializedValuesRemainBackwardCompatible() {
        assertEquals(Arrays.asList(
                "athena_periodic",
                "oguard_kill",
                "oguard_force_stop",
                "fast_clear_dispatch",
                "tournament",
                "memory_guard_dispatch",
                "osense_group_clean",
                "osense_process_kill",
                "hans_freeze_dispatch",
                "hans_wake_dispatch",
                "alarm_wake_delivery",
                "hans_r_to_m_timing",
                "hans_m_to_f_timing",
                "hans_core_exemption",
                "hans_lcd_on_exemption",
                "hans_resource_dispatch"
        ), HookTargetIds.all());
    }

    @Test
    public void allIsABijectionWithPublicIdentifiers() throws Exception {
        List<String> targets = HookTargetIds.all();
        List<String> publicValues = publicStringConstants(HookTargetIds.class);

        assertEquals(targets.size(), new HashSet<>(targets).size());
        assertEquals(publicValues.size(), new HashSet<>(publicValues).size());
        assertEquals(new HashSet<>(publicValues), new HashSet<>(targets));
        assertEquals(publicValues.size(), targets.size());
        try {
            targets.add("unexpected");
            fail("hook target IDs must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static List<String> publicStringConstants(Class<?> type) throws Exception {
        List<String> result = new ArrayList<>();
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == String.class
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)) {
                result.add((String) field.get(null));
            }
        }
        return result;
    }
}
