package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.hooks.AthenaPeriodicHook;
import com.frostwork.bgenhance.hooks.FastClearHook;
import com.frostwork.bgenhance.hooks.HansFreezeHook;
import com.frostwork.bgenhance.hooks.MemoryGuardHook;
import com.frostwork.bgenhance.hooks.NirvanaHook;
import com.frostwork.bgenhance.hooks.OguardForceStopHook;
import com.frostwork.bgenhance.hooks.OguardKillHook;
import com.frostwork.bgenhance.hooks.TournamentHook;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class FeatureHookTargetIdsTest {
    @Test
    public void everyInstallerOwnsOneStableTarget() {
        List<FeatureHook> hooks = Arrays.asList(
                new AthenaPeriodicHook(),
                new OguardKillHook(),
                new OguardForceStopHook(),
                new FastClearHook(),
                new TournamentHook(),
                new MemoryGuardHook(),
                new NirvanaHook(),
                new HansFreezeHook()
        );
        List<String> actual = new ArrayList<>();
        for (FeatureHook hook : hooks) {
            String targetId = hook.hookTargetId();
            assertTrue(HookTargetIds.all().contains(targetId));
            actual.add(targetId);
        }

        assertEquals(HookTargetIds.all(), actual);
        assertEquals(actual.size(), new HashSet<>(actual).size());
    }
}
