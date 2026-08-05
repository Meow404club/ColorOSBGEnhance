package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.server.hans.OplusHansPackage;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.hans.HansObjectAdapter;
import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.hooks.hans.HansWakeSourceClassifier;
import com.frostwork.bgenhance.hooks.hans.WakeThrottleState;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.BeforeClass;
import org.junit.Test;

public final class HansWakeHookTest {
    private static final String PACKAGE = "com.example.hans.wake";
    private static final String SHARED_PACKAGE = "com.example.hans.wake.shared";
    private static final int UID = 10_902;

    @BeforeClass
    public static void prepareHansPackageAccessors() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));
    }

    @Test
    public void kernelBlockSkipsVendorMethodExactlyOnce() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, source, blockedWake(source), true, true,
                    false, 1L, 2L, 0L, Map.of(PACKAGE, UID));
        }, () -> 1_000L, reporter);
        FakeChain chain = kernelChain(null, 0);

        assertNull(hook.interceptKernel(chain));
        assertEquals(0, chain.proceedCalls());
        assertEquals(1, decisions.get());
        assertEquals(1, reporter.records.size());
        assertEquals(WakeDecision.Action.BLOCK_WAKE,
                reporter.records.get(0).decision.effectiveAction());
        assertNull(reporter.records.get(0).throttleOutcome);
    }

    @Test
    public void terminalBlockReturnsFalseWithoutCallingVendorMethod() throws Throwable {
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> decision(
                target, source, blockedWake(source), true, true,
                false, 1L, 2L, 0L, Map.of(PACKAGE, UID)),
                () -> 1_000L, reporter);
        FakeChain chain = terminalChain(Boolean.TRUE, true, "Broadcast", "receiver");

        assertEquals(Boolean.FALSE, hook.interceptTerminal(chain));
        assertEquals(0, chain.proceedCalls());
        assertEquals(1, reporter.records.size());
        assertEquals(PACKAGE, reporter.records.get(0).event.packageName);
        assertEquals(WakeSource.BROADCAST.ordinal(), reporter.records.get(0).event.code);
    }

    @Test
    public void packetThrottleAllowsFirstAndBlocksWithinWindow() throws Throwable {
        AtomicLong now = new AtomicLong(10_000L);
        RecordingReporter reporter = new RecordingReporter();
        WakePolicy throttle = packetPolicy(WakeMode.THROTTLE, 5_000L);
        HansWakeHook hook = hook((target, source) -> decision(
                target, source, throttle, true, true,
                false, 7L, 11L, 3L, Map.of(PACKAGE, UID)),
                now::get, reporter);
        FakeChain first = kernelChain(null, 4);

        assertNull(hook.interceptKernel(first));
        assertEquals(1, first.proceedCalls());
        now.set(14_999L);
        FakeChain second = kernelChain(null, 4);
        assertNull(hook.interceptKernel(second));
        assertEquals(0, second.proceedCalls());

        assertEquals(2, reporter.records.size());
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                reporter.records.get(0).throttleOutcome);
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                reporter.records.get(1).throttleOutcome);
    }

    @Test
    public void kernelToTerminalChainDecidesConsumesAndRecordsOnlyOnce() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, source, WakePolicy.allowAll(), true, true,
                    false, 1L, 2L, 0L, Map.of(PACKAGE, UID));
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(Boolean.TRUE, true, "AsyncBinder", "aidl");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
            return null;
        }, 0, 101, 1_001, 202, UID, "aidl", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
        assertEquals(1, decisions.get());
        assertEquals(1, reporter.records.size());
    }

    @Test
    public void packetKernelToTerminalChainConsumesThrottleOnlyOnce() throws Throwable {
        AtomicLong now = new AtomicLong(1_000L);
        RecordingReporter reporter = new RecordingReporter();
        WakePolicy throttle = packetPolicy(WakeMode.THROTTLE, 5_000L);
        HansWakeHook hook = hook((target, source) -> decision(
                target,
                source,
                throttle,
                true,
                true,
                false,
                1L,
                2L,
                0L,
                Map.of(PACKAGE, UID)), now::get, reporter);
        FakeChain firstTerminal = terminalChain(Boolean.TRUE, true, "Packet", "network");
        FakeChain firstKernel = FakeChain.calling(() -> {
            assertEquals(Boolean.TRUE, hook.interceptTerminal(firstTerminal));
            return null;
        }, 4, 101, 1_001, 202, UID, "network", 7);

        assertNull(hook.interceptKernel(firstKernel));
        assertEquals(1, firstKernel.proceedCalls());
        assertEquals(1, firstTerminal.proceedCalls());
        assertEquals(1, reporter.records.size());
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                reporter.records.get(0).throttleOutcome);

        now.set(1_001L);
        FakeChain secondTerminal = terminalChain(Boolean.TRUE, true, "Packet", "network");
        FakeChain secondKernel = FakeChain.calling(() -> {
            hook.interceptTerminal(secondTerminal);
            return null;
        }, 4, 101, 1_001, 202, UID, "network", 7);
        assertNull(hook.interceptKernel(secondKernel));
        assertEquals(0, secondKernel.proceedCalls());
        assertEquals(0, secondTerminal.proceedCalls());
        assertEquals(2, reporter.records.size());
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                reporter.records.get(1).throttleOutcome);
    }

    @Test
    public void kernelFrameIsClearedAfterVendorFailure() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, source, WakePolicy.allowAll(), true, true,
                    false, 1L, 2L, 0L, Map.of(PACKAGE, UID));
        }, () -> 1_000L, reporter);
        OriginalFailure failure = new OriginalFailure();
        FakeChain failed = FakeChain.calling(() -> {
            throw failure;
        }, 0, 101, 1_001, 202, UID, "aidl", 7);

        assertSame(failure, assertThrows(
                OriginalFailure.class, () -> hook.interceptKernel(failed)));
        FakeChain direct = terminalChain(Boolean.TRUE, true, "AsyncBinder", "aidl");
        assertEquals(Boolean.TRUE, hook.interceptTerminal(direct));
        assertEquals(2, decisions.get());
        assertEquals(2, reporter.records.size());
        assertEquals(1, direct.proceedCalls());
    }

    @Test
    public void typedFailedOpenKernelFrameDoesNotAssumeIsolatedUidOwnership()
            throws Throwable {
        int isolatedUid = 99_902;
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(
                    target,
                    source,
                    blockedWake(source),
                    true,
                    true,
                    false,
                    1L,
                    2L,
                    0L,
                    Map.of(PACKAGE, UID));
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(Boolean.TRUE, true, "AsyncBinder", "aidl");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.FALSE, hook.interceptTerminal(terminal));
            return null;
        }, 0, 101, 1_001, 202, isolatedUid, "aidl", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(2, decisions.get());
        assertEquals(2, reporter.records.size());
        assertEquals(DecisionDisposition.FAIL_OPEN,
                reporter.records.get(0).decision.trace().disposition());
        assertEquals(WakeDecision.Action.BLOCK_WAKE,
                reporter.records.get(1).decision.effectiveAction());
        assertEquals(0, reporter.failOpenEvents.size());
        assertEquals(0, reporter.unparsedArgumentHits);
        assertEquals(1, kernel.proceedCalls());
        assertEquals(0, terminal.proceedCalls());
    }

    @Test
    public void typedFailedOpenKernelFrameDoesNotAliasAnotherOrdinaryUid() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(
                    target,
                    source,
                    WakePolicy.allowAll(),
                    false,
                    true,
                    false,
                    1L,
                    2L,
                    0L,
                    Map.of());
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(
                UID + 1, Boolean.TRUE, true, "AsyncBinder", "aidl");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
            return null;
        }, 0, 101, 1_001, 202, UID, "aidl", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(2, decisions.get());
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
        assertEquals(2, reporter.records.size());
    }

    @Test
    public void unknownKernelBypassesKnownNestedTerminalAndRecordsOnlyOnce() throws Throwable {
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            throw new AssertionError("unknown source must not reach policy");
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(
                Boolean.TRUE, true, "Broadcast", "future");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
            return null;
        }, 99, 101, 1_001, 202, UID, "future", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(1, reporter.failOpenEvents.size());
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
    }

    @Test
    public void unknownKernelFrameDoesNotBypassAnotherUid() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(
                    target,
                    source,
                    WakePolicy.allowAll(),
                    false,
                    true,
                    false,
                    1L,
                    2L,
                    0L,
                    Map.of());
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(
                UID + 1, Boolean.TRUE, true, "Broadcast", "future");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
            return null;
        }, 99, 101, 1_001, 202, UID, "future", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(1, decisions.get());
        assertEquals(1, reporter.failOpenEvents.size());
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
    }

    @Test
    public void isolatedAliasCannotCrossUsers() throws Throwable {
        int isolatedUid = 99_902;
        int otherUserOwnerUid = 110_902;
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(
                    target,
                    source,
                    WakePolicy.allowAll(),
                    false,
                    true,
                    false,
                    1L,
                    2L,
                    0L,
                    Map.of());
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(
                otherUserOwnerUid, Boolean.TRUE, true, "AsyncBinder", "aidl");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
            return null;
        }, 0, 101, 1_001, 202, isolatedUid, "aidl", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(2, decisions.get());
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
    }

    @Test
    public void inactiveCallbacksAlwaysProceedWithoutPolicyOrDiagnostics() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = new HansWakeHook(
                (target, source) -> {
                    decisions.incrementAndGet();
                    return decision(
                            target,
                            source,
                            blockedWake(source),
                            true,
                            true,
                            false,
                            1L,
                            2L,
                            0L,
                            Map.of(PACKAGE, UID));
                },
                new WakeThrottleState(),
                () -> 1_000L,
                reporter,
                false);
        FakeChain kernel = kernelChain(null, 0);
        FakeChain terminal = terminalChain(Boolean.TRUE, true, "Broadcast", "receiver");

        assertNull(hook.interceptKernel(kernel));
        assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(0, reporter.records.size());
        assertEquals(0, reporter.failOpenEvents.size());
    }

    @Test
    public void moduleRecoveryAlarmAndAlreadyThawedTargetsBypassPolicy() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            throw new AssertionError("policy must not run");
        }, () -> 1_000L, new RecordingReporter());

        FakeChain recovery = terminalChain(
                Boolean.TRUE,
                true,
                HansWakeSourceClassifier.MODULE_RECOVERY_REASON,
                HansWakeSourceClassifier.MODULE_RECOVERY_COMPONENT);
        FakeChain alarm = terminalChain(Boolean.TRUE, true, "Alarm", "calendar");
        FakeChain thawed = terminalChain(Boolean.TRUE, false, "Broadcast", "receiver");

        assertEquals(Boolean.TRUE, hook.interceptTerminal(recovery));
        assertEquals(Boolean.TRUE, hook.interceptTerminal(alarm));
        assertEquals(Boolean.TRUE, hook.interceptTerminal(thawed));
        assertEquals(0, decisions.get());
        assertEquals(1, recovery.proceedCalls());
        assertEquals(1, alarm.proceedCalls());
        assertEquals(1, thawed.proceedCalls());
    }

    @Test
    public void unknownKernelAndTerminalSourcesFailOpenWithoutPolicyLookup() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            throw new AssertionError("unknown source must fail open");
        }, () -> 1_000L, reporter);
        FakeChain kernel = FakeChain.returning(null,
                99, 101, 1_001, 202, UID, "future", 7);
        FakeChain terminal = terminalChain(Boolean.TRUE, true, "FutureReason", "component");

        assertNull(hook.interceptKernel(kernel));
        assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
        assertEquals(0, decisions.get());
        assertEquals(1, kernel.proceedCalls());
        assertEquals(1, terminal.proceedCalls());
        assertEquals(2, reporter.failOpenEvents.size());
    }

    @Test
    public void invalidKernelUidLeavesIndependentTerminalDecisionIntact() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            decisions.incrementAndGet();
            return decision(
                    target,
                    source,
                    blockedWake(source),
                    true,
                    true,
                    false,
                    1L,
                    2L,
                    0L,
                    Map.of(PACKAGE, UID));
        }, () -> 1_000L, reporter);
        FakeChain terminal = terminalChain(Boolean.TRUE, true, "Broadcast", "receiver");
        FakeChain kernel = FakeChain.calling(() -> {
            assertEquals(Boolean.FALSE, hook.interceptTerminal(terminal));
            return null;
        }, 99, 101, 1_001, 202, -1, "future", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(1, decisions.get());
        assertEquals(0, reporter.failOpenEvents.size());
        assertEquals(1, reporter.unparsedArgumentHits);
        assertEquals(1, reporter.records.size());
        assertEquals(1, kernel.proceedCalls());
        assertEquals(0, terminal.proceedCalls());
    }

    @Test
    public void malformedKernelUidIsRecordedOnlyAsRawUnparsedHit() throws Throwable {
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            throw new AssertionError("malformed UID must not reach policy");
        }, () -> 1_000L, reporter);
        FakeChain kernel = FakeChain.returning(
                null, 0, 101, 1_001, 202, "not-a-uid", "aidl", 7);

        assertNull(hook.interceptKernel(kernel));
        assertEquals(1, kernel.proceedCalls());
        assertEquals(0, reporter.records.size());
        assertEquals(0, reporter.failOpenEvents.size());
        assertEquals(1, reporter.unparsedArgumentHits);
    }

    @Test
    public void unresolvedTerminalObjectIsRecordedOnlyAsRawUnparsedHit()
            throws Throwable {
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> {
            throw new AssertionError("unresolved object must not reach policy");
        }, () -> 1_000L, reporter);
        FakeChain terminal = FakeChain.returning(
                Boolean.TRUE, null, "Broadcast", "receiver");

        assertEquals(Boolean.TRUE, hook.interceptTerminal(terminal));
        assertEquals(1, terminal.proceedCalls());
        assertEquals(0, reporter.records.size());
        assertEquals(0, reporter.failOpenEvents.size());
        assertEquals(1, reporter.unparsedArgumentHits);
    }

    @Test
    public void disabledDeletedAndSharedUidConflictDecisionsAllFailOpen() throws Throwable {
        WakePolicy blocked = blockedWake(WakeSource.ASYNC_BINDER);
        WakeDecision disabled = decision(
                ResidencyTarget.forUid(UID), WakeSource.ASYNC_BINDER, blocked,
                false, true, false, 1L, 2L, 0L, Map.of(PACKAGE, UID));
        WakeDecision deleted = decision(
                ResidencyTarget.forUid(UID), WakeSource.ASYNC_BINDER, blocked,
                true, true, false, 2L, 3L, 0L, Map.of());
        WakeDecision conflict = sharedUidConflictDecision();
        List<WakeDecision> decisions = Arrays.asList(disabled, deleted, conflict);
        AtomicInteger index = new AtomicInteger();
        HansWakeHook hook = hook(
                (target, source) -> decisions.get(index.getAndIncrement()),
                () -> 1_000L,
                new RecordingReporter());

        for (int attempt = 0; attempt < decisions.size(); attempt++) {
            FakeChain chain = kernelChain(null, 0);
            assertNull(hook.interceptKernel(chain));
            assertEquals(1, chain.proceedCalls());
        }
        assertEquals(DecisionDisposition.FAIL_OPEN,
                disabled.trace().disposition());
        assertEquals(DecisionDisposition.FAIL_OPEN,
                deleted.trace().disposition());
        assertEquals(DecisionDisposition.FAIL_OPEN,
                conflict.trace().disposition());
    }

    @Test
    public void staleIdentityGenerationFailsOpenWithoutReplacingNewWindow() throws Throwable {
        AtomicLong now = new AtomicLong(1_000L);
        WakePolicy throttle = packetPolicy(WakeMode.THROTTLE, 10_000L);
        WakeDecision generationOne = decision(
                ResidencyTarget.forUid(UID), WakeSource.PACKET, throttle,
                true, true, false, 5L, 8L, 1L, Map.of(PACKAGE, UID));
        WakeDecision generationTwo = decision(
                ResidencyTarget.forUid(UID), WakeSource.PACKET, throttle,
                true, true, false, 5L, 8L, 2L, Map.of(PACKAGE, UID));
        List<WakeDecision> decisions = Arrays.asList(
                generationOne, generationTwo, generationOne, generationTwo);
        AtomicInteger index = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook(
                (target, source) -> decisions.get(index.getAndIncrement()),
                now::get,
                reporter);

        int[] expectedProceedCalls = {1, 1, 1, 0};
        for (int attempt = 0; attempt < decisions.size(); attempt++) {
            now.set(1_000L + attempt);
            FakeChain chain = kernelChain(null, 4);
            assertNull(hook.interceptKernel(chain));
            assertEquals(expectedProceedCalls[attempt], chain.proceedCalls());
        }

        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                reporter.records.get(0).throttleOutcome);
        assertEquals(WakeThrottleState.Outcome.ALLOWED,
                reporter.records.get(1).throttleOutcome);
        assertEquals(WakeThrottleState.Outcome.FAIL_OPEN,
                reporter.records.get(2).throttleOutcome);
        assertEquals(WakeThrottleState.Outcome.THROTTLED,
                reporter.records.get(3).throttleOutcome);
    }

    @Test
    public void dryRunBlockNeverSuppressesVendorMethod() throws Throwable {
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook((target, source) -> decision(
                target, source, blockedWake(source), true, true,
                true, 1L, 2L, 0L, Map.of(PACKAGE, UID)),
                () -> 1_000L, reporter);
        FakeChain chain = kernelChain(null, 0);

        assertNull(hook.interceptKernel(chain));
        assertEquals(1, chain.proceedCalls());
        assertEquals(DecisionDisposition.DRY_RUN,
                reporter.records.get(0).decision.trace().disposition());
        assertEquals(WakeDecision.Action.ALLOW_SYSTEM,
                reporter.records.get(0).decision.effectiveAction());
    }

    @Test
    public void reportsCapabilityStatusWithoutClaimingPartialSupport() {
        assertEquals(
                "UNSUPPORTED_kernel=MISSING_METHOD,terminal=AVAILABLE",
                HansWakeHook.unavailableStatus(
                        HansRuntime.Status.MISSING_METHOD,
                        HansRuntime.Status.AVAILABLE));
        assertEquals(
                "FAILED_FAIL_OPEN:kernel=AVAILABLE,terminal=LOOKUP_FAILED",
                HansWakeHook.unavailableStatus(
                        HansRuntime.Status.AVAILABLE,
                        HansRuntime.Status.LOOKUP_FAILED));
    }

    @Test
    public void mapsThrottleStateToStableReporterOutcomes() {
        WakeDecision throttle = decision(
                ResidencyTarget.forUid(UID),
                WakeSource.PACKET,
                packetPolicy(WakeMode.THROTTLE, 5_000L),
                true,
                true,
                false,
                1L,
                2L,
                0L,
                Map.of(PACKAGE, UID));

        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_ALLOWED,
                HansWakeHook.reporterOutcome(
                        throttle, WakeThrottleState.Outcome.ALLOWED));
        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_BLOCKED,
                HansWakeHook.reporterOutcome(
                        throttle, WakeThrottleState.Outcome.THROTTLED));
        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.FAIL_OPEN,
                HansWakeHook.reporterOutcome(
                        throttle, WakeThrottleState.Outcome.FAIL_OPEN));
    }

    private static HansWakeHook hook(
            HansWakeHook.WakeDecisionResolver resolver,
            HansWakeHook.ElapsedRealtimeClock clock,
            RecordingReporter reporter) {
        return new HansWakeHook(
                resolver,
                new WakeThrottleState(),
                clock,
                reporter);
    }

    private static FakeChain kernelChain(Object result, int type) {
        return FakeChain.returning(result,
                type, 101, 1_001, 202, UID, "rpc", 7);
    }

    private static FakeChain terminalChain(
            Object result, boolean frozen, String reason, String component) {
        return terminalChain(UID, result, frozen, reason, component);
    }

    private static FakeChain terminalChain(
            int uid, Object result, boolean frozen, String reason, String component) {
        return FakeChain.returning(
                result,
                new OplusHansPackage(uid, PACKAGE, frozen, false, new Object()),
                reason,
                component);
    }

    private static WakePolicy blockedWake(WakeSource source) {
        return new WakePolicy(
                WakeChannelPolicy.allow(),
                WakeChannelPolicy.allow(),
                EnumSet.of(source));
    }

    private static WakePolicy packetPolicy(WakeMode mode, long cooldownMs) {
        return new WakePolicy(
                new WakeChannelPolicy(mode, cooldownMs),
                WakeChannelPolicy.allow(),
                EnumSet.noneOf(WakeSource.class));
    }

    private static WakeDecision sharedUidConflictDecision() {
        AppResidencyPolicy first = policy(PACKAGE, blockedWake(WakeSource.ASYNC_BINDER));
        AppResidencyPolicy second = policy(SHARED_PACKAGE, WakePolicy.allowAll());
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                9L,
                true,
                true,
                false,
                10L,
                Map.of(PACKAGE, first, SHARED_PACKAGE, second));
        ResidencyIdentityIndex identities = ResidencyIdentityIndex.build(
                snapshot,
                Map.of(PACKAGE, UID, SHARED_PACKAGE, UID));
        return new ResidencyDecisionEngine(snapshot, identities, 4L).decideWake(
                new WakeContext(
                        ResidencyTarget.forUid(UID),
                        WakeSource.ASYNC_BINDER,
                        FeatureIds.HANS_WAKE,
                        HookTargetIds.HANS_WAKE_DISPATCH));
    }

    private static WakeDecision decision(
            ResidencyTarget target,
            WakeSource source,
            WakePolicy wakePolicy,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            long configRevision,
            long policyRevision,
            long identityGeneration,
            Map<String, Integer> packageUids) {
        Map<String, AppResidencyPolicy> policies = packageUids.isEmpty()
                ? Map.of()
                : Map.of(PACKAGE, policy(PACKAGE, wakePolicy));
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                configRevision,
                masterEnabled,
                residencyEnabled,
                dryRun,
                policyRevision,
                policies);
        ResidencyIdentityIndex identities = ResidencyIdentityIndex.build(
                snapshot, packageUids);
        return new ResidencyDecisionEngine(
                snapshot, identities, identityGeneration).decideWake(new WakeContext(
                target,
                source,
                FeatureIds.HANS_WAKE,
                HookTargetIds.HANS_WAKE_DISPATCH));
    }

    private static AppResidencyPolicy policy(
            String packageName, WakePolicy wakePolicy) {
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                wakePolicy,
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem());
    }

    private static final class RecordingReporter
            implements HansWakeHook.WakeDiagnosticsRecorder {
        private final List<Record> records = new ArrayList<>();
        private final List<HookEvent> failOpenEvents = new ArrayList<>();
        private int unparsedArgumentHits;

        @Override
        public void recordDecision(
                HookEvent event,
                WakeDecision decision,
                WakeThrottleState.Outcome throttleOutcome) {
            records.add(new Record(event, decision, throttleOutcome));
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            failOpenEvents.add(event);
        }

        @Override
        public void recordUnparsedArguments() {
            unparsedArgumentHits++;
        }
    }

    private static final class Record {
        private final HookEvent event;
        private final WakeDecision decision;
        private final WakeThrottleState.Outcome throttleOutcome;

        private Record(
                HookEvent event,
                WakeDecision decision,
                WakeThrottleState.Outcome throttleOutcome) {
            this.event = event;
            this.decision = decision;
            this.throttleOutcome = throttleOutcome;
        }
    }

    private interface OriginalCall {
        Object proceed() throws Throwable;
    }

    private static final class OriginalFailure extends Exception {}

    private static final class FakeChain implements XposedInterface.Chain {
        private final List<Object> args;
        private final OriginalCall originalCall;
        private final AtomicInteger proceedCalls = new AtomicInteger();

        private FakeChain(OriginalCall originalCall, Object... args) {
            this.originalCall = originalCall;
            this.args = Arrays.asList(args);
        }

        private static FakeChain returning(Object result, Object... args) {
            return new FakeChain(() -> result, args);
        }

        private static FakeChain calling(OriginalCall originalCall, Object... args) {
            return new FakeChain(originalCall, args);
        }

        private int proceedCalls() { return proceedCalls.get(); }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return null; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }
        @Override public Object proceed() throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceed(Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceedWith(Object thisObject) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceedWith(
                Object thisObject, Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
    }
}
