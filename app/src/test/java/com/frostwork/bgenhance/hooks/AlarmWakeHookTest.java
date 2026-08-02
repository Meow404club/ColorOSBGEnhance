package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import com.android.server.alarm.Alarm;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.alarm.AlarmDeferredRegistry;
import com.frostwork.bgenhance.hooks.alarm.AlarmObjectAdapter;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.BeforeClass;
import org.junit.Test;

public final class AlarmWakeHookTest {
    private static final String OWNER_PACKAGE = "com.example.alarm.owner";
    private static final String SHARED_PACKAGE = "com.example.alarm.shared";
    private static final String SCHEDULER_PACKAGE = "android";
    private static final String STATS_TAG = "*walarm*:calendar";
    private static final int OWNER_UID = 10_902;
    private static final int SCHEDULER_UID = 1_000;
    private static final Object EXTENSION = new Object();
    private static final Object HANS_MANAGER = new Object();

    @BeforeClass
    public static void prepareAlarmAccessors() {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));
    }

    @Test
    public void blockDefersHansAndCompletesOnlyAtFinalGate() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
        });
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);

        StagedCall staged = stage(harness, alarm);

        assertEquals(Boolean.FALSE, staged.checkValue);
        assertEquals(1, staged.filter.proceedCalls());
        assertEquals(0, staged.check.proceedCalls());
        assertEquals(1, decisions.get());
        assertTrue(harness.diagnostics.records.isEmpty());
        assertTrue(harness.diagnostics.failOpenEvents.isEmpty());
        assertTrue(harness.diagnostics.runtimeFailOpenEvents.isEmpty());

        DeliveryCall delivery = deliver(harness, alarm, Boolean.FALSE);

        assertEquals(Boolean.TRUE, delivery.value);
        assertEquals(1, delivery.chain.proceedCalls());
        assertEquals(2, decisions.get());
        assertEquals(1, harness.completion.finishCount.get());
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(1, harness.diagnostics.records.size());
        DecisionRecord record = harness.diagnostics.records.get(0);
        assertEquals(WakeDecision.Action.BLOCK_WAKE, record.decision.effectiveAction());
        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED,
                record.outcome);
    }

    @Test
    public void throttleConsumesAndReportsOncePerFinalDelivery() throws Throwable {
        AtomicLong now = new AtomicLong(10_000L);
        AtomicInteger clockReads = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness(
                (target, source) -> {
                    decisions.incrementAndGet();
                    return decision(target, alarmPolicy(WakeMode.THROTTLE, 5_000L));
                },
                () -> {
                    clockReads.incrementAndGet();
                    return now.get();
                },
                4,
                true);
        Alarm firstAlarm = alarm(OWNER_UID, OWNER_PACKAGE);
        Alarm secondAlarm = alarm(OWNER_UID, OWNER_PACKAGE);

        assertEquals(Boolean.FALSE, stage(harness, firstAlarm).checkValue);
        assertEquals(0, clockReads.get());
        DeliveryCall first = deliver(harness, firstAlarm, Boolean.FALSE);
        assertEquals(Boolean.FALSE, first.value);
        assertEquals(1, clockReads.get());

        now.set(10_001L);
        assertEquals(Boolean.FALSE, stage(harness, secondAlarm).checkValue);
        assertEquals(1, clockReads.get());
        DeliveryCall second = deliver(harness, secondAlarm, Boolean.FALSE);

        assertEquals(Boolean.TRUE, second.value);
        assertEquals(2, clockReads.get());
        assertEquals(4, decisions.get());
        assertEquals(1, harness.completion.replayCount.get());
        assertEquals(1, harness.completion.finishCount.get());
        assertEquals(2, harness.diagnostics.records.size());
        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_ALLOWED,
                harness.diagnostics.records.get(0).outcome);
        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_BLOCKED,
                harness.diagnostics.records.get(1).outcome);
        assertTrue(harness.diagnostics.failOpenEvents.isEmpty());
        assertTrue(harness.diagnostics.runtimeFailOpenEvents.isEmpty());
    }

    @Test
    public void vendorTerminalBlockWinsAndConsumesDeferredRecord() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
        });
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
        assertEquals(Boolean.FALSE, stage(harness, alarm).checkValue);

        DeliveryCall vendorBlocked = deliver(harness, alarm, Boolean.TRUE);
        DeliveryCall repeated = deliver(harness, alarm, Boolean.FALSE);

        assertEquals(Boolean.TRUE, vendorBlocked.value);
        assertEquals(Boolean.FALSE, repeated.value);
        assertEquals(1, vendorBlocked.chain.proceedCalls());
        assertEquals(1, repeated.chain.proceedCalls());
        assertEquals(1, decisions.get());
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.records.isEmpty());
        assertTrue(harness.diagnostics.failOpenEvents.isEmpty());
        assertTrue(harness.diagnostics.runtimeFailOpenEvents.isEmpty());
    }

    @Test
    public void finalAllowReplaysAnEarlyBlockAndPassesDelivery() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> decisions.getAndIncrement() == 0
                ? decision(target, alarmPolicy(WakeMode.BLOCK, 0L))
                : decision(target, WakePolicy.allowAll()));
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);

        assertEquals(Boolean.FALSE, stage(harness, alarm).checkValue);
        DeliveryCall delivery = deliver(harness, alarm, Boolean.FALSE);

        assertEquals(Boolean.FALSE, delivery.value);
        assertEquals(2, decisions.get());
        assertEquals(1, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertEquals(1, harness.diagnostics.records.size());
        assertEquals(
                WakeDecision.Action.ALLOW_SYSTEM,
                harness.diagnostics.records.get(0).decision.effectiveAction());
    }

    @Test
    public void finalBlockCannotSuppressWhenEarlyCheckAlreadyRan() throws Throwable {
        AtomicReference<WakePolicy> policy = new AtomicReference<>(WakePolicy.allowAll());
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, policy.get());
        });
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);

        StagedCall staged = stage(harness, alarm);
        assertEquals(Boolean.TRUE, staged.checkValue);
        assertEquals(1, staged.check.proceedCalls());

        policy.set(alarmPolicy(WakeMode.BLOCK, 0L));
        DeliveryCall delivery = deliver(harness, alarm, Boolean.FALSE);

        assertEquals(Boolean.FALSE, delivery.value);
        assertEquals(1, decisions.get());
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertEquals(1, harness.diagnostics.records.size());
        assertEquals(
                WakeDecision.Action.ALLOW_SYSTEM,
                harness.diagnostics.records.get(0).decision.effectiveAction());
    }

    @Test
    public void fullRegistryRunsCurrentHansCheckAndLeavesNoPartialDelay() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness(
                (target, source) -> {
                    decisions.incrementAndGet();
                    return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
                },
                () -> 1_000L,
                1,
                true);
        Alarm retained = alarm(OWNER_UID, OWNER_PACKAGE);
        Alarm overflow = alarm(OWNER_UID, OWNER_PACKAGE);

        assertEquals(Boolean.FALSE, stage(harness, retained).checkValue);
        StagedCall overflowStage = stage(harness, overflow);

        assertEquals(Boolean.TRUE, overflowStage.checkValue);
        assertEquals(1, overflowStage.check.proceedCalls());
        assertNull(harness.registry.remove(overflow));
        assertEquals(1, harness.diagnostics.runtimeFailOpenEvents.size());
        assertEquals(2, decisions.get());

        assertEquals(Boolean.FALSE, deliver(harness, overflow, Boolean.FALSE).value);
        assertEquals(Boolean.TRUE, deliver(harness, retained, Boolean.TRUE).value);
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.records.isEmpty());
    }

    @Test
    public void finishFailureReplaysAndFailsOpen() throws Throwable {
        Harness harness = harness((target, source) ->
                decision(target, alarmPolicy(WakeMode.BLOCK, 0L)));
        harness.completion.finishFailure = new CompletionFailure();
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
        assertEquals(Boolean.FALSE, stage(harness, alarm).checkValue);

        DeliveryCall delivery = deliver(harness, alarm, Boolean.FALSE);

        assertEquals(Boolean.FALSE, delivery.value);
        assertEquals(1, harness.completion.finishCount.get());
        assertEquals(1, harness.completion.replayCount.get());
        assertTrue(harness.diagnostics.records.isEmpty());
        assertEquals(1, harness.diagnostics.runtimeFailOpenEvents.size());
    }

    @Test
    public void replayFailureKeepsVendorPassAndReportsRuntimeFailOpen() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> decisions.getAndIncrement() == 0
                ? decision(target, alarmPolicy(WakeMode.BLOCK, 0L))
                : decision(target, WakePolicy.allowAll()));
        harness.completion.replayFailure = new CompletionFailure();
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
        assertEquals(Boolean.FALSE, stage(harness, alarm).checkValue);

        DeliveryCall delivery = deliver(harness, alarm, Boolean.FALSE);

        assertEquals(Boolean.FALSE, delivery.value);
        assertEquals(1, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.records.isEmpty());
        assertEquals(1, harness.diagnostics.runtimeFailOpenEvents.size());
    }

    @Test
    public void filterResolutionFailureFailsOpenWithoutEnteringPolicy() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            throw new AssertionError("unresolved Alarm must not reach policy");
        });
        Object unpreparedAlarm = new Object();
        FakeChain filter = FakeChain.returning(
                EXTENSION, "vendor-filter", unpreparedAlarm);

        assertEquals("vendor-filter", harness.hook.interceptFilter(filter));
        assertEquals(1, filter.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(1, harness.diagnostics.failOpenEvents.size());
        assertEquals(
                ResidencyTarget.UNKNOWN_UID,
                harness.diagnostics.failOpenEvents.get(0).uid);
    }

    @Test
    public void checkMismatchFailsOpenAndCallsVendorExactlyOnce() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            throw new AssertionError("mismatched check must not reach policy");
        });
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);

        StagedCall staged = stage(
                harness,
                alarm,
                OWNER_UID + 1,
                OWNER_PACKAGE,
                STATS_TAG,
                Boolean.TRUE);

        assertEquals(Boolean.TRUE, staged.checkValue);
        assertEquals(1, staged.check.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(1, harness.diagnostics.failOpenEvents.size());
        assertEquals(Boolean.FALSE, deliver(harness, alarm, Boolean.FALSE).value);
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
    }

    @Test
    public void nullAndThrowingResolversBothFailOpenAtEarlyCheck() throws Throwable {
        assertEarlyResolverFailureFailsOpen((target, source) -> null);
        assertEarlyResolverFailureFailsOpen((target, source) -> {
            throw new IllegalStateException("resolver unavailable");
        });
    }

    @Test
    public void inactiveCallbacksArePurePassThrough() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness(
                (target, source) -> {
                    decisions.incrementAndGet();
                    throw new AssertionError("inactive hook must not decide");
                },
                () -> {
                    throw new AssertionError("inactive hook must not read the clock");
                },
                2,
                false);
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
        FakeChain filter = FakeChain.returning(EXTENSION, "filter", alarm);
        FakeChain check = FakeChain.returning(
                HANS_MANAGER, "check", OWNER_UID, OWNER_PACKAGE, STATS_TAG);
        FakeChain delivery = FakeChain.returning(EXTENSION, "delivery", alarm);

        assertEquals("filter", harness.hook.interceptFilter(filter));
        assertEquals("check", harness.hook.interceptHansCheck(check));
        assertEquals("delivery", harness.hook.interceptDelivery(delivery));

        assertEquals(1, filter.proceedCalls());
        assertEquals(1, check.proceedCalls());
        assertEquals(1, delivery.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.isEmpty());
    }

    @Test
    public void absentOrFailingNativeDriverProbeNeverCoordinatesAlarm()
            throws Throwable {
        List<AlarmWakeHook.AlarmDriverProbe> probes = Arrays.asList(
                extension -> false,
                extension -> {
                    throw new IllegalStateException("driver probe unavailable");
                });

        for (AlarmWakeHook.AlarmDriverProbe probe : probes) {
            AtomicInteger probeCalls = new AtomicInteger();
            AtomicInteger decisions = new AtomicInteger();
            AtomicInteger clockReads = new AtomicInteger();
            Harness harness = harness(
                    (target, source) -> {
                        decisions.incrementAndGet();
                        return decision(
                                target,
                                alarmPolicy(WakeMode.THROTTLE, 5_000L));
                    },
                    () -> {
                        clockReads.incrementAndGet();
                        return 1_000L;
                    },
                    2,
                    true,
                    extension -> {
                        probeCalls.incrementAndGet();
                        return probe.isNativeDriverPresent(extension);
                    });
            Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
            FakeChain check = checkChain(
                    OWNER_UID, OWNER_PACKAGE, STATS_TAG, Boolean.TRUE);
            AtomicReference<Object> checkValue = new AtomicReference<>();
            FakeChain filter = FakeChain.calling(EXTENSION, () -> {
                checkValue.set(harness.hook.interceptHansCheck(check));
                return Boolean.FALSE;
            }, alarm);

            assertEquals(Boolean.FALSE, harness.hook.interceptFilter(filter));
            assertEquals(Boolean.TRUE, checkValue.get());
            assertEquals(1, filter.proceedCalls());
            assertEquals(1, check.proceedCalls());
            assertEquals(1, probeCalls.get());
            assertEquals(0, decisions.get());
            assertEquals(0, clockReads.get());
            assertNull(harness.registry.remove(alarm));

            assertEquals(Boolean.FALSE, deliver(harness, alarm, Boolean.FALSE).value);
            assertEquals(0, harness.completion.replayCount.get());
            assertEquals(0, harness.completion.finishCount.get());
            assertTrue(harness.diagnostics.records.isEmpty());
            assertTrue(harness.diagnostics.failOpenEvents.isEmpty());
            assertEquals(1, harness.diagnostics.runtimeFailOpenEvents.size());
        }
    }

    @Test
    public void nestedFilterFramesRestoreOuterAlarmAndClearAfterReturn() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
        });
        Alarm outerAlarm = alarm(OWNER_UID, OWNER_PACKAGE);
        Alarm innerAlarm = alarm(OWNER_UID, OWNER_PACKAGE);
        FakeChain outerCheck = checkChain(
                OWNER_UID, OWNER_PACKAGE, STATS_TAG, Boolean.TRUE);
        FakeChain innerCheck = checkChain(
                OWNER_UID, OWNER_PACKAGE, STATS_TAG, Boolean.TRUE);
        AtomicReference<Object> innerCheckValue = new AtomicReference<>();
        FakeChain innerFilter = FakeChain.calling(EXTENSION, () -> {
            innerCheckValue.set(harness.hook.interceptHansCheck(innerCheck));
            return Boolean.FALSE;
        }, innerAlarm);
        AtomicReference<Object> outerCheckValue = new AtomicReference<>();
        FakeChain outerFilter = FakeChain.calling(EXTENSION, () -> {
            assertEquals(Boolean.FALSE, harness.hook.interceptFilter(innerFilter));
            outerCheckValue.set(harness.hook.interceptHansCheck(outerCheck));
            return Boolean.FALSE;
        }, outerAlarm);

        assertEquals(Boolean.FALSE, harness.hook.interceptFilter(outerFilter));
        assertEquals(Boolean.FALSE, innerCheckValue.get());
        assertEquals(Boolean.FALSE, outerCheckValue.get());
        assertEquals(0, innerCheck.proceedCalls());
        assertEquals(0, outerCheck.proceedCalls());
        assertEquals(2, decisions.get());

        FakeChain outsideCheck = checkChain(
                OWNER_UID, OWNER_PACKAGE, STATS_TAG, Boolean.TRUE);
        assertEquals(Boolean.TRUE, harness.hook.interceptHansCheck(outsideCheck));
        assertEquals(1, outsideCheck.proceedCalls());

        assertEquals(Boolean.TRUE, deliver(harness, innerAlarm, Boolean.TRUE).value);
        assertEquals(Boolean.TRUE, deliver(harness, outerAlarm, Boolean.TRUE).value);
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.isEmpty());
    }

    @Test
    public void exceptionalFilterClearsThreadLocalAndPreservesOriginalFailure()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
        });
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
        OriginalFailure failure = new OriginalFailure();
        FakeChain filter = FakeChain.calling(EXTENSION, () -> {
            throw failure;
        }, alarm);

        assertSame(failure, assertThrows(
                OriginalFailure.class,
                () -> harness.hook.interceptFilter(filter)));

        FakeChain directCheck = checkChain(
                OWNER_UID, OWNER_PACKAGE, STATS_TAG, Boolean.TRUE);
        assertEquals(Boolean.TRUE, harness.hook.interceptHansCheck(directCheck));
        assertEquals(1, directCheck.proceedCalls());
        assertEquals(0, decisions.get());
        assertTrue(harness.diagnostics.isEmpty());
    }

    @Test
    public void dryRunMasterOffAndSharedUidConflictNeverDelayHans() throws Throwable {
        List<AlarmWakeHook.WakeDecisionResolver> resolvers = Arrays.asList(
                (target, source) -> decision(
                        target,
                        alarmPolicy(WakeMode.BLOCK, 0L),
                        true,
                        true,
                        true,
                        1L,
                        2L,
                        0L),
                (target, source) -> decision(
                        target,
                        alarmPolicy(WakeMode.BLOCK, 0L),
                        false,
                        true,
                        false,
                        1L,
                        2L,
                        0L),
                (target, source) -> sharedUidConflictDecision(target));
        List<DecisionDisposition> expected = Arrays.asList(
                DecisionDisposition.DRY_RUN,
                DecisionDisposition.FAIL_OPEN,
                DecisionDisposition.FAIL_OPEN);

        for (int index = 0; index < resolvers.size(); index++) {
            Harness harness = harness(resolvers.get(index));
            Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);

            StagedCall staged = stage(harness, alarm);

            assertEquals(Boolean.TRUE, staged.checkValue);
            assertEquals(1, staged.check.proceedCalls());
            assertNull(harness.registry.remove(alarm));
            assertEquals(Boolean.FALSE, deliver(harness, alarm, Boolean.FALSE).value);
            assertEquals(0, harness.completion.replayCount.get());
            assertEquals(0, harness.completion.finishCount.get());
            assertEquals(1, harness.diagnostics.records.size());
            assertEquals(
                    expected.get(index),
                    harness.diagnostics.records.get(0).decision.trace().disposition());
        }
    }

    @Test
    public void associationUsesObjectAndCreatorWhileFinishUsesCurrentSchedulerFields()
            throws Throwable {
        AtomicReference<ResidencyTarget> resolvedTarget = new AtomicReference<>();
        AtomicReference<WakeSource> resolvedSource = new AtomicReference<>();
        Harness harness = harness((target, source) -> {
            resolvedTarget.set(target);
            resolvedSource.set(source);
            return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
        });
        PendingIntent operation = allocate(PendingIntent.class);
        Alarm alarm = new Alarm(
                operation,
                null,
                null,
                STATS_TAG,
                null,
                SCHEDULER_UID,
                OWNER_UID,
                SCHEDULER_PACKAGE,
                OWNER_PACKAGE);

        assertEquals(Boolean.FALSE, stage(
                harness,
                alarm,
                OWNER_UID,
                OWNER_PACKAGE,
                "vendor-action-is-not-identity",
                Boolean.TRUE).checkValue);
        int currentSchedulerUid = SCHEDULER_UID + 7;
        String currentSchedulerPackage = "android.updated";
        String currentStatsTag = "*walarm*:updated";
        setInstanceField(alarm, "uid", currentSchedulerUid);
        setInstanceField(alarm, "packageName", currentSchedulerPackage);
        setInstanceField(alarm, "statsTag", currentStatsTag);
        assertEquals(Boolean.TRUE, deliver(harness, alarm, Boolean.FALSE).value);

        assertEquals(OWNER_UID, resolvedTarget.get().uid());
        assertEquals(OWNER_PACKAGE, resolvedTarget.get().packageName());
        assertEquals(WakeSource.ALARM, resolvedSource.get());
        assertEquals(1, harness.diagnostics.records.size());
        DecisionRecord record = harness.diagnostics.records.get(0);
        assertEquals(FeatureIds.HANS_WAKE, record.event.feature);
        assertEquals(WakeSource.ALARM.ordinal(), record.event.code);
        assertEquals(OWNER_UID, record.event.uid);
        assertEquals(OWNER_PACKAGE, record.event.packageName);
        assertEquals("alarm_delivery", record.event.scene);
        assertEquals(WakeDecision.Action.BLOCK_WAKE, record.decision.effectiveAction());
        assertEquals(
                ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED,
                record.outcome);

        assertEquals(1, harness.completion.finishedTargets.size());
        AlarmObjectAdapter.Target finished = harness.completion.finishedTargets.get(0);
        assertEquals(currentSchedulerUid, finished.schedulerUid());
        assertEquals(currentSchedulerPackage, finished.schedulerPackage());
        assertEquals(OWNER_UID, finished.ownerUid());
        assertEquals(OWNER_PACKAGE, finished.ownerPackage());
        assertSame(operation, finished.operation());
        assertEquals(currentStatsTag, finished.statsTag());
        assertSame(EXTENSION, harness.completion.finishedExtensions.get(0));
    }

    @Test
    public void installReportsUnsupportedWhenAlarmAbiIsMissing() {
        ClassLoader loader = new FilteringClassLoader(
                Alarm.class.getClassLoader(), "com.android.server.alarm.Alarm");

        String status = new AlarmWakeHook().install(loader);

        assertTrue(status, status.startsWith("UNSUPPORTED_"));
        assertTrue(status, status.contains("alarm_access=MISSING_CLASS"));
    }

    @Test
    public void vendorDeliveryFailurePropagatesWithoutModuleCompletion()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Harness harness = harness((target, source) -> {
            decisions.incrementAndGet();
            return decision(target, alarmPolicy(WakeMode.BLOCK, 0L));
        });
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);
        assertEquals(Boolean.FALSE, stage(harness, alarm).checkValue);
        OriginalFailure failure = new OriginalFailure();
        FakeChain delivery = FakeChain.calling(EXTENSION, () -> {
            throw failure;
        }, alarm);

        assertSame(failure, assertThrows(
                OriginalFailure.class,
                () -> harness.hook.interceptDelivery(delivery)));

        assertEquals(1, delivery.proceedCalls());
        assertEquals(1, decisions.get());
        assertEquals(1, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.isEmpty());

        assertEquals(Boolean.TRUE, deliver(harness, alarm, Boolean.TRUE).value);
        assertEquals(1, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
        assertTrue(harness.diagnostics.isEmpty());
    }

    private static void assertEarlyResolverFailureFailsOpen(
            AlarmWakeHook.WakeDecisionResolver resolver) throws Throwable {
        Harness harness = harness(resolver);
        Alarm alarm = alarm(OWNER_UID, OWNER_PACKAGE);

        StagedCall staged = stage(harness, alarm);

        assertEquals(Boolean.TRUE, staged.checkValue);
        assertEquals(1, staged.check.proceedCalls());
        assertEquals(1, harness.diagnostics.failOpenEvents.size());
        assertNull(harness.registry.remove(alarm));
        assertEquals(Boolean.FALSE, deliver(harness, alarm, Boolean.FALSE).value);
        assertEquals(0, harness.completion.replayCount.get());
        assertEquals(0, harness.completion.finishCount.get());
    }

    private static Harness harness(AlarmWakeHook.WakeDecisionResolver resolver) {
        return harness(resolver, () -> 1_000L, 8, true);
    }

    private static Harness harness(
            AlarmWakeHook.WakeDecisionResolver resolver,
            AlarmWakeHook.ElapsedRealtimeClock clock,
            int capacity,
            boolean active) {
        return harness(
                resolver,
                clock,
                capacity,
                active,
                extension -> true);
    }

    private static Harness harness(
            AlarmWakeHook.WakeDecisionResolver resolver,
            AlarmWakeHook.ElapsedRealtimeClock clock,
            int capacity,
            boolean active,
            AlarmWakeHook.AlarmDriverProbe driverProbe) {
        AlarmDeferredRegistry<AlarmWakeHook.DeferredAlarm> registry =
                new AlarmDeferredRegistry<>(capacity);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        CompletionRecorder completion = new CompletionRecorder();
        AlarmWakeHook hook = new AlarmWakeHook(
                resolver,
                new WakeThrottleState(),
                clock,
                registry,
                diagnostics,
                completion,
                completion,
                driverProbe,
                active);
        return new Harness(hook, registry, diagnostics, completion);
    }

    private static StagedCall stage(Harness harness, Alarm alarm) throws Throwable {
        return stage(
                harness,
                alarm,
                OWNER_UID,
                OWNER_PACKAGE,
                STATS_TAG,
                Boolean.TRUE);
    }

    private static StagedCall stage(
            Harness harness,
            Alarm alarm,
            int checkUid,
            String checkPackage,
            String checkAction,
            Object vendorCheckValue) throws Throwable {
        FakeChain check = checkChain(
                checkUid, checkPackage, checkAction, vendorCheckValue);
        AtomicReference<Object> checkValue = new AtomicReference<>();
        FakeChain filter = FakeChain.calling(EXTENSION, () -> {
            checkValue.set(harness.hook.interceptHansCheck(check));
            return Boolean.FALSE;
        }, alarm);
        Object filterValue = harness.hook.interceptFilter(filter);
        assertEquals(Boolean.FALSE, filterValue);
        return new StagedCall(filter, check, checkValue.get());
    }

    private static FakeChain checkChain(
            int uid, String packageName, String action, Object vendorValue) {
        return FakeChain.returning(
                HANS_MANAGER, vendorValue, uid, packageName, action);
    }

    private static DeliveryCall deliver(
            Harness harness, Alarm alarm, Object vendorValue) throws Throwable {
        FakeChain chain = FakeChain.returning(EXTENSION, vendorValue, alarm);
        return new DeliveryCall(chain, harness.hook.interceptDelivery(chain));
    }

    private static Alarm alarm(int schedulerUid, String schedulerPackage)
            throws Exception {
        return new Alarm(
                allocate(PendingIntent.class),
                null,
                null,
                STATS_TAG,
                null,
                schedulerUid,
                OWNER_UID,
                schedulerPackage,
                OWNER_PACKAGE);
    }

    private static WakePolicy alarmPolicy(WakeMode mode, long cooldownMs) {
        return new WakePolicy(
                WakeChannelPolicy.allow(),
                new WakeChannelPolicy(mode, cooldownMs),
                java.util.EnumSet.noneOf(WakeSource.class));
    }

    private static WakeDecision decision(
            ResidencyTarget target, WakePolicy wakePolicy) {
        return decision(target, wakePolicy, true, true, false, 1L, 2L, 0L);
    }

    private static WakeDecision decision(
            ResidencyTarget target,
            WakePolicy wakePolicy,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            long configRevision,
            long policyRevision,
            long identityGeneration) {
        AppResidencyPolicy policy = policy(OWNER_PACKAGE, wakePolicy);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                configRevision,
                masterEnabled,
                residencyEnabled,
                dryRun,
                policyRevision,
                Map.of(OWNER_PACKAGE, policy));
        ResidencyIdentityIndex identities = ResidencyIdentityIndex.build(
                snapshot, Map.of(OWNER_PACKAGE, OWNER_UID));
        return new ResidencyDecisionEngine(
                snapshot, identities, identityGeneration).decideWake(new WakeContext(
                target,
                WakeSource.ALARM,
                FeatureIds.HANS_WAKE,
                HookTargetIds.ALARM_WAKE_DELIVERY));
    }

    private static WakeDecision sharedUidConflictDecision(ResidencyTarget target) {
        AppResidencyPolicy blocked = policy(
                OWNER_PACKAGE, alarmPolicy(WakeMode.BLOCK, 0L));
        AppResidencyPolicy allowed = policy(SHARED_PACKAGE, WakePolicy.allowAll());
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                9L,
                true,
                true,
                false,
                10L,
                Map.of(OWNER_PACKAGE, blocked, SHARED_PACKAGE, allowed));
        ResidencyIdentityIndex identities = ResidencyIdentityIndex.build(
                snapshot,
                Map.of(OWNER_PACKAGE, OWNER_UID, SHARED_PACKAGE, OWNER_UID));
        return new ResidencyDecisionEngine(snapshot, identities, 4L).decideWake(
                new WakeContext(
                        target,
                        WakeSource.ALARM,
                        FeatureIds.HANS_WAKE,
                        HookTargetIds.ALARM_WAKE_DELIVERY));
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

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
        return type.cast(allocateInstance.invoke(unsafe, type));
    }

    private static void setInstanceField(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Harness {
        private final AlarmWakeHook hook;
        private final AlarmDeferredRegistry<AlarmWakeHook.DeferredAlarm> registry;
        private final RecordingDiagnostics diagnostics;
        private final CompletionRecorder completion;

        private Harness(
                AlarmWakeHook hook,
                AlarmDeferredRegistry<AlarmWakeHook.DeferredAlarm> registry,
                RecordingDiagnostics diagnostics,
                CompletionRecorder completion) {
            this.hook = hook;
            this.registry = registry;
            this.diagnostics = diagnostics;
            this.completion = completion;
        }
    }

    private static final class StagedCall {
        private final FakeChain filter;
        private final FakeChain check;
        private final Object checkValue;

        private StagedCall(FakeChain filter, FakeChain check, Object checkValue) {
            this.filter = filter;
            this.check = check;
            this.checkValue = checkValue;
        }
    }

    private static final class DeliveryCall {
        private final FakeChain chain;
        private final Object value;

        private DeliveryCall(FakeChain chain, Object value) {
            this.chain = chain;
            this.value = value;
        }
    }

    private static final class CompletionRecorder
            implements AlarmWakeHook.HansReplay, AlarmWakeHook.AlarmFinisher {
        private final AtomicInteger replayCount = new AtomicInteger();
        private final AtomicInteger finishCount = new AtomicInteger();
        private final List<AlarmWakeHook.DeferredAlarm> replayed = new ArrayList<>();
        private final List<Object> finishedExtensions = new ArrayList<>();
        private final List<AlarmObjectAdapter.Target> finishedTargets = new ArrayList<>();
        private Throwable replayFailure;
        private Throwable finishFailure;

        @Override
        public void replay(AlarmWakeHook.DeferredAlarm record) throws Throwable {
            replayCount.incrementAndGet();
            replayed.add(record);
            if (replayFailure != null) throw replayFailure;
        }

        @Override
        public void finish(
                Object extension, AlarmObjectAdapter.Target target) throws Throwable {
            finishCount.incrementAndGet();
            finishedExtensions.add(extension);
            finishedTargets.add(target);
            if (finishFailure != null) throw finishFailure;
        }
    }

    private static final class RecordingDiagnostics
            implements AlarmWakeHook.WakeDiagnosticsRecorder {
        private final List<DecisionRecord> records = new ArrayList<>();
        private final List<HookEvent> failOpenEvents = new ArrayList<>();
        private final List<HookEvent> runtimeFailOpenEvents = new ArrayList<>();

        @Override
        public void recordDecision(
                HookEvent event,
                WakeDecision decision,
                ResidencyDecisionReporter.WakeRuntimeOutcome outcome) {
            records.add(new DecisionRecord(event, decision, outcome));
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            failOpenEvents.add(event);
        }

        @Override
        public void recordRuntimeFailOpen(HookEvent event) {
            runtimeFailOpenEvents.add(event);
        }

        private boolean isEmpty() {
            return records.isEmpty()
                    && failOpenEvents.isEmpty()
                    && runtimeFailOpenEvents.isEmpty();
        }
    }

    private static final class DecisionRecord {
        private final HookEvent event;
        private final WakeDecision decision;
        private final ResidencyDecisionReporter.WakeRuntimeOutcome outcome;

        private DecisionRecord(
                HookEvent event,
                WakeDecision decision,
                ResidencyDecisionReporter.WakeRuntimeOutcome outcome) {
            this.event = event;
            this.decision = decision;
            this.outcome = outcome;
        }
    }

    private interface OriginalCall {
        Object proceed() throws Throwable;
    }

    private static final class OriginalFailure extends Exception {}

    private static final class CompletionFailure extends Exception {}

    private static final class FakeChain implements XposedInterface.Chain {
        private final Object thisObject;
        private final List<Object> args;
        private final OriginalCall originalCall;
        private final AtomicInteger proceedCalls = new AtomicInteger();

        private FakeChain(
                Object thisObject, OriginalCall originalCall, Object... args) {
            this.thisObject = thisObject;
            this.originalCall = originalCall;
            this.args = Arrays.asList(args);
        }

        private static FakeChain returning(
                Object thisObject, Object result, Object... args) {
            return new FakeChain(thisObject, () -> result, args);
        }

        private static FakeChain calling(
                Object thisObject, OriginalCall originalCall, Object... args) {
            return new FakeChain(thisObject, originalCall, args);
        }

        private int proceedCalls() {
            return proceedCalls.get();
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return thisObject; }
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
        @Override public Object proceedWith(Object receiver) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
        @Override public Object proceedWith(
                Object receiver, Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            return originalCall.proceed();
        }
    }

    private static final class FilteringClassLoader extends ClassLoader {
        private final String hiddenClass;

        private FilteringClassLoader(ClassLoader parent, String hiddenClass) {
            super(parent);
            this.hiddenClass = hiddenClass;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (hiddenClass.equals(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }
}
