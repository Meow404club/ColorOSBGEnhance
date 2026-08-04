package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBootId;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWriteBatch;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = 34,
        manifest = Config.NONE,
        shadows = HookDiagnosticsTest.ShadowDiagnosticsBootId.class)
@SuppressWarnings("deprecation")
public final class HookDiagnosticsTest {
    private static final long EPOCH = 7L;

    private RecordingProvider provider;

    @Before
    public void setUp() throws Exception {
        resetHookDiagnostics();
        setStaticField(
                HookConfig.class,
                "sResolver",
                RuntimeEnvironment.getApplication().getContentResolver());
        HookConfig.setProcess("android", "system_server");
        provider = new RecordingProvider();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
    }

    @After
    public void tearDown() throws Exception {
        resetHookDiagnostics();
        setStaticField(HookConfig.class, "sResolver", null);
        setStaticField(HookConfig.class, "sContext", null);
    }

    @Test
    public void configReportIdentityIncludesSourcePathAndError() {
        HookDiagnostics.ConfigReportState envelope = state(
                "runtime_envelope", "config", "");

        assertTrue(envelope.sameConfigurationAs(
                state("runtime_envelope", "config", "")));
        assertFalse(envelope.sameConfigurationAs(
                state("legacy_preferences", "config", "")));
        assertFalse(envelope.sameConfigurationAs(
                state("runtime_envelope", "other", "")));
        assertFalse(envelope.sameConfigurationAs(
                state("runtime_envelope", "config", "invalid_document")));
    }

    @Test
    public void configReportIdentityIncludesRevision() {
        HookDiagnostics.ConfigReportState first = state(
                7, "runtime_envelope", "config", 1L, "");
        HookDiagnostics.ConfigReportState second = state(
                8, "runtime_envelope", "config", 1L, "");

        assertFalse(first.sameConfigurationAs(second));
    }

    @Test
    public void latencyDoesNotTurnOneConfigStateIntoAFalseTransition() {
        HookDiagnostics.ConfigReportState first = state(
                7, "runtime_envelope", "config", 1L, "invalid");
        HookDiagnostics.ConfigReportState retry = state(
                7, "runtime_envelope", "config", 2_000L, "invalid");

        assertTrue(first.sameConfigurationAs(retry));
        assertNotEquals(first.reloadLatencyMs, retry.reloadLatencyMs);
    }

    @Test
    public void lockedPathEventUpdatesOutboxWithoutSchedulingExecutorWork() throws Exception {
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        assertFalse(flushScheduled().get());
        assertEquals(2, pendingItemCount());
        assertEquals(1, pendingRawHitCount());
        assertEquals(1, pendingNormalizedEventCount());
        assertEquals(2, outboxSize());
    }

    @Test
    public void allEventPathsOnlyUpdateMemoryOnTheHookThread() throws Exception {
        for (int index = 0; index < 32; index++) {
            HookDiagnostics.recordEvent(event(index), DiagnosticsResult.PASSED);
        }

        assertFalse(flushScheduled().get());
        assertNull(scheduledFlush());
        assertEquals(32, pendingRawHitCount());
        assertEquals(32, pendingNormalizedEventCount());
    }

    @Test
    public void moduleOwnedEventsCannotFeedTheDiagnosticsTransport() throws Exception {
        moduleUid().set(10_044);
        HookEvent moduleEvent = new HookEvent(
                FeatureIds.HANS_WAKE,
                1,
                10_044,
                BridgeContract.MODULE_PACKAGE,
                BridgeContract.MODULE_PACKAGE,
                "provider");

        HookDiagnostics.recordEvent(moduleEvent, DiagnosticsResult.PASSED);
        HookDiagnostics.recordEventFromLockedPath(moduleEvent, DiagnosticsResult.PASSED);
        HookDiagnostics.recordDecision(moduleEvent, DiagnosticsResult.PASSED, null);
        HookDiagnostics.recordDecisionFromLockedPath(
                moduleEvent, DiagnosticsResult.PASSED, null);
        HookDiagnostics.recordEventFromLockedPath(new HookEvent(
                FeatureIds.HANS_RESOURCE,
                1,
                10_044,
                "",
                "",
                "resource"), DiagnosticsResult.PASSED);

        assertEquals(0, pendingRawHitCount());
        assertEquals(0, pendingNormalizedEventCount());
        assertEquals(0, outboxSize());
        runScheduledFlush();
        assertTrue(provider.methods.isEmpty());

        HookDiagnostics.recordEventFromLockedPath(new HookEvent(
                FeatureIds.HANS_WAKE,
                1,
                10_045,
                BridgeContract.MODULE_PACKAGE + ".other",
                BridgeContract.MODULE_PACKAGE + ".other",
                "provider"), DiagnosticsResult.PASSED);
        assertEquals(1, pendingRawHitCount());
        assertEquals(1, pendingNormalizedEventCount());
    }

