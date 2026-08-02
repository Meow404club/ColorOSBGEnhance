package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.hooks.HansActiveRecoveryHook;
import com.frostwork.bgenhance.hooks.HansFastFreezeHook;
import com.frostwork.bgenhance.hooks.HansFreezeHook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class FeatureHookTargetIdsTest {
    @Test
    public void everyInstallerOwnsOneStableTarget() {
        List<FeatureHook> hooks = new ArrayList<>();
        hooks.addAll(MainHook.systemServerHooks());
        hooks.addAll(MainHook.athenaHooks());
        List<String> actual = new ArrayList<>();
        for (FeatureHook hook : hooks) {
            String targetId = hook.hookTargetId();
            assertTrue(HookTargetIds.all().contains(targetId));
            actual.add(targetId);
        }

        assertEquals(new HashSet<>(HookTargetIds.all()), new HashSet<>(actual));
        assertEquals(HookTargetIds.all().size(), actual.size());
        assertEquals(actual.size(), new HashSet<>(actual).size());
    }

    @Test
    public void activeRecoveryInstallsAfterEveryHansBehaviorGate() {
        List<FeatureHook> hooks = MainHook.systemServerHooks();
        assertTrue(hooks.get(hooks.size() - 1) instanceof HansActiveRecoveryHook);
    }

    @Test
    public void fastFreezeInstallsImmediatelyAfterObjectFreezeGate() {
        List<FeatureHook> hooks = MainHook.systemServerHooks();
        int freezeIndex = -1;
        int fastIndex = -1;
        for (int index = 0; index < hooks.size(); index++) {
            if (hooks.get(index) instanceof HansFreezeHook) freezeIndex = index;
            if (hooks.get(index) instanceof HansFastFreezeHook) fastIndex = index;
        }
        assertTrue(freezeIndex >= 0);
        assertEquals(freezeIndex + 1, fastIndex);
    }
}
