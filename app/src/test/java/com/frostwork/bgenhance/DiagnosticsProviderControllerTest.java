package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.os.Process;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireEncoder;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWriteBatch;
import com.frostwork.bgenhance.diagnostics.NormalizedDiagnosticsEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DiagnosticsProviderControllerTest {
    private static final int MODULE_UID = 12_345;
    private static final int ATHENA_UID = 23_456;
    private static final long EPOCH = 7L;

    @Test
    public void onlyModuleSystemAndResolvedAthenaUidsAreAllowed() {
        DiagnosticsProviderController controller = controller(batch -> accepted(batch.size()));

        assertTrue(controller.isAllowedCaller(MODULE_UID));
        assertTrue(controller.isAllowedCaller(Process.SYSTEM_UID));
        assertTrue(controller.isAllowedCaller(ATHENA_UID));
        assertFalse(controller.isAllowedCaller(99_999));

        try {
            controller.handle(99_999, BridgeContract.METHOD_PING, null);
            fail("untrusted caller must be rejected");
        } catch (SecurityException expected) {
            assertEquals("diagnostics caller rejected", expected.getMessage());
        }
    }

    @Test
    public void pingReturnsAuthoritativeVersionBootAndEpoch() {
        Bundle response = controller(batch -> accepted(batch.size()))
                .handle(MODULE_UID, BridgeContract.METHOD_PING, null);

        assertTrue(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals(BridgeContract.BRIDGE_VERSION,
                response.getInt(BridgeContract.EXTRA_BRIDGE_VERSION));
        assertEquals("boot", response.getString(BridgeContract.EXTRA_BOOT_ID));
        assertEquals(EPOCH, response.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH));
    }

    @Test
    public void acceptedBatchIsEnqueuedExactlyOnceAndFullyAcknowledged() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return accepted(batch.size());
        });
        DiagnosticsWriteBatch batch = batch("boot", EPOCH, 4L);

        Bundle response = controller.handle(
                Process.SYSTEM_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch));

        assertEquals(1, calls.get());
        assertTrue(response.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals(batch.size(), response.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(0, response.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    @Test
    public void bootAndEpochMismatchNeverReachBackend() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return accepted(batch.size());
        });

        Bundle boot = controller.handle(MODULE_UID, BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch("other-boot", EPOCH, 0L)));
        Bundle epoch = controller.handle(MODULE_UID, BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch("boot", EPOCH + 1L, 0L)));

        assertEquals(0, calls.get());
        assertFalse(boot.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("boot_mismatch", boot.getString(BridgeContract.EXTRA_ERROR));
        assertFalse(epoch.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("epoch_mismatch", epoch.getString(BridgeContract.EXTRA_ERROR));
        assertEquals(EPOCH, epoch.getLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH));
    }

    @Test
    public void parserRejectionAndBackendBackpressureAreExplicit() {
        AtomicInteger calls = new AtomicInteger();
        DiagnosticsProviderController controller = controller(batch -> {
            calls.incrementAndGet();
            return new DiagnosticsProviderController.BatchResult(0, batch.size());
        });

        Bundle invalid = controller.handle(
                MODULE_UID, BridgeContract.METHOD_REPORT_BATCH, new Bundle());
        Bundle busy = controller.handle(
                MODULE_UID,
                BridgeContract.METHOD_REPORT_BATCH,
                DiagnosticsWireEncoder.encodeTyped(batch("boot", EPOCH, 0L)));

        assertEquals(1, calls.get());
        assertFalse(invalid.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("invalid_batch_identity",
                invalid.getString(BridgeContract.EXTRA_ERROR));
        assertFalse(busy.getBoolean(BridgeContract.EXTRA_OK));
        assertEquals("backend_busy", busy.getString(BridgeContract.EXTRA_ERROR));
        assertEquals(0, busy.getInt(BridgeContract.EXTRA_ACCEPTED_ITEMS));
        assertEquals(1, busy.getInt(BridgeContract.EXTRA_DROPPED_ITEMS));
    }

    @Test
    public void unresolvedAthenaUidDoesNotAuthorizeArbitraryNegativeUid() {
        DiagnosticsProviderController controller = new DiagnosticsProviderController(
                MODULE_UID, -1, "boot", () -> EPOCH,
                batch -> accepted(batch.size()), new DiagnosticsWireParser());

        assertFalse(controller.isAllowedCaller(-1));
    }

    private static DiagnosticsProviderController controller(
            DiagnosticsProviderController.BatchSink sink) {
        return new DiagnosticsProviderController(
                MODULE_UID, ATHENA_UID, "boot", () -> EPOCH,
                sink, new DiagnosticsWireParser());
    }

    private static DiagnosticsProviderController.BatchResult accepted(int count) {
        return new DiagnosticsProviderController.BatchResult(count, 0);
    }

    private static DiagnosticsWriteBatch batch(String bootId, long epoch, long sequence) {
        NormalizedDiagnosticsEvent event = NormalizedDiagnosticsEvent.builder()
                .timestampMillis(System.currentTimeMillis())
                .bootId(bootId)
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                .behavior("FREEZE")
                .source("FAST")
                .packageName("com.example.target")
                .uid(10_123)
                .decision("POLICY_BLOCKS_FREEZE")
                .result(DiagnosticsResult.BLOCKED)
                .eventToken("event-1")
                .build();
        return DiagnosticsWriteBatch.builder()
                .wireIdentity(
                        DiagnosticsWireParser.WIRE_VERSION,
                        bootId,
                        "system_server",
                        "sender-instance",
                        epoch,
                        sequence)
                .countingMode(DiagnosticsWriteBatch.CountingMode.TYPED)
                .addEvent(event)
                .build();
    }
}