    @Test
    public void identityAwareRawHitsExcludeOnlyTheModuleTarget() throws Exception {
        moduleUid().set(10_044);

        HookDiagnostics.recordRawHookHit(
                HookTargetIds.HANS_R_TO_M_TIMING,
                10_044,
                "",
                true,
                true,
                "");
        HookDiagnostics.recordRawHookHit(
                HookTargetIds.HANS_LCD_ON_EXEMPTION,
                10_045,
                BridgeContract.MODULE_PACKAGE,
                true,
                true,
                "");
        HookDiagnostics.recordRawHookHit(
                HookTargetIds.HANS_R_TO_M_TIMING,
                10_045,
                "example.app",
                true,
                true,
                "");

        assertEquals(1, pendingRawHitCount());
        assertEquals(0, pendingNormalizedEventCount());
    }

    @Test
    public void providerWakeDuringAckCannotScheduleAnotherDiagnosticsBatch()
            throws Exception {
        setEpoch(EPOCH);
        moduleUid().set(10_044);
        provider.enqueue(request -> {
            HookDiagnostics.recordEventFromLockedPath(new HookEvent(
                    FeatureIds.HANS_WAKE,
                    1,
                    10_044,
                    BridgeContract.MODULE_PACKAGE,
                    BridgeContract.MODULE_PACKAGE,
                    "provider"), DiagnosticsResult.PASSED);
            return acceptedAck(request, EPOCH);
        });
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        flushSnapshot();
        runScheduledFlush();

        assertEquals(1, provider.requests.size());
        assertEquals(0, pendingItemCount());
        assertEquals(0, outboxSize());
    }

    @Test
    public void rawAndNormalizedQueuesRemainIndependentlyBounded() throws Exception {
        for (int index = 0; index < 2_048; index++) {
            HookDiagnostics.recordRawHookHit(
                    HookTargetIds.HANS_R_TO_M_TIMING, true, true, "");
        }
        HookDiagnostics.recordEventFromLockedPath(event(3_000), DiagnosticsResult.PASSED);

        assertEquals(2_048, pendingRawHitCount());
        assertEquals(1, pendingNormalizedEventCount());

        for (int index = 3_001; index < 5_500; index++) {
            HookDiagnostics.recordEventFromLockedPath(event(index), DiagnosticsResult.PASSED);
        }

        assertEquals(2_048, pendingRawHitCount());
        assertEquals(2_048, pendingNormalizedEventCount());
        assertFalse(flushScheduled().get());
    }

