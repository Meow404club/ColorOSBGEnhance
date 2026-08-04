package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusOsenseKillAction;
import com.android.server.am.ProcessInfo;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public final class OsenseProcessKillHookTest {
    private static final String PACKAGE_NAME = "com.example.osense.target";
    private static final int UID = 10_711;

    @Test
    public void acceptsOnlyExactPrivateFourParameterVoidMethod() throws Exception {
        Method exact = OplusOsenseKillAction.class.getDeclaredMethod(
                "killOneProcessLocked",
                OplusOsenseKillAction.KillContext.class,
                ProcessInfo.class,
                String.class,
                int.class);
        Method wrapper = OplusOsenseKillAction.class.getDeclaredMethod(
                "killOneProcessLocked",
                OplusOsenseKillAction.KillContext.class,
                ProcessInfo.class,
                String.class);

        assertTrue(OsenseProcessKillHook.matchesTarget(exact));
        assertFalse(OsenseProcessKillHook.matchesTarget(wrapper));
        assertFalse(OsenseProcessKillHook.matchesTarget(method(StaticTarget.class)));
        assertFalse(OsenseProcessKillHook.matchesTarget(method(PublicTarget.class)));
        assertFalse(OsenseProcessKillHook.matchesTarget(method(WrongReturnTarget.class)));
        assertFalse(OsenseProcessKillHook.matchesTarget(method(WrongParameterTarget.class)));
    }

    @Test
    public void reportsAccessorPreparationFailureAsFailOpenInstallation() {
        assertEquals("INSTALLED:1", OsenseProcessKillHook.status(1, true));
        assertEquals(
                "UNSUPPORTED_SIGNATURE:EXPECTED=1:FOUND=2",
                OsenseProcessKillHook.status(2, true));
        assertEquals(
                "FAILED_FAIL_OPEN:ACCESSOR_PREPARE:INSTALLED=1",
                OsenseProcessKillHook.status(1, false));
    }

    @Test
    public void blocksWithoutProceedAndPassesThroughExactlyOnce() throws Throwable {
        ChainRecorder blocked = new ChainRecorder();
        assertNull(OsenseProcessKillHook.complete(blocked.call(), decision(false)));
        assertEquals(0, blocked.proceedCount);

        ChainRecorder dryRun = new ChainRecorder();
        assertSame(dryRun.result,
                OsenseProcessKillHook.complete(dryRun.call(), decision(true)));
        assertEquals(1, dryRun.proceedCount);

        ChainRecorder unavailable = new ChainRecorder();
        assertSame(unavailable.result,
                OsenseProcessKillHook.complete(
                        unavailable.call(), unavailableDecision()));
        assertEquals(1, unavailable.proceedCount);

        ChainRecorder globalFeatureDisabled = new ChainRecorder();
        assertNull(OsenseProcessKillHook.complete(
                globalFeatureDisabled.call(), globalFeatureDisabledDecision()));
        assertEquals(0, globalFeatureDisabled.proceedCount);
    }

    private static Method method(Class<?> owner) throws Exception {
        return owner.getDeclaredMethods()[0];
    }

    private static KillDecision decision(boolean dryRun) {
        AppResidencyPolicy policy = ResidencyPresets.create(
                PACKAGE_NAME, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE_NAME, policy));
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(
                snapshot, ResidencyIdentityIndex.build(snapshot, Map.of(PACKAGE_NAME, UID)));
        return engine.decideKill(new KillContext(
                new ResidencyTarget(PACKAGE_NAME, UID, 4_711),
                KillContext.Path.OSENSE_PER_PROCESS));
    }

    private static KillDecision unavailableDecision() {
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.unavailable(1L);
        return new ResidencyDecisionEngine(
                snapshot, ResidencyIdentityIndex.build(snapshot, Collections.emptyMap()))
                .decideKill(new KillContext(
                        new ResidencyTarget(PACKAGE_NAME, UID, 4_711),
                        KillContext.Path.OSENSE_PER_PROCESS));
    }

    private static KillDecision globalFeatureDisabledDecision() {
        AppResidencyPolicy policy = ResidencyPresets.create(
                PACKAGE_NAME, ResidencyMode.BALANCED);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L,
                true,
                true,
                false,
                Map.of(ModuleSettings.KEY_NIRVANA, false),
                2L,
                Map.of(PACKAGE_NAME, policy));
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(
                snapshot, ResidencyIdentityIndex.build(snapshot, Map.of(PACKAGE_NAME, UID)));
        return engine.decideKill(
                new KillContext(
                        new ResidencyTarget(PACKAGE_NAME, UID, 4_711),
                        KillContext.Path.NIRVANA),
                ModuleSettings.KEY_NIRVANA,
                true);
    }

    private static final class ChainRecorder {
        private final Object result = new Object();
        private int proceedCount;

        OsenseProcessKillHook.OriginalCall call() {
            return () -> {
                proceedCount++;
                return result;
            };
        }
    }

    private static final class StaticTarget {
        private static void killOneProcessLocked(
                OplusOsenseKillAction.KillContext context,
                ProcessInfo processInfo,
                String reason,
                int subReasonCode) {}
    }

    private static final class PublicTarget {
        public void killOneProcessLocked(
                OplusOsenseKillAction.KillContext context,
                ProcessInfo processInfo,
                String reason,
                int subReasonCode) {}
    }

    private static final class WrongReturnTarget {
        private boolean killOneProcessLocked(
                OplusOsenseKillAction.KillContext context,
                ProcessInfo processInfo,
                String reason,
                int subReasonCode) { return false; }
    }

    private static final class WrongParameterTarget {
        private void killOneProcessLocked(
                Object context, ProcessInfo processInfo, String reason, int subReasonCode) {}
    }
}
