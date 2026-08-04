package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.residency.decision.FreezeTimingContext;
import com.frostwork.bgenhance.residency.decision.FreezeTimingDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class HansTimingHookTest {
    private static final String PACKAGE = "com.example.hans.timing";
    private static final int UID = 10_851;

    @Test
    public void callsOriginalBeforeDecisionAndReplacesValidDelay() throws Throwable {
        List<String> order = new ArrayList<>();
        FreezeTimingDecision decision = decision(
                FreezeTimingContext.Transition.R_TO_M,
                false,
                true,
                false,
                5_000L,
                7_000L);

        Object result = HansTimingHook.complete(
                () -> {
                    order.add("original");
                    return 1_111L;
                },
                () -> {
                    order.add("decision");
                    return decision;
                });

        assertEquals(List.of("original", "decision"), order);
        assertEquals(Long.valueOf(5_000L), result);
    }

    @Test
    public void choosesTheDelayForTheRequestedTransition() throws Throwable {
        FreezeTimingDecision decision = decision(
                FreezeTimingContext.Transition.M_TO_F,
                false,
                true,
                false,
                5_000L,
                7_000L);

        Object result = HansTimingHook.complete(() -> 2_222L, () -> decision);

        assertEquals(Long.valueOf(7_000L), result);
    }

    @Test
    public void dryRunAndFollowSystemKeepTheVendorResult() throws Throwable {
        FreezeTimingDecision dryRun = decision(
                FreezeTimingContext.Transition.R_TO_M,
                true,
                true,
                false,
                5_000L,
                7_000L);
        FreezeTimingDecision followSystem = decision(
                FreezeTimingContext.Transition.R_TO_M,
                false,
                false,
                false,
                5_000L,
                7_000L);
        FreezeTimingDecision exempt = decision(
                FreezeTimingContext.Transition.R_TO_M,
                false,
                true,
                true,
                5_000L,
                7_000L);

        assertEquals(Long.valueOf(3_333L),
                HansTimingHook.complete(() -> 3_333L, () -> dryRun));
        assertEquals(Long.valueOf(3_333L),
                HansTimingHook.complete(() -> 3_333L, () -> followSystem));
        assertEquals(Long.valueOf(3_333L),
                HansTimingHook.complete(() -> 3_333L, () -> exempt));
    }

    @Test
    public void hookBoundaryRejectsOutOfRangeCustomDelays() throws Throwable {
        FreezeTimingDecision tooShort = decision(
                FreezeTimingContext.Transition.R_TO_M,
                false,
                true,
                false,
                HansTimingHook.MIN_DELAY_MS - 1L,
                7_000L);
        FreezeTimingDecision tooLong = decision(
                FreezeTimingContext.Transition.M_TO_F,
                false,
                true,
                false,
                5_000L,
                HansTimingHook.MAX_DELAY_MS + 1L);

        assertEquals(Long.valueOf(4_444L),
                HansTimingHook.complete(() -> 4_444L, () -> tooShort));
        assertEquals(Long.valueOf(4_444L),
                HansTimingHook.complete(() -> 4_444L, () -> tooLong));
    }

    @Test
    public void decisionFailureAndUnexpectedResultTypeKeepTheVendorResult()
            throws Throwable {
        Object original = new Object();
        AtomicInteger decisions = new AtomicInteger();

        assertEquals(Long.valueOf(5_555L), HansTimingHook.complete(
                () -> 5_555L,
                () -> {
                    throw new IllegalStateException("decision failed");
                }));
        assertSame(original, HansTimingHook.complete(
                () -> original,
                () -> {
                    decisions.incrementAndGet();
                    return decision(
                            FreezeTimingContext.Transition.R_TO_M,
                            false,
                            true,
                            false,
                            5_000L,
                            7_000L);
                }));
        assertEquals(0, decisions.get());
    }

    @Test
    public void originalFailurePropagatesWithoutRunningDecision() {
        OriginalFailure failure = new OriginalFailure();
        AtomicInteger decisions = new AtomicInteger();

        OriginalFailure actual = assertThrows(OriginalFailure.class, () ->
                HansTimingHook.complete(
                        () -> { throw failure; },
                        () -> {
                            decisions.incrementAndGet();
                            return null;
                        }));

        assertSame(failure, actual);
        assertEquals(0, decisions.get());
    }

    @Test
    public void reportsCapabilityAndInstallationStatesPrecisely() {
        assertEquals(
                HansRuntime.Capability.R_TO_M_TIMING,
                HansTimingHook.rToM().capability());
        assertEquals(
                HansRuntime.Capability.M_TO_F_TIMING,
                HansTimingHook.mToF().capability());
        assertEquals(
                "UNSUPPORTED_MISSING_CLASS",
                HansTimingHook.capabilityStatus(HansRuntime.Status.MISSING_CLASS));
        assertEquals(
                "FAILED_FAIL_OPEN:LOOKUP_FAILED",
                HansTimingHook.capabilityStatus(HansRuntime.Status.LOOKUP_FAILED));
        assertEquals("UNSUPPORTED_CLASS", HansTimingHook.installationStatus(-1));
        assertEquals(
                "FAILED_FAIL_OPEN:HOOK_NOT_INSTALLED",
                HansTimingHook.installationStatus(0));
        assertEquals("INSTALLED:1", HansTimingHook.installationStatus(1));
        assertEquals(
                "FAILED_FAIL_OPEN:UNEXPECTED_HOOK_COUNT=2",
                HansTimingHook.installationStatus(2));
    }

    @Test
    public void onlyProductionTimingEventsReplaceTheRawOnlyDiagnostic() {
        ResidencyTarget target = new ResidencyTarget(
                PACKAGE, UID, ResidencyTarget.UNKNOWN_PID);
        FreezeTimingDecision decision = decision(
                FreezeTimingContext.Transition.R_TO_M,
                false,
                true,
                false,
                5_000L,
                7_000L);

        assertTrue(HansTimingHook.producesStandardEvent(
                FreezeTimingContext.Transition.R_TO_M, target, decision));
        assertFalse(HansTimingHook.producesStandardEvent(
                FreezeTimingContext.Transition.M_TO_F, target, decision));
        assertFalse(HansTimingHook.producesStandardEvent(
                FreezeTimingContext.Transition.R_TO_M, null, decision));
        assertFalse(HansTimingHook.producesStandardEvent(
                FreezeTimingContext.Transition.R_TO_M, target, null));
    }

    @Test
    public void delayBoundsAreInclusiveAndInvalidTargetsAreRejected() {
        assertTrue(HansTimingHook.isValidDelay(HansTimingHook.MIN_DELAY_MS));
        assertTrue(HansTimingHook.isValidDelay(HansTimingHook.MAX_DELAY_MS));
        assertFalse(HansTimingHook.isValidDelay(HansTimingHook.MIN_DELAY_MS - 1L));
        assertFalse(HansTimingHook.isValidDelay(HansTimingHook.MAX_DELAY_MS + 1L));

        assertNull(HansTimingHook.target(ResidencyTarget.UNKNOWN_UID, PACKAGE));
        assertNull(HansTimingHook.target(UID, null));
        assertNull(HansTimingHook.target(UID, ""));
        assertEquals(
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID),
                HansTimingHook.target(UID, PACKAGE));

        assertEquals(
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID),
                HansTimingHook.target(new FakeChain(UID, PACKAGE)));
        assertNull(HansTimingHook.target(new FakeChain((long) UID, PACKAGE)));
        assertNull(HansTimingHook.target(new FakeChain(UID, new Object())));
        assertNull(HansTimingHook.target(new FakeChain(UID)));
    }

    private static FreezeTimingDecision decision(
            FreezeTimingContext.Transition transition,
            boolean dryRun,
            boolean customTiming,
            boolean fullExemption,
            long rToMDelayMs,
            long mToFDelayMs) {
        FreezePolicy freezePolicy = new FreezePolicy(
                true,
                true,
                true,
                true,
                customTiming,
                rToMDelayMs,
                mToFDelayMs,
                fullExemption,
                false,
                false);
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freezePolicy,
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(
                snapshot,
                ResidencyIdentityIndex.build(snapshot, Map.of(PACKAGE, UID)));
        return engine.decideFreezeTiming(new FreezeTimingContext(
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID),
                transition,
                "",
                "hans_timing_test"));
    }

    private static final class OriginalFailure extends Exception {}

    private static final class FakeChain implements XposedInterface.Chain {
        private final List<Object> args;

        private FakeChain(Object... args) {
            this.args = Arrays.asList(args);
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return null; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }
        @Override public Object proceed() { throw new UnsupportedOperationException(); }
        @Override public Object proceed(Object[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public Object proceedWith(Object thisObject) {
            throw new UnsupportedOperationException();
        }
        @Override public Object proceedWith(Object thisObject, Object[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