    @Test
    public void scheduledDrainCommitsSeveralWireBatchesInOneWakeWindow()
            throws Exception {
        setEpoch(EPOCH);
        for (int index = 0; index < 300; index++) {
            HookDiagnostics.recordEventFromLockedPath(event(index), DiagnosticsResult.PASSED);
        }
        for (int index = 0; index < 3; index++) {
            provider.enqueue(request -> acceptedAck(request, EPOCH));
        }

        runScheduledFlush();

        assertEquals(3, provider.requests.size());
        assertEquals(0, pendingItemCount());
        assertEquals(3L, nextBatchSequence());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(5L));
    }

    @Test
    public void scheduledDrainStopsOnTransportFailureAndStartsBoundedBackoff()
            throws Exception {
        setEpoch(EPOCH);
        markEventBatchDue();
        provider.enqueue(request -> null);
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        runScheduledFlush();

        assertEquals(1, provider.requests.size());
        assertEquals(2, pendingItemCount());
        assertNotNull(staticValue(HookDiagnostics.class, "sInFlight"));
        assertScheduledNear(TimeUnit.SECONDS.toMillis(15L));
    }

    @Test
    public void repeatedTransportFailuresBackOffAndSuccessResetsTheDelay()
            throws Exception {
        setEpoch(EPOCH);
        markEventBatchDue();
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);
        long[] expectedDelays = {
                TimeUnit.SECONDS.toMillis(15L),
                TimeUnit.SECONDS.toMillis(30L),
                TimeUnit.MINUTES.toMillis(1L),
                TimeUnit.MINUTES.toMillis(2L),
                TimeUnit.MINUTES.toMillis(4L),
                TimeUnit.MINUTES.toMillis(5L),
                TimeUnit.MINUTES.toMillis(5L)
        };
        for (long expectedDelay : expectedDelays) {
            provider.enqueue(request -> null);
            runScheduledFlush();
            assertScheduledNear(expectedDelay);
            allowTransportRetryNow();
        }

        provider.enqueue(request -> acceptedAck(request, EPOCH));
        runScheduledFlush();
        assertEquals(0, pendingItemCount());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(5L));

        HookDiagnostics.recordEventFromLockedPath(event(2), DiagnosticsResult.PASSED);
        markEventBatchDue();
        provider.enqueue(request -> null);
        runScheduledFlush();
        assertScheduledNear(TimeUnit.SECONDS.toMillis(15L));
    }

    @Test
    public void lowVolumeWaitsForBatchWindow()
            throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        runScheduledFlush();

        assertTrue(provider.requests.isEmpty());
        assertEquals(2, pendingItemCount());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(5L));

        markEventBatchDue();
        runScheduledFlush();

        assertEquals(1, provider.requests.size());
        assertEquals(0, pendingItemCount());
    }

    @Test
    public void queueHighWaterFlushesBeforeBatchWindow() throws Exception {
        setEpoch(EPOCH);
        for (int index = 0; index < 256; index++) {
            HookDiagnostics.recordRawHookHit(
                    HookTargetIds.HANS_R_TO_M_TIMING, true, true, "");
        }
        provider.enqueue(request -> acceptedAck(request, EPOCH));

        runScheduledFlush();

        assertEquals(1, provider.requests.size());
        assertEquals(0, pendingItemCount());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(5L));
    }

    @Test
    public void laterBatchFailureBacksOffAndStickyCannotBypassIt()
            throws Exception {
        setEpoch(EPOCH);
        for (int index = 0; index < 300; index++) {
            HookDiagnostics.recordEventFromLockedPath(event(index), DiagnosticsResult.PASSED);
        }
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        provider.enqueue(request -> null);

        runScheduledFlush();

        assertEquals(2, provider.requests.size());
        assertEquals(344, pendingItemCount());
        assertEquals(1L, nextBatchSequence());
        assertNotNull(staticValue(HookDiagnostics.class, "sInFlight"));
        assertScheduledNear(TimeUnit.SECONDS.toMillis(15L));

        HookDiagnostics.reportFeature(HookTargetIds.OGUARD_KILL, "INSTALLED:1");

        assertEquals(2, provider.requests.size());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(15L));
    }

    @Test
    public void stickyReportedInsideFailedProviderCallIsMovedBehindBackoff()
            throws Exception {
        setEpoch(EPOCH);
        markEventBatchDue();
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);
        provider.enqueue(request -> {
            HookDiagnostics.reportFeature(HookTargetIds.OGUARD_KILL, "INSTALLED:1");
            return null;
        });

        runScheduledFlush();

        assertEquals(1, provider.requests.size());
        assertEquals(2, pendingItemCount());
        assertNotNull(staticValue(HookDiagnostics.class, "sInFlight"));
        assertScheduledNear(TimeUnit.SECONDS.toMillis(15L));
    }

    @Test
    public void largeBacklogContinuesSoonWithoutUnboundedProviderBurst()
            throws Exception {
        setEpoch(EPOCH);
        for (int index = 0; index < 1_100; index++) {
            HookDiagnostics.recordEventFromLockedPath(event(index), DiagnosticsResult.PASSED);
        }
        for (int index = 0; index < 9; index++) {
            provider.enqueue(request -> acceptedAck(request, EPOCH));
        }

        runScheduledFlush();

        assertEquals(8, provider.requests.size());
        assertEquals(152, pendingItemCount());
        assertEquals(8L, nextBatchSequence());
        assertTrue(scheduledDelayMillis() > 0L);
        assertTrue(scheduledDelayMillis() <= TimeUnit.SECONDS.toMillis(2L));

        runScheduledFlush();

        assertEquals(9, provider.requests.size());
        assertEquals(0, pendingItemCount());
        assertEquals(9L, nextBatchSequence());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(5L));
    }

    @Test
    public void lostAckRetransmitsSamePayloadAndSequence() throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> null);
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        flushSnapshot();

        Object inFlight = staticValue(HookDiagnostics.class, "sInFlight");
        assertNotNull(inFlight);
        Bundle stablePayload = (Bundle) instanceValue(inFlight, "payload");
        assertEquals(2, pendingItemCount());
        assertEquals(0L, nextBatchSequence());

        flushSnapshot();

        assertEquals(2, provider.requests.size());
        assertSame(stablePayload, provider.requests.get(0));
        assertSame(provider.requests.get(0), provider.requests.get(1));
        DiagnosticsWriteBatch first = parse(provider.requests.get(0));
        DiagnosticsWriteBatch retry = parse(provider.requests.get(1));
        assertEquals(first.batchIdentity, retry.batchIdentity);
        assertEquals(0L, retry.batchIdentity.batchSequence);
        assertEquals(first.events.get(0).eventToken, retry.events.get(0).eventToken);
        assertEquals(0, pendingItemCount());
        assertEquals(0, outboxSize());
        assertEquals(1L, nextBatchSequence());
        assertNull(staticValue(HookDiagnostics.class, "sInFlight"));
    }

    @Test
    public void lockedUserDefersProviderResolutionWithoutLosingPendingState()
            throws Exception {
        setUserUnlocked(false);
        markEventBatchDue();
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        runScheduledFlush();

        assertTrue(provider.methods.isEmpty());
        assertEquals("user_locked", transportError());
        assertEquals(2, pendingItemCount());
        assertEquals(-1L, epoch());
        assertScheduledNear(TimeUnit.SECONDS.toMillis(5L));

        setUserUnlocked(true);
        runScheduledFlush();

        assertEquals(List.of(BridgeContract.METHOD_PING), provider.methods);
        assertEquals(2, pendingItemCount());
    }

    @Test
    public void unlockProbeFailureAlsoAvoidsProviderResolution() throws Exception {
        setStaticField(
                HookDiagnostics.class,
                "sUserUnlockProbe",
                (HookDiagnostics.UserUnlockProbe) () -> {
                    throw new IllegalStateException("user service unavailable");
                });
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        flushSnapshot();

        assertTrue(provider.methods.isEmpty());
        assertEquals("user_unlock_state_unavailable", transportError());
        assertEquals(2, pendingItemCount());
    }

    @Test
    public void epochChangeDropsOldStateAndStartsFreshDeduplicationScope() throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> epochMismatch(EPOCH + 1L, request));
        provider.enqueue(request -> acceptedAck(request, EPOCH + 1L));
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        flushSnapshot();

        assertEquals(EPOCH + 1L, epoch());
        assertEquals(0L, nextBatchSequence());
        assertEquals(0, pendingItemCount());
        assertEquals(0, outboxSize());
        assertNull(staticValue(HookDiagnostics.class, "sInFlight"));
        assertEquals(-1L, reportedStickyGeneration().get());

        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);
        flushSnapshot();

        DiagnosticsWriteBatch oldBatch = parse(provider.requests.get(0));
        DiagnosticsWriteBatch newBatch = parse(provider.requests.get(1));
        assertEquals(EPOCH, oldBatch.batchIdentity.epoch);
        assertEquals(0L, oldBatch.batchIdentity.batchSequence);
        assertEquals(EPOCH + 1L, newBatch.batchIdentity.epoch);
        assertEquals(0L, newBatch.batchIdentity.batchSequence);
        assertEquals("example.app1", newBatch.events.get(0).packageName);
        assertEquals(1L, nextBatchSequence());
        assertEquals(0, pendingItemCount());
    }

    @Test
    public void malformedOrPartialAcksCannotCommitOrAdvanceSequence() throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> {
            Bundle response = acceptedAck(request, EPOCH);
            response.putInt(
                    BridgeContract.EXTRA_BRIDGE_VERSION,
                    BridgeContract.BRIDGE_VERSION + 1);
            return response;
        });
        provider.enqueue(request -> {
            Bundle response = acceptedAck(request, EPOCH);
            response.remove(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH);
            return response;
        });
        provider.enqueue(request -> {
            Bundle response = acceptedAck(request, EPOCH);
            response.putBoolean(BridgeContract.EXTRA_OK, false);
            response.putInt(
                    BridgeContract.EXTRA_ACCEPTED_ITEMS,
                    parse(request).size() - 1);
            response.putString(BridgeContract.EXTRA_ERROR, "partial_ack");
            return response;
        });
        provider.enqueue(request -> {
            Bundle response = acceptedAck(request, EPOCH);
            response.putBoolean(BridgeContract.EXTRA_OK, false);
            response.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, 1);
            response.putString(BridgeContract.EXTRA_ERROR, "dropped_ack");
            return response;
        });
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        flushSnapshot();
        assertUncommitted("bridge_version_mismatch");
        flushSnapshot();
        assertUncommitted("bridge_epoch_missing");
        flushSnapshot();
        assertUncommitted("partial_ack");
        flushSnapshot();
        assertUncommitted("dropped_ack");

        assertEquals(4, provider.requests.size());
        for (Bundle request : provider.requests) {
            assertSame(provider.requests.get(0), request);
        }
    }

    @Test
    public void independentStreamsFragmentAndCommitOnlyAcknowledgedPrefixes()
            throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        for (int index = 0; index < 129; index++) {
            HookDiagnostics.recordEventFromLockedPath(event(index), DiagnosticsResult.PASSED);
        }

        flushSnapshot();

        DiagnosticsWriteBatch first = parse(provider.requests.get(0));
        assertEquals(DiagnosticsWireParser.MAX_WIRE_ITEMS, first.size());
        assertEquals(128, first.rawHits.size());
        assertEquals(128, first.events.size());
        assertEquals(0L, first.batchIdentity.batchSequence);
        assertEquals(2, pendingItemCount());
        assertEquals(1, pendingRawHitCount());
        assertEquals(1, pendingNormalizedEventCount());
        assertEquals(2, outboxSize());
        assertEquals(1L, nextBatchSequence());

        flushSnapshot();

        DiagnosticsWriteBatch second = parse(provider.requests.get(1));
        assertEquals(2, second.size());
        assertEquals(1, second.rawHits.size());
        assertEquals(1, second.events.size());
        assertEquals(1L, second.batchIdentity.batchSequence);
        assertNotEquals(
                first.events.get(first.events.size() - 1).eventToken,
                second.events.get(0).eventToken);
        assertEquals(0, pendingItemCount());
        assertEquals(2L, nextBatchSequence());
    }

    @Test
    public void stickyChangeDuringEventRetryIsSentInFollowingSequence() throws Exception {
        setEpoch(EPOCH);
        setFeatureStatus(HookTargetIds.OGUARD_KILL, "INSTALLED:1", 1L);
        provider.enqueue(request -> null);
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        HookDiagnostics.recordEventFromLockedPath(event(1), DiagnosticsResult.PASSED);

        flushSnapshot();
        setFeatureStatus(HookTargetIds.OGUARD_KILL, "FAILED_FAIL_OPEN", 2L);
        flushSnapshot();

        assertEquals(1L, reportedStickyGeneration().get());
        assertEquals(0, pendingItemCount());
        assertEquals(1L, nextBatchSequence());

        flushSnapshot();

        assertEquals(3, provider.requests.size());
        assertSame(provider.requests.get(0), provider.requests.get(1));
        DiagnosticsWriteBatch first = parse(provider.requests.get(0));
        DiagnosticsWriteBatch retry = parse(provider.requests.get(1));
        DiagnosticsWriteBatch sticky = parse(provider.requests.get(2));
        assertEquals(first.batchIdentity, retry.batchIdentity);
        assertEquals(1, first.rawHits.size());
        assertEquals(1, first.events.size());
        assertEquals(1, first.hookStates.size());
        assertEquals("INSTALLED:1", first.hookStates.get(0).installStatus);
        assertTrue(sticky.rawHits.isEmpty());
        assertTrue(sticky.events.isEmpty());
        assertEquals(1, sticky.hookStates.size());
        assertEquals("FAILED_FAIL_OPEN", sticky.hookStates.get(0).installStatus);
        assertEquals(1L, sticky.batchIdentity.batchSequence);
        assertEquals(2L, reportedStickyGeneration().get());
        assertEquals(2L, nextBatchSequence());
    }

    @Test
    public void concurrentDecisionsKeepEveryNormalizedEventAndRawHit()
            throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                final int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    String feature = (worker & 1) == 0
                            ? FeatureIds.OGUARD_KILL : FeatureIds.OSENSE_PER_PROCESS;
                    HookDiagnostics.recordEventFromLockedPath(
                            new HookEvent(
                                    feature,
                                    500 + worker,
                                    10_123,
                                    "com.example.same",
                                    "com.example.same:worker",
                                    "hook_path_" + worker),
                            DiagnosticsResult.BLOCKED);
                    return null;
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(5L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        flushSnapshot();

        DiagnosticsWriteBatch batch = parse(provider.requests.get(0));
        assertEquals(workers, batch.rawHits.size());
        assertEquals(workers, batch.events.size());
        assertEquals(0, pendingItemCount());
    }

    @Test
    public void differentSemanticOutcomesAreNotDeduplicated() throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> acceptedAck(request, EPOCH));
        HookEvent sameBehavior = new HookEvent(
                FeatureIds.OGUARD_KILL,
                502,
                10_123,
                "com.example.same",
                "com.example.same",
                "same_path");

        HookDiagnostics.recordEventFromLockedPath(sameBehavior, DiagnosticsResult.PASSED);
        HookDiagnostics.recordEventFromLockedPath(sameBehavior, DiagnosticsResult.BLOCKED);
        flushSnapshot();

        DiagnosticsWriteBatch batch = parse(provider.requests.get(0));
        assertEquals(2, batch.rawHits.size());
        assertEquals(2, batch.events.size());
    }

    @Test
    public void diagnosticOnlyHookHitNeverCreatesANormalizedEvent() throws Exception {
        setEpoch(EPOCH);
        provider.enqueue(request -> acceptedAck(request, EPOCH));

        HookDiagnostics.recordRawHookHit(
                HookTargetIds.HANS_R_TO_M_TIMING, true, true, "");
        flushSnapshot();

        DiagnosticsWriteBatch batch = parse(provider.requests.get(0));
        assertEquals(1, batch.rawHits.size());
        assertEquals(HookTargetIds.HANS_R_TO_M_TIMING,
                batch.rawHits.get(0).hookTargetId);
        assertTrue(batch.events.isEmpty());
        assertEquals(0, pendingItemCount());
    }

    @Test
    public void fallbackMappingsKeepFreezeAndUnknownAthenaEventsOnVerifiedTargets() {
        HookEvent superFreeze = new HookEvent(
                FeatureIds.SUPER_FREEZE, -1, 10_001,
                "example.freeze", "", "manager_fallback");
        HookEvent athenaOther = new HookEvent(
                FeatureIds.ATHENA_OTHER, 9_999, 10_002,
                "example.athena", "", "memory_guard_unknown");
        HookEvent nirvana = new HookEvent(
                FeatureIds.NIRVANA, 1, 10_003,
                "example.nirvana", "", "nirvana");

        assertEquals(HookTargetIds.HANS_FREEZE_DISPATCH,
                HookDiagnostics.hookTargetFor(superFreeze, null));
        assertEquals(HookTargetIds.MEMORY_GUARD_DISPATCH,
                HookDiagnostics.hookTargetFor(athenaOther, null));
        assertEquals(HookTargetIds.OSENSE_PROCESS_KILL,
                HookDiagnostics.hookTargetFor(nirvana, null));
        assertEquals("SUPER", HookDiagnostics.sourceFor(superFreeze));
        assertEquals("OTHER", HookDiagnostics.sourceFor(athenaOther));
    }

    @Test
    public void resourceAndRefreezeSourcesUseStableCodesInsteadOfFreeText() {
        assertEquals("R_TO_M", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_FREEZE_TIMING, 0, 10001, "pkg", "", "r_to_m_timing")));
        assertEquals("M_TO_F", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_FREEZE_TIMING, 0, 10001, "pkg", "", "m_to_f_timing")));
        assertEquals("SERVICE", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_RESOURCE, 1, 10001, "pkg", "", "private:text")));
        assertEquals("BLUETOOTH_SCAN", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_RESOURCE, 10, 10001, "pkg", "", "private:text")));
        assertEquals("NETWORK", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_RESOURCE, 100, 10001, "pkg", "", "private:text")));
        assertEquals("PACKET", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_REFREEZE, 1, 10001, "pkg", "", "private:text")));
        assertEquals("OTHER", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_REFREEZE, 17, 10001, "pkg", "", "private:text")));
        assertEquals("OTHER", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_REFREEZE, -1, 10001, "pkg", "", "private:text")));
        assertEquals("", HookDiagnostics.sourceFor(new HookEvent(
                FeatureIds.HANS_RESOURCE, -1, 10001, "pkg", "", "private:text")));
    }

    private static HookEvent event(int index) {
        return new HookEvent(
                FeatureIds.OGUARD_KILL,
                500 + index,
                10_001 + index,
                "example.app" + index,
                "example.app" + index + ":worker",
                "reason_" + index);
    }

    private static void flushSnapshot() throws Exception {
        Method method = HookDiagnostics.class.getDeclaredMethod("flushSnapshot");
        method.setAccessible(true);
        method.invoke(null);
    }

    private static void runScheduledFlush() throws Exception {
        ScheduledFuture<?> scheduled = scheduledFlush();
        if (scheduled != null) scheduled.cancel(false);
        Method method = HookDiagnostics.class.getDeclaredMethod("runScheduledFlush");
        method.setAccessible(true);
        executor().submit(() -> {
            try {
                method.invoke(null);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }).get(5L, TimeUnit.SECONDS);
    }

    private static DiagnosticsWriteBatch parse(Bundle request) {
        DiagnosticsWireParser.ParseResult parsed =
                new DiagnosticsWireParser().parseSnapshot(request);
        assertTrue(parsed.errorCode, parsed.accepted());
        return parsed.batch;
    }

    private static Bundle acceptedAck(Bundle request, long epoch) {
        DiagnosticsWriteBatch batch = parse(request);
        Bundle response = baseResponse(true, epoch);
        response.putInt(BridgeContract.EXTRA_ACCEPTED_ITEMS, batch.size());
        response.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, 0);
        return response;
    }

    private static Bundle epochMismatch(long epoch, Bundle request) {
        Bundle response = baseResponse(false, epoch);
        response.putString(BridgeContract.EXTRA_ERROR, "epoch_mismatch");
        response.putInt(BridgeContract.EXTRA_ACCEPTED_ITEMS, 0);
        response.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, parse(request).size());
        return response;
    }

    private static Bundle baseResponse(boolean ok, long epoch) {
        Bundle response = new Bundle();
        response.putBoolean(BridgeContract.EXTRA_OK, ok);
        response.putInt(BridgeContract.EXTRA_BRIDGE_VERSION, BridgeContract.BRIDGE_VERSION);
        response.putLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH, epoch);
        response.putString(BridgeContract.EXTRA_BOOT_ID, bootId());
        return response;
    }

    private static void setFeatureStatus(String target, String status, long generation)
            throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> statuses = (Map<String, String>) staticValue(
                HookDiagnostics.class, "FEATURE_STATUSES");
        @SuppressWarnings("unchecked")
        Map<String, Long> updated = (Map<String, Long>) staticValue(
                HookDiagnostics.class, "FEATURE_UPDATED_AT");
        statuses.put(target, status);
        updated.put(target, System.currentTimeMillis());
        stickyGeneration().set(generation);
    }

    private static void setEpoch(long value) throws Exception {
        setStaticField(HookDiagnostics.class, "sEpoch", value);
    }

    private static void markEventBatchDue() throws Exception {
        setStaticField(
                HookDiagnostics.class,
                "sLastEventBatchAt",
                android.os.SystemClock.elapsedRealtime() - TimeUnit.MINUTES.toMillis(6L));
    }

    private static void allowTransportRetryNow() throws Exception {
        setStaticField(HookDiagnostics.class, "sTransportRetryNotBeforeNanos", 0L);
    }

    private static void assertUncommitted(String error) throws Exception {
        assertEquals(2, pendingItemCount());
        assertEquals(2, outboxSize());
        assertEquals(0L, nextBatchSequence());
        assertNotNull(staticValue(HookDiagnostics.class, "sInFlight"));
        assertEquals(error, staticValue(HookDiagnostics.class, "sTransportError"));
    }

    private static long epoch() throws Exception {
        return (long) staticValue(HookDiagnostics.class, "sEpoch");
    }

    private static String transportError() throws Exception {
        return (String) staticValue(HookDiagnostics.class, "sTransportError");
    }

    private static void setUserUnlocked(boolean unlocked) throws Exception {
        setStaticField(
                HookDiagnostics.class,
                "sUserUnlockProbe",
                (HookDiagnostics.UserUnlockProbe) () -> unlocked);
    }

    private static long nextBatchSequence() throws Exception {
        return (long) staticValue(HookDiagnostics.class, "sNextBatchSequence");
    }

    private static String bootId() {
        try {
            return (String) staticValue(HookDiagnostics.class, "BOOT_ID");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static AtomicBoolean flushScheduled() throws Exception {
        return (AtomicBoolean) staticValue(HookDiagnostics.class, "FLUSH_SCHEDULED");
    }

    private static int pendingItemCount() throws Exception {
        return pendingRawHitCount() + pendingNormalizedEventCount();
    }

    private static int pendingRawHitCount() throws Exception {
        return ((AtomicInteger) instanceValue(outboxState(), "rawHitCount")).get();
    }

    private static int pendingNormalizedEventCount() throws Exception {
        return ((AtomicInteger) instanceValue(outboxState(), "eventCount")).get();
    }

    private static AtomicLong stickyGeneration() throws Exception {
        return (AtomicLong) staticValue(HookDiagnostics.class, "STICKY_GENERATION");
    }

    private static AtomicLong reportedStickyGeneration() throws Exception {
        return (AtomicLong) staticValue(
                HookDiagnostics.class, "REPORTED_STICKY_GENERATION");
    }

    private static ScheduledThreadPoolExecutor executor() throws Exception {
        return (ScheduledThreadPoolExecutor) staticValue(HookDiagnostics.class, "EXECUTOR");
    }

    private static ScheduledFuture<?> scheduledFlush() throws Exception {
        return (ScheduledFuture<?>) staticValue(HookDiagnostics.class, "sScheduledFlush");
    }

    private static long scheduledDelayMillis() throws Exception {
        ScheduledFuture<?> scheduled = scheduledFlush();
        assertNotNull(scheduled);
        return scheduled.getDelay(TimeUnit.MILLISECONDS);
    }

    private static void assertScheduledNear(long expectedDelayMillis) throws Exception {
        long actual = scheduledDelayMillis();
        assertTrue("scheduled too early: " + actual,
                actual >= expectedDelayMillis - TimeUnit.SECONDS.toMillis(2L));
        assertTrue("scheduled too late: " + actual,
                actual <= expectedDelayMillis + TimeUnit.SECONDS.toMillis(1L));
    }

    private static int outboxSize() throws Exception {
        return ((java.util.Queue<?>) instanceValue(outboxState(), "rawHits")).size()
                + ((java.util.Queue<?>) instanceValue(outboxState(), "events")).size();
    }

    private static Object outboxState() throws Exception {
        return ((AtomicReference<?>) staticValue(HookDiagnostics.class, "OUTBOX")).get();
    }

    private static void resetHookDiagnostics() throws Exception {
        ScheduledThreadPoolExecutor executor = executor();
        executor.submit(() -> {}).get(2L, TimeUnit.SECONDS);

        Object flushLock = staticValue(HookDiagnostics.class, "FLUSH_SCHEDULE_LOCK");
        synchronized (flushLock) {
            ScheduledFuture<?> scheduled = scheduledFlush();
            if (scheduled != null) scheduled.cancel(false);
            setStaticField(HookDiagnostics.class, "sScheduledFlush", null);
            setStaticField(
                    HookDiagnostics.class,
                    "sScheduledFlushDeadlineNanos",
                    Long.MAX_VALUE);
            flushScheduled().set(false);
        }
        for (Runnable task : new ArrayList<>(executor.getQueue())) {
            if (task instanceof Future<?>) ((Future<?>) task).cancel(false);
        }
        executor.purge();

        Method resetForEpoch = HookDiagnostics.class.getDeclaredMethod(
                "resetForEpoch", long.class);
        resetForEpoch.setAccessible(true);
        resetForEpoch.invoke(null, 0L);
        clearMap("FEATURE_STATUSES");
        clearMap("FEATURE_UPDATED_AT");
        clearMap("PROCESS_STAGES");
        clearMap("PROCESS_ERRORS");
        clearMap("PROCESS_UPDATED_AT");
        stickyGeneration().set(0L);
        reportedStickyGeneration().set(0L);
        ((AtomicLong) staticValue(HookDiagnostics.class, "EVENT_SEQUENCE")).set(0L);
        moduleUid().set(-1);
        @SuppressWarnings("unchecked")
        AtomicReference<HookDiagnostics.ConfigReportState> config =
                (AtomicReference<HookDiagnostics.ConfigReportState>) staticValue(
                        HookDiagnostics.class, "CURRENT_CONFIG_REPORT");
        config.set(new HookDiagnostics.ConfigReportState(
                Integer.MIN_VALUE, "", "", 0L, ""));

        setStaticField(HookDiagnostics.class, "sLastReportedConfig", null);
        setStaticField(HookDiagnostics.class, "sLastConfigReportAt", 0L);
        setStaticField(HookDiagnostics.class, "sConfigUpdatedAt", 0L);
        setStaticField(HookDiagnostics.class, "sEpoch", -1L);
        setStaticField(HookDiagnostics.class, "sNextBatchSequence", 0L);
        setStaticField(HookDiagnostics.class, "sInFlight", null);
        setStaticField(HookDiagnostics.class, "sTransportError", "");
        setStaticField(
                HookDiagnostics.class,
                "sTransportRetryDelayMs",
                TimeUnit.SECONDS.toMillis(15L));
        setStaticField(HookDiagnostics.class, "sTransportRetryNotBeforeNanos", 0L);
        setStaticField(
                HookDiagnostics.class,
                "sLastEventBatchAt",
                android.os.SystemClock.elapsedRealtime());
        setStaticField(HookDiagnostics.class, "sDrainContinuationPending", false);
        setStaticField(HookDiagnostics.class, "sLastTransportLogAt", 0L);
        setUserUnlocked(true);
    }

    private static void clearMap(String fieldName) throws Exception {
        ((Map<?, ?>) staticValue(HookDiagnostics.class, fieldName)).clear();
    }

    private static AtomicInteger moduleUid() throws Exception {
        return (AtomicInteger) staticValue(HookDiagnostics.class, "MODULE_UID");
    }

    private static Object staticValue(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object instanceValue(Object owner, String fieldName) throws Exception {
        Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setStaticField(Class<?> owner, String fieldName, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static HookDiagnostics.ConfigReportState state(
            String source, String path, String error) {
        return state(7, source, path, 1L, error);
    }

    private static HookDiagnostics.ConfigReportState state(
            int revision, String source, String path, long latencyMs, String error) {
        return new HookDiagnostics.ConfigReportState(
                revision, source, path, latencyMs, error);
    }

    private static final class RecordingProvider extends ContentProvider {
        final List<Bundle> requests = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final ArrayDeque<Function<Bundle, Bundle>> responses = new ArrayDeque<>();

        void enqueue(Function<Bundle, Bundle> response) {
            responses.addLast(response);
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            methods.add(method);
            if (!BridgeContract.METHOD_REPORT_BATCH.equals(method)) return null;
            requests.add(extras);
            Function<Bundle, Bundle> response = responses.pollFirst();
            return response == null ? null : response.apply(extras);
        }

        @Override
        public Cursor query(
                Uri uri,
                String[] projection,
                String selection,
                String[] selectionArgs,
                String sortOrder) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(
                Uri uri,
                ContentValues values,
                String selection,
                String[] selectionArgs) {
            return 0;
        }
    }

    @Implements(DiagnosticsBootId.class)
    public static final class ShadowDiagnosticsBootId {
        @Implementation
        protected static String readCurrent() {
            return "test-boot";
        }
    }
}
