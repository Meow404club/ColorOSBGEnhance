package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

        HashSet<String> implementedTargets = new HashSet<>(HookTargetIds.all());
        implementedTargets.remove(HookTargetIds.HANS_WAKE_DISPATCH);
        assertEquals(implementedTargets, new HashSet<>(actual));
        assertEquals(implementedTargets.size(), actual.size());
        assertEquals(actual.size(), new HashSet<>(actual).size());
    }
}
