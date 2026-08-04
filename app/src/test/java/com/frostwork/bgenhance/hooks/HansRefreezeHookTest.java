package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.states.HansAppStateMachine;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRegistry;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRuntime;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeStateAdapter;
import com.frostwork.bgenhance.residency.decision.FreezeTimingContext;
import com.frostwork.bgenhance.residency.decision.FreezeTimingDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.PackageUidSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.BeforeClass;
import org.junit.Test;

public final class HansRefreezeHookTest {
    private static final String PACKAGE = "com.example.refreeze.target";
    private static final int UID = 10_851;
    private static final Object ORIGINAL = new Object();

    @BeforeClass
    public static void prepareFirmwareAccessors() {
        assertTrue(HansRefreezeRuntime.inspect(
                OplusHansPackage.class.getClassLoader()).allAvailable());
    }

    @Test
    public void sourceAwareCycleOverridesDelayAndConsumesLatestDisabledDecision()
            throws Throwable {
        AtomicReference<RefreezeMode> mode = new AtomicReference<>(RefreezeMode.AFTER_WAKE);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 12_000L, false),
                diagnostics,
                new HansRefreezeRegistry(8));
        Fixture fixture = fixture("Packet");

        AtomicReference<Object> nestedTimingResult = new AtomicReference<>();
        FakeChain frozen = FakeChain.calling(fixture.frozen, () -> {
            FakeChain timing = FakeChain.returning(
                    1_111L, null, UID, PACKAGE, 0, 0L, "Packet");
            nestedTimingResult.set(hook.interceptTiming(timing));
            return null;
        });
        assertNull(hook.interceptFrozenToMiddle(frozen));
        assertEquals(Long.valueOf(12_000L), nestedTimingResult.get());

        FakeChain enter = FakeChain.returning(null, fixture.middle);
        assertNull(hook.interceptMiddleEnter(enter));
        mode.set(RefreezeMode.DISABLED);

        FakeChain message = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(message, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(0, message.proceedCalls());
        assertEquals(1, diagnostics.decisions.size());
        assertEquals(RefreezeDecision.Action.DISABLE_REFREEZE,
                diagnostics.decisions.get(0).effectiveAction());
    }

    @Test
    public void masterOffRestoresOemRefreezeAfterAMessageWasConsumed() throws Throwable {
        assertConsumedMessageRecovery(RecoveryPolicy.MASTER_OFF);
    }

    @Test
    public void deletingTheRuleRestoresOemRefreezeAfterAMessageWasConsumed()
            throws Throwable {
        assertConsumedMessageRecovery(RecoveryPolicy.RULE_DELETED);
    }

    @Test
    public void systemModeRestoresOemRefreezeAfterAMessageWasConsumed() throws Throwable {
        assertConsumedMessageRecovery(RecoveryPolicy.SYSTEM_MODE);
    }

    @Test
    public void staleRecoveryTokenCannotAffectANewerMiddleCycle() throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                new RecordingDiagnostics(),
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE));

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);
        Object staleToken = fixture.stateMachine.sentMessageObjects().get(0);
        assertTrue(staleToken instanceof HansRefreezeHook.RecoveryToken);

        hook.interceptMiddleExit(FakeChain.returning(null, fixture.middle));
        mode.set(RefreezeMode.DISABLED);
        armFixture(hook, fixture, "Packet");
        long newerGeneration = registry.activeCycleOrNull(
                fixture.stateMachine).generation();

        FakeChain stale = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                stale, HansRefreezeHook.M_TO_F_MESSAGE, staleToken));
        assertEquals(0, stale.proceedCalls());
        assertEquals(newerGeneration, registry.activeCycleOrNull(
                fixture.stateMachine).generation());

        FakeChain current = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                current, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(0, current.proceedCalls());
        assertEquals(1, registry.blockedCycles().size());
    }

    @Test
    public void configPublicationBetweenDecisionAndTimedBlockNeverLosesRecovery()
            throws Throwable {
        for (RecoveryPolicy recoveryPolicy : RecoveryPolicy.values()) {
            HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
            AtomicBoolean publishDuringDecision = new AtomicBoolean();
            AtomicReference<HansRefreezeHook> hookReference = new AtomicReference<>();
            HansRefreezeHook hook = hook(
                    target -> timingDecision(target, 9_000L, false),
                    (target, source) -> {
                        RefreezeDecision disabled = refreezeDecision(
                                target, source, RefreezeMode.DISABLED, 0L, false);
                        if (publishDuringDecision.getAndSet(false)) {
                            hookReference.get().onRuntimeStateChanged(
                                    publishedState(recoveryPolicy));
                        }
                        return disabled;
                    },
                    new RecordingDiagnostics(),
                    registry);
            hookReference.set(hook);
            Fixture fixture = armedFixture(hook, "Packet");

            publishDuringDecision.set(true);
            FakeChain timed = FakeChain.returning(Boolean.FALSE, fixture.middle);
            assertEquals(recoveryPolicy.name(), Boolean.FALSE,
                    hook.interceptMiddleProcess(
                            timed, HansRefreezeHook.M_TO_F_MESSAGE));
            assertEquals(recoveryPolicy.name(), 1, timed.proceedCalls());
            assertTrue(recoveryPolicy.name(), registry.blockedCycles().isEmpty());
            assertTrue(recoveryPolicy.name(),
                    fixture.stateMachine.sentMessages().isEmpty());
        }
    }

    @Test
    public void configPublicationBetweenDecisionAndFastExitNeverLosesRecovery()
            throws Throwable {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.AFTER_WAKE);
        AtomicBoolean publishDuringDecision = new AtomicBoolean();
        AtomicReference<HansRefreezeHook> hookReference = new AtomicReference<>();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> {
                    RefreezeDecision decision = refreezeDecision(
                            target, source, mode.get(), 0L, false);
                    if (publishDuringDecision.getAndSet(false)) {
                        hookReference.get().onRuntimeStateChanged(
                                publishedState(RecoveryPolicy.SYSTEM_MODE));
                    }
                    return decision;
                },
                new RecordingDiagnostics(),
                registry);
        hookReference.set(hook);
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(FakeChain.calling(fixture.middle, () -> {
            fixture.stateMachine.setMtoFinFastFreeze(true);
            return Boolean.TRUE;
        }), HansRefreezeHook.M_TO_F_MESSAGE);

        mode.set(RefreezeMode.DISABLED);
        publishDuringDecision.set(true);
        FakeChain fastExit = FakeChain.calling(fixture.middle, () -> {
            fixture.stateMachine.setMtoFinFastFreeze(false);
            return Boolean.FALSE;
        });
        assertEquals(Boolean.FALSE, hook.interceptMiddleProcess(
                fastExit, HansRefreezeHook.FAST_FREEZE_EXIT_MESSAGE));
        assertEquals(1, fastExit.proceedCalls());
        assertTrue(registry.blockedCycles().isEmpty());
        assertFalse(fixture.stateMachine.isMtoFinFastFreeze());
    }

    @Test
    public void authoritativeRuntimePublicationClosesTheAsyncListenerWindow()
            throws Throwable {
        RuntimeStateSequence states = runtimeStateSequence();
        AtomicReference<ResidencyRuntimeState> authoritative =
                new AtomicReference<>(states.disabledReady);
        AtomicBoolean publishDuringDecision = new AtomicBoolean();
        RefreezeDecision oldDisabled = runtimeRefreezeDecision(states.disabledReady);
        HansRefreezeHook hook = installedHook(
                (target, source) -> {
                    if (publishDuringDecision.getAndSet(false)) {
                        authoritative.set(states.systemReady);
                    }
                    return oldDisabled;
                },
                listener -> {
                    listener.onStateChanged(states.disabledReady);
                    return states.disabledReady;
                },
                authoritative::get,
                HansRefreezeHook.RecoveryExecutor.direct(),
                (runnable, delayMs) -> true,
                (stateMachine, token) -> true,
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");

        publishDuringDecision.set(true);
        FakeChain timed = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE, hook.interceptMiddleProcess(
                timed, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, timed.proceedCalls());
    }

    @Test
    public void queuedRecoveryTokenCannotCrossAnUnobservedRuntimeGeneration()
            throws Throwable {
        RuntimeStateSequence states = runtimeStateSequence();
        AtomicReference<ResidencyRuntimeState> authoritative =
                new AtomicReference<>(states.disabledReady);
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = installedHook(
                (target, source) -> runtimeRefreezeDecision(authoritative.get()),
                listener -> {
                    listener.onStateChanged(states.disabledReady);
                    return states.disabledReady;
                },
                authoritative::get,
                HansRefreezeHook.RecoveryExecutor.direct(),
                (runnable, delayMs) -> true,
                HansRefreezeStateAdapter::requestMtoF,
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE));

        authoritative.set(states.systemReady);
        hook.onRuntimeStateChanged(states.systemReady);
        Object firstToken = fixture.stateMachine.sentMessageObjects().get(0);

        authoritative.set(states.rebuildPending);
        FakeChain stale = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                stale, HansRefreezeHook.M_TO_F_MESSAGE, firstToken));
        assertEquals(0, stale.proceedCalls());
        assertEquals(1, registry.blockedCycles().size());

        authoritative.set(states.rebuildReady);
        hook.onRuntimeStateChanged(states.rebuildReady);
        assertEquals(2, fixture.stateMachine.sentMessages().size());
    }

    @Test
    public void identityRebuildPausesRecoveryUntilAStableStateIsPublished()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicLong runtimeGeneration = new AtomicLong();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false,
                        runtimeGeneration.get()),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE));

        mode.set(RefreezeMode.SYSTEM);
        AtomicReference<Runnable> identityBuild = new AtomicReference<>();
        ResidencyRuntimeController rebuilding =
                new ResidencyRuntimeController(identityBuild::set);
        rebuilding.setPackageUidSource(new PackageUidSource() {
            @Override public boolean hasCompleteUidEnumeration() { return true; }
            @Override public OptionalInt findUid(String packageName) {
                return OptionalInt.of(UID);
            }
            @Override public Set<String> findPackagesForUid(int uid) {
                return Set.of(PACKAGE);
            }
        });
        AppResidencyPolicy policy = ResidencyPresets.create(
                PACKAGE, ResidencyMode.CUSTOM);
        rebuilding.updateConfig(snapshot(policy, false));
        runtimeGeneration.set(rebuilding.state().generation());
        hook.onRuntimeStateChanged(rebuilding.state());
        assertTrue(fixture.stateMachine.sentMessages().isEmpty());

        identityBuild.get().run();
        hook.onRuntimeStateChanged(rebuilding.state());
        assertEquals(List.of(HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());

        rebuilding.invalidateIdentities();
        runtimeGeneration.set(rebuilding.state().generation());
        hook.onRuntimeStateChanged(rebuilding.state());
        FakeChain queuedDuringRebuild =
                FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                queuedDuringRebuild,
                HansRefreezeHook.M_TO_F_MESSAGE,
                fixture.stateMachine.sentMessageObjects().get(0)));
        assertEquals(0, queuedDuringRebuild.proceedCalls());
        identityBuild.get().run();
        hook.onRuntimeStateChanged(rebuilding.state());
        assertEquals(List.of(
                HansRefreezeHook.M_TO_F_MESSAGE,
                HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());
    }

    @Test
    public void latePendingSnapshotCannotRegressAReadyRuntimeGeneration()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicLong runtimeGeneration = new AtomicLong();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false,
                        runtimeGeneration.get()),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE));

        RuntimeStatePair states = pendingAndReadyRuntimeStates();
        runtimeGeneration.set(states.ready.generation());
        mode.set(RefreezeMode.SYSTEM);
        hook.onRuntimeStateChanged(states.ready);
        assertEquals(1, fixture.stateMachine.sentMessages().size());
        hook.onRuntimeStateChanged(states.pending);

        FakeChain restored = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE, hook.interceptMiddleProcess(
                restored,
                HansRefreezeHook.M_TO_F_MESSAGE,
                fixture.stateMachine.sentMessageObjects().get(0)));
        assertEquals(1, restored.proceedCalls());
    }

    @Test
    public void recoveryWaitsUntilItsDecisionGenerationIsObserved() throws Throwable {
        ResidencyTarget target =
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID);
        AtomicReference<RefreezeDecision> decision = new AtomicReference<>(
                refreezeDecision(
                        target, WakeSource.PACKET,
                        RefreezeMode.DISABLED, 0L, false));
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = hook(
                ignored -> timingDecision(target, 9_000L, false),
                (ignored, source) -> decision.get(),
                new RecordingDiagnostics(),
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE));

        RuntimeStatePair states = pendingAndReadyRuntimeStates();
        decision.set(runtimeRefreezeDecision(states.pending));
        signalStableRuntime(hook);
        assertTrue(fixture.stateMachine.sentMessages().isEmpty());
        assertEquals(1, registry.blockedCycles().size());

        decision.set(runtimeRefreezeDecision(states.ready));
        hook.onRuntimeStateChanged(states.ready);
        assertEquals(List.of(HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());
    }

    @Test
    public void fastFreezeExitRechecksPolicyAndOnlyConsumesAnOwnedCycle()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.AFTER_WAKE);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 12_000L, false),
                diagnostics,
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");

        FakeChain timed = FakeChain.calling(fixture.middle, () -> {
            fixture.stateMachine.setMtoFinFastFreeze(true);
            return Boolean.TRUE;
        });
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(timed, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, timed.proceedCalls());
        assertTrue(diagnostics.decisions.isEmpty());

        mode.set(RefreezeMode.DISABLED);
        FakeChain fastExit = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                fastExit, HansRefreezeHook.FAST_FREEZE_EXIT_MESSAGE));
        assertEquals(0, fastExit.proceedCalls());
        assertFalse(fixture.stateMachine.isMtoFinFastFreeze());
        assertEquals(1, diagnostics.decisions.size());
        assertEquals(RefreezeDecision.Action.DISABLE_REFREEZE,
                diagnostics.decisions.get(0).effectiveAction());

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);
        assertEquals(List.of(HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());
    }

    @Test
    public void forcedFreezeRevokesTimedFastFreezeOwnership() throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.AFTER_WAKE);
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 12_000L, false),
                new RecordingDiagnostics(),
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        FakeChain timed = FakeChain.calling(fixture.middle, () -> {
            fixture.stateMachine.setMtoFinFastFreeze(true);
            return Boolean.TRUE;
        });
        hook.interceptMiddleProcess(timed, HansRefreezeHook.M_TO_F_MESSAGE);
        assertTrue(registry.activeCycleOrNull(
                fixture.stateMachine).fastFreezeOwned());

        FakeChain forced = FakeChain.returning(Boolean.TRUE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                forced, HansRefreezeHook.FORCE_FREEZE_MESSAGE));
        assertEquals(1, forced.proceedCalls());
        assertFalse(registry.activeCycleOrNull(
                fixture.stateMachine).fastFreezeOwned());
        assertTrue(fixture.stateMachine.isMtoFinFastFreeze());

        mode.set(RefreezeMode.DISABLED);
        FakeChain fastExit = FakeChain.calling(fixture.middle, () -> {
            fixture.stateMachine.setMtoFinFastFreeze(false);
            return Boolean.TRUE;
        });
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                fastExit, HansRefreezeHook.FAST_FREEZE_EXIT_MESSAGE));
        assertEquals(1, fastExit.proceedCalls());
        assertTrue(registry.blockedCycles().isEmpty());
    }

    @Test
    public void forcedMessageResolutionFailureOnlyRevokesItsBoundCycle()
            throws Throwable {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.AFTER_WAKE, 12_000L, false),
                new RecordingDiagnostics(),
                registry);
        Fixture first = armedFixture(hook, "Packet");
        Fixture second = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(FakeChain.calling(first.middle, () -> {
            first.stateMachine.setMtoFinFastFreeze(true);
            return Boolean.TRUE;
        }), HansRefreezeHook.M_TO_F_MESSAGE);
        hook.interceptMiddleProcess(FakeChain.calling(second.middle, () -> {
            second.stateMachine.setMtoFinFastFreeze(true);
            return Boolean.TRUE;
        }), HansRefreezeHook.M_TO_F_MESSAGE);
        assertTrue(registry.ownsFastFreeze(first.stateMachine));
        assertTrue(registry.ownsFastFreeze(second.stateMachine));

        Object unresolvedAlias = new Object();
        assertTrue(registry.bindState(first.stateMachine, unresolvedAlias));
        FakeChain forced = FakeChain.returning(Boolean.TRUE, unresolvedAlias);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                forced, HansRefreezeHook.FORCE_FREEZE_MESSAGE));

        assertFalse(registry.ownsFastFreeze(first.stateMachine));
        assertTrue(registry.ownsFastFreeze(second.stateMachine));
    }

    @Test
    public void scheduledAndSystemDecisionsPreserveTheOemImportancePath() throws Throwable {
        AtomicReference<RefreezeMode> mode = new AtomicReference<>(RefreezeMode.AFTER_WAKE);
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 9_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 12_000L, false),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Alarm");

        FakeChain scheduled = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE,
                hook.interceptMiddleProcess(scheduled, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, scheduled.proceedCalls());

        mode.set(RefreezeMode.SYSTEM);
        FakeChain system = FakeChain.returning(Boolean.TRUE, fixture.middle);
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(system, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, system.proceedCalls());
    }

    @Test
    public void ordinaryMtoFUsesFreezeTimingAndNeverAStaleWakeReason() throws Throwable {
        AtomicInteger refreezeCalls = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> {
                    refreezeCalls.incrementAndGet();
                    return refreezeDecision(
                            target, source, RefreezeMode.DISABLED, 0L, false);
                },
                diagnostics,
                new HansRefreezeRegistry(8));

        FakeChain emptyReason = FakeChain.returning(
                2_222L, null, UID, PACKAGE, 0, 0L, "");
        assertEquals(Long.valueOf(7_000L), hook.interceptTiming(emptyReason));
        assertEquals(0, refreezeCalls.get());
        assertEquals(1, diagnostics.timingDecisions.size());
        assertEquals(FeatureIds.HANS_FREEZE_TIMING,
                diagnostics.timingEvents.get(0).feature);
        assertEquals("m_to_f_timing", diagnostics.timingEvents.get(0).scene);

        FakeChain nonNestedOldReason = FakeChain.returning(
                2_222L, null, UID, PACKAGE, 0, 0L, "Packet");
        assertEquals(Long.valueOf(2_222L), hook.interceptTiming(nonNestedOldReason));
        assertEquals(0, refreezeCalls.get());
        assertEquals(1, diagnostics.timingDecisions.size());
    }

    @Test
    public void dryRunAndInvalidDelaysKeepTheVendorDelay() throws Throwable {
        HansRefreezeHook dryRun = hook(
                target -> timingDecision(target, 7_000L, true),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.AFTER_WAKE, 12_000L, true),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        FakeChain empty = FakeChain.returning(3_333L, null, UID, PACKAGE, 0, 0L, "");
        assertEquals(Long.valueOf(3_333L), dryRun.interceptTiming(empty));

        HansRefreezeHook invalid = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.AFTER_WAKE,
                        HansTimingHook.MAX_DELAY_MS + 1L, false),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = fixture("Packet");
        AtomicReference<Object> nested = new AtomicReference<>();
        invalid.interceptFrozenToMiddle(FakeChain.calling(fixture.frozen, () -> {
            nested.set(invalid.interceptTiming(FakeChain.returning(
                    4_444L, null, UID, PACKAGE, 0, 0L, "Packet")));
            return null;
        }));
        assertEquals(Long.valueOf(4_444L), nested.get());
    }

    @Test
    public void unknownReasonFailsOpenButAChangedKnownSourceIsRebound() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.DISABLED, 0L, false),
                diagnostics,
                new HansRefreezeRegistry(8));
        Fixture unknown = fixture("futureReason");
        FakeChain frozen = FakeChain.returning(ORIGINAL, unknown.frozen);
        assertSame(ORIGINAL, hook.interceptFrozenToMiddle(frozen));
        assertEquals(1, frozen.proceedCalls());
        assertEquals(1, diagnostics.failures.size());

        Fixture changed = armedFixture(hook, "Packet");
        changed.stateMachine.setReason("Alarm");
        FakeChain message = FakeChain.returning(Boolean.FALSE, changed.middle);
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(message, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(0, message.proceedCalls());

        FakeChain repeated = FakeChain.returning(Boolean.TRUE, changed.middle);
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(repeated, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(0, repeated.proceedCalls());

        FakeChain invalidState = FakeChain.returning(Boolean.FALSE, new Object());
        assertEquals(Boolean.FALSE,
                hook.interceptMiddleProcess(
                        invalidState, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, invalidState.proceedCalls());
        assertEquals(2, diagnostics.failures.size());
        assertEquals(2, diagnostics.decisions.size());
    }

    @Test
    public void fastFreezeExitUsesAChangedKnownSourceFromTheSameMiddleCycle()
            throws Throwable {
        List<WakeSource> observedSources = new ArrayList<>();
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> {
                    observedSources.add(source);
                    return refreezeDecision(
                            target,
                            source,
                            source == WakeSource.ALARM
                                    ? RefreezeMode.DISABLED
                                    : RefreezeMode.AFTER_WAKE,
                            12_000L,
                            false);
                },
                new RecordingDiagnostics(),
                registry);
        Fixture fixture = armedFixture(hook, "Packet");

        FakeChain timed = FakeChain.calling(fixture.middle, () -> {
            fixture.stateMachine.setMtoFinFastFreeze(true);
            return Boolean.TRUE;
        });
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(timed, HansRefreezeHook.M_TO_F_MESSAGE));
        long packetGeneration = registry.activeCycleOrNull(
                fixture.stateMachine).generation();
        observedSources.clear();

        fixture.stateMachine.setReason("Alarm");
        FakeChain fastExit = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                fastExit, HansRefreezeHook.FAST_FREEZE_EXIT_MESSAGE));

        assertEquals(List.of(WakeSource.ALARM), observedSources);
        assertEquals(0, fastExit.proceedCalls());
        assertFalse(fixture.stateMachine.isMtoFinFastFreeze());
        assertFalse(registry.ownsFastFreeze(fixture.stateMachine));
        assertTrue(registry.activeCycleOrNull(
                fixture.stateMachine).generation() > packetGeneration);
    }

    @Test
    public void onlyMessageEightIsEligibleAndMiddleExitClearsState() throws Throwable {
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.DISABLED, 0L, false),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");

        FakeChain forced = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE, hook.interceptMiddleProcess(forced, 21));
        assertEquals(1, forced.proceedCalls());

        fixture.stateMachine.setMtoFinFastFreeze(true);
        FakeChain forcedFastExit = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE, hook.interceptMiddleProcess(
                forcedFastExit, HansRefreezeHook.FAST_FREEZE_EXIT_MESSAGE));
        assertEquals(1, forcedFastExit.proceedCalls());

        FakeChain exit = FakeChain.returning(ORIGINAL, fixture.middle);
        assertSame(ORIGINAL, hook.interceptMiddleExit(exit));
        FakeChain afterExit = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE,
                hook.interceptMiddleProcess(afterExit, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, afterExit.proceedCalls());
    }

    @Test
    public void registryCapacityFailureKeepsNestedGetterAndMessageFailOpen()
            throws Throwable {
        HansRefreezeRegistry registry = new HansRefreezeRegistry(1);
        Object occupied = new Object();
        registry.arm(
                occupied,
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID),
                WakeSource.PACKET);
        HansRefreezeHook hook = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.DISABLED, 0L, false),
                new RecordingDiagnostics(),
                registry);
        Fixture fixture = fixture("Packet");
        AtomicReference<Object> nested = new AtomicReference<>();

        hook.interceptFrozenToMiddle(FakeChain.calling(fixture.frozen, () -> {
            nested.set(hook.interceptTiming(FakeChain.returning(
                    5_555L, null, UID, PACKAGE, 0, 0L, "Packet")));
            return null;
        }));
        assertEquals(Long.valueOf(5_555L), nested.get());
        hook.interceptMiddleEnter(FakeChain.returning(null, fixture.middle));
        FakeChain message = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE,
                hook.interceptMiddleProcess(message, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, message.proceedCalls());
    }

    @Test
    public void partialInstallationLeavesEveryCallbackInactive() throws Throwable {
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        HansRefreezeHook hook = new HansRefreezeHook(
                target -> {
                    decisions.incrementAndGet();
                    return timingDecision(target, 7_000L, false);
                },
                (target, source) -> {
                    decisions.incrementAndGet();
                    return refreezeDecision(
                            target, source, RefreezeMode.DISABLED, 0L, false);
                },
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8),
                (className, loader, matcher, hooker) ->
                        installs.incrementAndGet() == 3 ? 0 : 1);

        assertEquals(
                "FAILED_FAIL_OPEN:PARTIAL=MIDDLE_ENTER",
                hook.install(OplusHansPackage.class.getClassLoader()));
        FakeChain timing = FakeChain.returning(
                6_666L, null, UID, PACKAGE, 0, 0L, "");
        assertEquals(Long.valueOf(6_666L), hook.interceptTiming(timing));
        assertEquals(1, timing.proceedCalls());
        assertEquals(0, decisions.get());
    }

    @Test
    public void successfulInstallationRequiresAllFiveMethods() throws Throwable {
        AtomicInteger installs = new AtomicInteger();
        HansRefreezeHook hook = new HansRefreezeHook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.DISABLED, 0L, false),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8),
                (className, loader, matcher, hooker) -> {
                    installs.incrementAndGet();
                    return 1;
                });

        assertEquals("INSTALLED:timing=1,state=4",
                hook.install(OplusHansPackage.class.getClassLoader()));
        assertEquals(5, installs.get());
        FakeChain timing = FakeChain.returning(
                6_666L, null, UID, PACKAGE, 0, 0L, "");
        assertEquals(Long.valueOf(7_000L), hook.interceptTiming(timing));
    }

    @Test
    public void listenerRegistrationFailureLeavesAllInstalledCallbacksInactive()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        AtomicReference<HansRefreezeHook> hookReference = new AtomicReference<>();
        AtomicReference<Object> callbackResult = new AtomicReference<>();
        AtomicReference<FakeChain> callbackChain = new AtomicReference<>();
        HansRefreezeHook hook = new HansRefreezeHook(
                target -> {
                    decisions.incrementAndGet();
                    return timingDecision(target, 7_000L, false);
                },
                (target, source) -> {
                    decisions.incrementAndGet();
                    return refreezeDecision(
                            target, source, RefreezeMode.DISABLED, 0L, false);
                },
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8),
                (className, loader, matcher, hooker) -> 1,
                listener -> {
                    FakeChain duringRegistration = FakeChain.returning(
                            6_111L, null, UID, PACKAGE, 0, 0L, "");
                    callbackChain.set(duringRegistration);
                    try {
                        callbackResult.set(hookReference.get().interceptTiming(
                                duringRegistration));
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                    throw new IllegalStateException("registration");
                },
                HansRefreezeHook.RecoveryExecutor.direct(),
                (stateMachine, token) -> true);
        hookReference.set(hook);

        assertEquals("FAILED_FAIL_OPEN:RECOVERY_LISTENER",
                hook.install(OplusHansPackage.class.getClassLoader()));
        assertEquals(Long.valueOf(6_111L), callbackResult.get());
        assertEquals(1, callbackChain.get().proceedCalls());
        FakeChain timing = FakeChain.returning(
                6_666L, null, UID, PACKAGE, 0, 0L, "");
        assertEquals(Long.valueOf(6_666L), hook.interceptTiming(timing));
        assertEquals(1, timing.proceedCalls());
        assertEquals(0, decisions.get());
    }

    @Test
    public void callbacksActivateOnlyAfterSuccessfulListenerRegistration()
            throws Throwable {
        AtomicReference<HansRefreezeHook> hookReference = new AtomicReference<>();
        AtomicReference<Object> duringRegistrationResult = new AtomicReference<>();
        ResidencyRuntimeState initial = publishedState(RecoveryPolicy.SYSTEM_MODE);
        HansRefreezeHook hook = new HansRefreezeHook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.DISABLED, 0L, false),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8),
                (className, loader, matcher, hooker) -> 1,
                listener -> {
                    listener.onStateChanged(initial);
                    try {
                        duringRegistrationResult.set(
                                hookReference.get().interceptTiming(FakeChain.returning(
                                        6_222L, null, UID, PACKAGE, 0, 0L, "")));
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                    return initial;
                },
                HansRefreezeHook.RecoveryExecutor.direct(),
                HansRefreezeStateAdapter::requestMtoF);
        hookReference.set(hook);

        assertEquals("INSTALLED:timing=1,state=4",
                hook.install(OplusHansPackage.class.getClassLoader()));
        assertEquals(Long.valueOf(6_222L), duringRegistrationResult.get());
        FakeChain afterRegistration = FakeChain.returning(
                6_222L, null, UID, PACKAGE, 0, 0L, "");
        assertEquals(Long.valueOf(7_000L), hook.interceptTiming(afterRegistration));
    }

    @Test
    public void rejectedRecoveryDispatchFallsBackToABoundedRetry() throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        FlakyRecoveryExecutor executor = new FlakyRecoveryExecutor(1);
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                executor,
                HansRefreezeStateAdapter::requestMtoF,
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);

        assertEquals(2, executor.executeCalls.get());
        assertEquals(1, executor.scheduleCalls.get());
        assertEquals(List.of(HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());
    }

    @Test
    public void failedRecoverySendRetriesAndPersistentFailureIsBounded()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicInteger transientCalls = new AtomicInteger();
        HansRefreezeHook transientFailure = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                HansRefreezeHook.RecoveryExecutor.direct(),
                (stateMachine, token) -> transientCalls.incrementAndGet() > 1
                        && HansRefreezeStateAdapter.requestMtoF(stateMachine, token),
                new HansRefreezeRegistry(8));
        Fixture recovered = armedFixture(transientFailure, "Packet");
        transientFailure.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, recovered.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);
        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(transientFailure);
        assertEquals(2, transientCalls.get());
        assertEquals(1, recovered.stateMachine.sentMessages().size());

        AtomicInteger persistentCalls = new AtomicInteger();
        AtomicReference<RefreezeMode> persistentMode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        HansRefreezeRegistry persistentRegistry = new HansRefreezeRegistry(8);
        HansRefreezeHook persistentFailure = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, persistentMode.get(), 0L, false),
                HansRefreezeHook.RecoveryExecutor.direct(),
                (stateMachine, token) -> {
                    persistentCalls.incrementAndGet();
                    return false;
                },
                persistentRegistry);
        Fixture blocked = armedFixture(persistentFailure, "Packet");
        persistentFailure.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, blocked.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);
        persistentMode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(persistentFailure);
        assertEquals(6, persistentCalls.get());
        assertEquals(1, persistentRegistry.blockedCycles().size());
        assertEquals(6, persistentRegistry.blockedCycles().get(0).retryAttempt());
    }

    @Test
    public void rejectedRetrySchedulingConsumesTheSameBoundedRetryBudget()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger schedules = new AtomicInteger();
        HansRefreezeHook.RecoveryExecutor executor =
                new HansRefreezeHook.RecoveryExecutor() {
                    @Override
                    public boolean execute(Runnable runnable) {
                        runnable.run();
                        return true;
                    }

                    @Override
                    public boolean schedule(Runnable runnable, long delayMs) {
                        schedules.incrementAndGet();
                        return false;
                    }
                };
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                executor,
                (stateMachine, token) -> {
                    sends.incrementAndGet();
                    return false;
                },
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);

        assertEquals(6, sends.get());
        assertEquals(5, schedules.get());
        assertEquals(1, registry.blockedCycles().size());
        assertEquals(6, registry.blockedCycles().get(0).retryAttempt());
    }

    @Test
    public void recoverySenderExceptionUsesTheSameBoundedRetryPath()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicInteger calls = new AtomicInteger();
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                HansRefreezeHook.RecoveryExecutor.direct(),
                (stateMachine, token) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("sender");
                    }
                    return HansRefreezeStateAdapter.requestMtoF(stateMachine, token);
                },
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);

        assertEquals(2, calls.get());
        assertEquals(List.of(HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());
    }

    @Test
    public void recoveryResolutionFailureReturnsTheCycleToItsRetryPath()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        ManualRecoveryExecutor executor = new ManualRecoveryExecutor();
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                executor,
                HansRefreezeStateAdapter::requestMtoF,
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);
        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);
        Object token = fixture.stateMachine.sentMessageObjects().get(0);

        FakeChain unresolved = FakeChain.returning(Boolean.FALSE, new Object());
        assertEquals(Boolean.TRUE, hook.interceptMiddleProcess(
                unresolved, HansRefreezeHook.M_TO_F_MESSAGE, token));
        assertEquals(0, unresolved.proceedCalls());
        assertEquals(1, executor.scheduled.size());

        executor.runScheduled();
        assertEquals(2, fixture.stateMachine.sentMessages().size());
    }

    @Test
    public void unconfirmedRecoveryDeliveryTimesOutIntoABoundedRetry()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicInteger sends = new AtomicInteger();
        ManualRecoveryExecutor retryExecutor = new ManualRecoveryExecutor();
        ManualConfirmationScheduler confirmations =
                new ManualConfirmationScheduler();
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                listener -> null,
                () -> null,
                retryExecutor,
                confirmations,
                (stateMachine, token) -> {
                    sends.incrementAndGet();
                    return true;
                },
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);
        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);
        assertEquals(1, sends.get());
        assertEquals(List.of(2_000L), List.copyOf(confirmations.delays));

        confirmations.runNext();
        assertEquals(List.of(100L), List.copyOf(retryExecutor.scheduledDelays));
        retryExecutor.runScheduled();
        assertEquals(2, sends.get());
    }

    @Test
    public void multipleRecoveryFailuresKeepIndependentRetryTimers() throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicInteger sends = new AtomicInteger();
        ManualRecoveryExecutor executor = new ManualRecoveryExecutor();
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                executor,
                (stateMachine, token) -> {
                    sends.incrementAndGet();
                    return false;
                },
                registry);
        Fixture first = armedFixture(hook, "Packet");
        Fixture second = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, first.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, second.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);
        assertEquals(2, sends.get());
        assertEquals(2, executor.scheduled.size());
        assertEquals(List.of(100L, 100L), List.copyOf(executor.scheduledDelays));

        executor.runScheduled();
        assertEquals(3, sends.get());
        assertEquals(2, executor.scheduled.size());
        executor.runScheduled();
        assertEquals(4, sends.get());
        assertEquals(2, executor.scheduled.size());
    }

    @Test
    public void retryIsCancelledWhenPolicyDisablesRecoveryOrCycleExits()
            throws Throwable {
        AtomicReference<RefreezeMode> mode =
                new AtomicReference<>(RefreezeMode.DISABLED);
        AtomicInteger sends = new AtomicInteger();
        ManualRecoveryExecutor executor = new ManualRecoveryExecutor();
        HansRefreezeRegistry registry = new HansRefreezeRegistry(8);
        HansRefreezeHook hook = installedHook(
                (target, source) -> refreezeDecision(
                        target, source, mode.get(), 0L, false),
                executor,
                (stateMachine, token) -> {
                    sends.incrementAndGet();
                    return false;
                },
                registry);
        Fixture fixture = armedFixture(hook, "Packet");
        hook.interceptMiddleProcess(
                FakeChain.returning(Boolean.FALSE, fixture.middle),
                HansRefreezeHook.M_TO_F_MESSAGE);

        mode.set(RefreezeMode.SYSTEM);
        signalStableRuntime(hook);
        assertEquals(1, sends.get());
        assertEquals(1, executor.scheduled.size());

        mode.set(RefreezeMode.DISABLED);
        executor.runScheduled();
        assertEquals(1, sends.get());
        assertEquals(1, registry.blockedCycles().size());
        hook.interceptMiddleExit(FakeChain.returning(null, fixture.middle));
        signalStableRuntime(hook);
        assertTrue(registry.blockedCycles().isEmpty());
        assertEquals(1, sends.get());
    }

    @Test
    public void stableSourceCodesCoverEveryWakeSource() {
        Set<Integer> codes = new HashSet<>();
        for (WakeSource source : WakeSource.values()) {
            int code = HansRefreezeHook.sourceCode(source);
            assertTrue(code > 0);
            assertTrue("duplicate code for " + source, codes.add(code));
        }
        assertEquals(HansRefreezeHook.UNKNOWN_SOURCE_CODE,
                HansRefreezeHook.sourceCode(null));
    }

    @Test
    public void originalFailuresPropagateAndClearTransitionState() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        HansRefreezeHook hook = hook(
                target -> {
                    decisions.incrementAndGet();
                    return timingDecision(target, 7_000L, false);
                },
                (target, source) -> {
                    decisions.incrementAndGet();
                    return refreezeDecision(
                            target, source, RefreezeMode.DISABLED, 0L, false);
                },
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        OriginalFailure failure = new OriginalFailure();

        OriginalFailure timingFailure = assertThrows(OriginalFailure.class, () ->
                hook.interceptTiming(FakeChain.calling(null, () -> {
                    throw failure;
                })));
        assertSame(failure, timingFailure);
        assertEquals(0, decisions.get());

        Fixture fixture = fixture("Packet");
        OriginalFailure transitionFailure = assertThrows(OriginalFailure.class, () ->
                hook.interceptFrozenToMiddle(FakeChain.calling(fixture.frozen, () -> {
                    hook.interceptTiming(FakeChain.returning(
                            1_111L, null, UID, PACKAGE, 0, 0L, "Packet"));
                    throw failure;
                })));
        assertSame(failure, transitionFailure);

        hook.interceptMiddleEnter(FakeChain.returning(null, fixture.middle));
        FakeChain message = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE,
                hook.interceptMiddleProcess(message, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, message.proceedCalls());
    }

    @Test
    public void decisionAndDiagnosticsFailuresNeverBlockTheOemPath() throws Throwable {
        HansRefreezeHook decisionFailure = hook(
                target -> { throw new IllegalStateException("timing"); },
                (target, source) -> { throw new IllegalStateException("refreeze"); },
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(decisionFailure, "Packet");
        FakeChain message = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE,
                decisionFailure.interceptMiddleProcess(
                        message, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, message.proceedCalls());

        HansRefreezeHook diagnosticsFailure = hook(
                target -> timingDecision(target, 7_000L, false),
                (target, source) -> refreezeDecision(
                        target, source, RefreezeMode.DISABLED, 0L, false),
                new ThrowingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture disabled = armedFixture(diagnosticsFailure, "Packet");
        FakeChain blocked = FakeChain.returning(Boolean.FALSE, disabled.middle);
        assertEquals(Boolean.TRUE,
                diagnosticsFailure.interceptMiddleProcess(
                        blocked, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(0, blocked.proceedCalls());
    }

    private static HansRefreezeHook hook(
            HansRefreezeHook.FreezeTimingDecisionResolver timing,
            HansRefreezeHook.RefreezeDecisionResolver refreeze,
            RecordingDiagnostics diagnostics,
            HansRefreezeRegistry registry) {
        return new HansRefreezeHook(timing, refreeze, diagnostics, registry);
    }

    private static HansRefreezeHook installedHook(
            HansRefreezeHook.RefreezeDecisionResolver refreeze,
            HansRefreezeHook.RecoveryExecutor recoveryExecutor,
            HansRefreezeHook.RecoveryMessageSender recoveryMessageSender,
            HansRefreezeRegistry registry) {
        return installedHook(
                refreeze,
                listener -> null,
                () -> null,
                recoveryExecutor,
                (runnable, delayMs) -> true,
                recoveryMessageSender,
                registry);
    }

    private static HansRefreezeHook installedHook(
            HansRefreezeHook.RefreezeDecisionResolver refreeze,
            HansRefreezeHook.RuntimeStateListenerRegistrar registrar,
            HansRefreezeHook.RuntimeStateSource runtimeStateSource,
            HansRefreezeHook.RecoveryExecutor recoveryExecutor,
            HansRefreezeHook.RecoveryConfirmationScheduler confirmationScheduler,
            HansRefreezeHook.RecoveryMessageSender recoveryMessageSender,
            HansRefreezeRegistry registry) {
        HansRefreezeHook hook = new HansRefreezeHook(
                target -> timingDecision(target, 9_000L, false),
                refreeze,
                new RecordingDiagnostics(),
                registry,
                (className, loader, matcher, hooker) -> 1,
                registrar,
                runtimeStateSource,
                recoveryExecutor,
                confirmationScheduler,
                recoveryMessageSender);
        assertEquals("INSTALLED:timing=1,state=4",
                hook.install(OplusHansPackage.class.getClassLoader()));
        return hook;
    }

    private static void assertConsumedMessageRecovery(RecoveryPolicy recoveryPolicy)
            throws Throwable {
        ResidencyTarget target =
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID);
        AtomicReference<RefreezeDecision> decision = new AtomicReference<>(
                refreezeDecision(
                        target, WakeSource.PACKET, RefreezeMode.DISABLED, 0L, false));
        HansRefreezeHook hook = hook(
                ignored -> timingDecision(target, 9_000L, false),
                (ignored, source) -> decision.get(),
                new RecordingDiagnostics(),
                new HansRefreezeRegistry(8));
        Fixture fixture = armedFixture(hook, "Packet");

        FakeChain blocked = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.TRUE,
                hook.interceptMiddleProcess(blocked, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(0, blocked.proceedCalls());

        decision.set(recoveryDecision(target, WakeSource.PACKET, recoveryPolicy));
        signalStableRuntime(hook);
        assertEquals(List.of(HansRefreezeHook.M_TO_F_MESSAGE),
                fixture.stateMachine.sentMessages());
        signalStableRuntime(hook);
        assertEquals(1, fixture.stateMachine.sentMessages().size());

        FakeChain restored = FakeChain.returning(Boolean.FALSE, fixture.middle);
        assertEquals(Boolean.FALSE,
                hook.interceptMiddleProcess(restored, HansRefreezeHook.M_TO_F_MESSAGE));
        assertEquals(1, restored.proceedCalls());
    }

    private static void signalStableRuntime(HansRefreezeHook hook) {
        hook.onRuntimeStateChanged(
                new ResidencyRuntimeController(Runnable::run).state());
    }

    private static Fixture armedFixture(HansRefreezeHook hook, String reason)
            throws Throwable {
        Fixture fixture = fixture(reason);
        armFixture(hook, fixture, reason);
        return fixture;
    }

    private static void armFixture(
            HansRefreezeHook hook, Fixture fixture, String reason) throws Throwable {
        fixture.stateMachine.setReason(reason);
        hook.interceptFrozenToMiddle(FakeChain.calling(fixture.frozen, () -> {
            hook.interceptTiming(FakeChain.returning(
                    1_111L, null, UID, PACKAGE, 0, 0L, reason));
            return null;
        }));
        hook.interceptMiddleEnter(FakeChain.returning(null, fixture.middle));
    }

    private static Fixture fixture(String reason) {
        OplusHansPackage hansPackage = new OplusHansPackage(
                UID, PACKAGE, true, false, new Object());
        HansAppStateMachine stateMachine = new HansAppStateMachine(hansPackage, reason);
        return new Fixture(
                stateMachine,
                stateMachine.new MiddleState(),
                stateMachine.new FrozenState());
    }

    private static FreezeTimingDecision timingDecision(
            ResidencyTarget target, long delayMs, boolean dryRun) {
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        FreezePolicy freeze = new FreezePolicy(
                true, true, true, true, true, 5_000L, delayMs,
                false, false, false);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE, true, ResidencyMode.CUSTOM,
                base.killPolicy(), freeze, base.wakePolicy(),
                base.resourcePolicy(), base.refreezePolicy());
        RuntimeConfigSnapshot snapshot = snapshot(policy, dryRun);
        return new ResidencyDecisionEngine(snapshot, index(snapshot)).decideFreezeTiming(
                new FreezeTimingContext(
                        target,
                        FreezeTimingContext.Transition.M_TO_F,
                        FeatureIds.HANS_REFREEZE,
                        HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static RefreezeDecision refreezeDecision(
            ResidencyTarget target,
            WakeSource source,
            RefreezeMode mode,
            long delayMs,
            boolean dryRun) {
        return refreezeDecision(target, source, mode, delayMs, dryRun, 0L);
    }

    private static RefreezeDecision refreezeDecision(
            ResidencyTarget target,
            WakeSource source,
            RefreezeMode mode,
            long delayMs,
            boolean dryRun,
            long identityGeneration) {
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        RefreezePolicy refreeze = new RefreezePolicy(
                mode,
                delayMs, delayMs, delayMs, delayMs, delayMs, delayMs, delayMs);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE, true, ResidencyMode.CUSTOM,
                base.killPolicy(), base.freezePolicy(), base.wakePolicy(),
                base.resourcePolicy(), refreeze);
        RuntimeConfigSnapshot snapshot = snapshot(policy, dryRun);
        return new ResidencyDecisionEngine(
                snapshot, index(snapshot), identityGeneration).decideRefreeze(
                new RefreezeContext(
                        target,
                        source,
                        FeatureIds.HANS_REFREEZE,
                        HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static RefreezeDecision recoveryDecision(
            ResidencyTarget target,
            WakeSource source,
            RecoveryPolicy recoveryPolicy) {
        RuntimeConfigSnapshot snapshot = recoverySnapshot(recoveryPolicy);
        return new ResidencyDecisionEngine(snapshot, index(snapshot)).decideRefreeze(
                new RefreezeContext(
                        target,
                        source,
                        FeatureIds.HANS_REFREEZE,
                        HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static ResidencyRuntimeState publishedState(
            RecoveryPolicy recoveryPolicy) {
        ResidencyRuntimeController controller =
                new ResidencyRuntimeController(Runnable::run);
        controller.setPackageUidSource(new PackageUidSource() {
            @Override public boolean hasCompleteUidEnumeration() { return true; }
            @Override public OptionalInt findUid(String packageName) {
                return OptionalInt.of(UID);
            }
            @Override public Set<String> findPackagesForUid(int uid) {
                return Set.of(PACKAGE);
            }
        });
        controller.updateConfig(recoverySnapshot(recoveryPolicy));
        return controller.state();
    }

    private static RuntimeStatePair pendingAndReadyRuntimeStates() {
        AtomicReference<Runnable> identityBuild = new AtomicReference<>();
        ResidencyRuntimeController controller =
                new ResidencyRuntimeController(identityBuild::set);
        controller.setPackageUidSource(new PackageUidSource() {
            @Override public boolean hasCompleteUidEnumeration() { return true; }
            @Override public OptionalInt findUid(String packageName) {
                return OptionalInt.of(UID);
            }
            @Override public Set<String> findPackagesForUid(int uid) {
                return Set.of(PACKAGE);
            }
        });
        AppResidencyPolicy policy = ResidencyPresets.create(
                PACKAGE, ResidencyMode.CUSTOM);
        controller.updateConfig(snapshot(policy, false));
        ResidencyRuntimeState pending = controller.state();
        identityBuild.get().run();
        ResidencyRuntimeState ready = controller.state();
        assertEquals(pending.generation(), ready.generation());
        return new RuntimeStatePair(pending, ready);
    }

    private static RuntimeStateSequence runtimeStateSequence() {
        AtomicReference<Runnable> identityBuild = new AtomicReference<>();
        ResidencyRuntimeController controller =
                new ResidencyRuntimeController(identityBuild::set);
        controller.setPackageUidSource(new PackageUidSource() {
            @Override public boolean hasCompleteUidEnumeration() { return true; }
            @Override public OptionalInt findUid(String packageName) {
                return OptionalInt.of(UID);
            }
            @Override public Set<String> findPackagesForUid(int uid) {
                return Set.of(PACKAGE);
            }
        });

        controller.updateConfig(snapshot(policy(RefreezeMode.DISABLED), false));
        identityBuild.get().run();
        ResidencyRuntimeState disabledReady = controller.state();

        controller.updateConfig(snapshot(policy(RefreezeMode.SYSTEM), false));
        identityBuild.get().run();
        ResidencyRuntimeState systemReady = controller.state();

        controller.invalidateIdentities();
        ResidencyRuntimeState rebuildPending = controller.state();
        identityBuild.get().run();
        ResidencyRuntimeState rebuildReady = controller.state();
        return new RuntimeStateSequence(
                disabledReady, systemReady, rebuildPending, rebuildReady);
    }

    private static AppResidencyPolicy policy(RefreezeMode mode) {
        AppResidencyPolicy base = ResidencyPresets.create(
                PACKAGE, ResidencyMode.CUSTOM);
        long delay = mode == RefreezeMode.AFTER_WAKE ? 5_000L : 0L;
        RefreezePolicy refreeze = new RefreezePolicy(
                mode, delay, delay, delay, delay, delay, delay, delay);
        return new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                base.freezePolicy(),
                base.wakePolicy(),
                base.resourcePolicy(),
                refreeze);
    }

    private static RefreezeDecision runtimeRefreezeDecision(
            ResidencyRuntimeState state) {
        return state.decisionEngine().decideRefreeze(new RefreezeContext(
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID),
                WakeSource.PACKET,
                FeatureIds.HANS_REFREEZE,
                HookTargetIds.HANS_M_TO_F_TIMING));
    }

    private static RuntimeConfigSnapshot recoverySnapshot(
            RecoveryPolicy recoveryPolicy) {
        switch (recoveryPolicy) {
            case MASTER_OFF: {
                AppResidencyPolicy policy = ResidencyPresets.create(
                        PACKAGE, ResidencyMode.CUSTOM);
                return RuntimeConfigSnapshot.available(
                        2L, false, true, false, 3L, Map.of(PACKAGE, policy));
            }
            case RULE_DELETED:
                return RuntimeConfigSnapshot.available(
                        2L, true, true, false, 3L, Map.of());
            case SYSTEM_MODE: {
                AppResidencyPolicy policy = ResidencyPresets.create(
                        PACKAGE, ResidencyMode.SYSTEM);
                return RuntimeConfigSnapshot.available(
                        2L, true, true, false, 3L, Map.of(PACKAGE, policy));
            }
            default:
                throw new AssertionError(recoveryPolicy);
        }
    }

    private static RuntimeConfigSnapshot snapshot(
            AppResidencyPolicy policy, boolean dryRun) {
        return RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
    }

    private static ResidencyIdentityIndex index(RuntimeConfigSnapshot snapshot) {
        return ResidencyIdentityIndex.build(snapshot, Map.of(PACKAGE, UID));
    }

    private static final class Fixture {
        private final HansAppStateMachine stateMachine;
        private final HansAppStateMachine.MiddleState middle;
        private final HansAppStateMachine.FrozenState frozen;

        private Fixture(
                HansAppStateMachine stateMachine,
                HansAppStateMachine.MiddleState middle,
                HansAppStateMachine.FrozenState frozen) {
            this.stateMachine = stateMachine;
            this.middle = middle;
            this.frozen = frozen;
        }
    }

    private static final class RuntimeStatePair {
        private final ResidencyRuntimeState pending;
        private final ResidencyRuntimeState ready;

        private RuntimeStatePair(
                ResidencyRuntimeState pending, ResidencyRuntimeState ready) {
            this.pending = pending;
            this.ready = ready;
        }
    }

    private static final class RuntimeStateSequence {
        private final ResidencyRuntimeState disabledReady;
        private final ResidencyRuntimeState systemReady;
        private final ResidencyRuntimeState rebuildPending;
        private final ResidencyRuntimeState rebuildReady;

        private RuntimeStateSequence(
                ResidencyRuntimeState disabledReady,
                ResidencyRuntimeState systemReady,
                ResidencyRuntimeState rebuildPending,
                ResidencyRuntimeState rebuildReady) {
            this.disabledReady = disabledReady;
            this.systemReady = systemReady;
            this.rebuildPending = rebuildPending;
            this.rebuildReady = rebuildReady;
        }
    }

    private static class RecordingDiagnostics
            implements HansRefreezeHook.RefreezeDiagnosticsRecorder {
        private final List<HookEvent> events = new ArrayList<>();
        private final List<RefreezeDecision> decisions = new ArrayList<>();
        private final List<HookEvent> timingEvents = new ArrayList<>();
        private final List<FreezeTimingDecision> timingDecisions = new ArrayList<>();
        private final List<HookEvent> failures = new ArrayList<>();

        @Override
        public void recordDecision(HookEvent event, RefreezeDecision decision) {
            events.add(event);
            decisions.add(decision);
        }

        @Override
        public void recordTimingDecision(
                HookEvent event, FreezeTimingDecision decision) {
            timingEvents.add(event);
            timingDecisions.add(decision);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            failures.add(event);
        }
    }

    private static final class ThrowingDiagnostics extends RecordingDiagnostics {
        @Override
        public void recordDecision(HookEvent event, RefreezeDecision decision) {
            throw new IllegalStateException("diagnostics");
        }

        @Override
        public void recordTimingDecision(
                HookEvent event, FreezeTimingDecision decision) {
            throw new IllegalStateException("diagnostics");
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            throw new IllegalStateException("diagnostics");
        }
    }

    private static final class OriginalFailure extends Exception {}

    private static final class FlakyRecoveryExecutor
            implements HansRefreezeHook.RecoveryExecutor {
        private final AtomicInteger remainingRejections;
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger scheduleCalls = new AtomicInteger();

        private FlakyRecoveryExecutor(int rejections) {
            remainingRejections = new AtomicInteger(rejections);
        }

        @Override
        public boolean execute(Runnable runnable) {
            executeCalls.incrementAndGet();
            if (remainingRejections.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                return false;
            }
            runnable.run();
            return true;
        }

        @Override
        public boolean schedule(Runnable runnable, long delayMs) {
            scheduleCalls.incrementAndGet();
            runnable.run();
            return true;
        }
    }

    private static final class ManualRecoveryExecutor
            implements HansRefreezeHook.RecoveryExecutor {
        private final Queue<Runnable> scheduled = new ArrayDeque<>();
        private final Queue<Long> scheduledDelays = new ArrayDeque<>();

        @Override
        public boolean execute(Runnable runnable) {
            runnable.run();
            return true;
        }

        @Override
        public boolean schedule(Runnable runnable, long delayMs) {
            scheduled.add(runnable);
            scheduledDelays.add(delayMs);
            return true;
        }

        private void runScheduled() {
            scheduledDelays.remove();
            scheduled.remove().run();
        }
    }

    private static final class ManualConfirmationScheduler
            implements HansRefreezeHook.RecoveryConfirmationScheduler {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Queue<Long> delays = new ArrayDeque<>();

        @Override
        public boolean schedule(Runnable runnable, long delayMs) {
            tasks.add(runnable);
            delays.add(delayMs);
            return true;
        }

        private void runNext() {
            delays.remove();
            tasks.remove().run();
        }
    }

    private enum RecoveryPolicy {
        MASTER_OFF,
        RULE_DELETED,
        SYSTEM_MODE
    }

    private interface ProceedCall {
        Object proceed() throws Throwable;
    }

    private static final class FakeChain implements XposedInterface.Chain {
        private final Object thisObject;
        private final List<Object> args;
        private final ProceedCall proceedCall;
        private int proceedCalls;

        private FakeChain(
                Object thisObject, List<Object> args, ProceedCall proceedCall) {
            this.thisObject = thisObject;
            this.args = args;
            this.proceedCall = proceedCall;
        }

        private static FakeChain returning(
                Object result, Object thisObject, Object... args) {
            return new FakeChain(thisObject, Arrays.asList(args), () -> result);
        }

        private static FakeChain calling(Object thisObject, ProceedCall proceedCall) {
            return new FakeChain(thisObject, List.of(), proceedCall);
        }

        private int proceedCalls() { return proceedCalls; }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return thisObject; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }

        @Override
        public Object proceed() throws Throwable {
            proceedCalls++;
            return proceedCall.proceed();
        }

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
