package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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
                "hans_r_to_m_timing",
                "hans_m_to_f_timing"
        ), HookTargetIds.all());
    }

    @Test
    public void targetListIsUniqueAndImmutable() {
        List<String> targets = HookTargetIds.all();
        assertEquals(targets.size(), new HashSet<>(targets).size());
        try {
            targets.add("unexpected");
            fail("hook target IDs must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }
}
