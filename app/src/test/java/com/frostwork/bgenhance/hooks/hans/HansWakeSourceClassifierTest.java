package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class HansWakeSourceClassifierTest {
    @Test
    public void classifiesEveryVerifiedKernelType() {
        assertResolved(WakeSource.ASYNC_BINDER,
                HansWakeSourceClassifier.classifyKernel(0));
        assertResolved(WakeSource.SYNC_BINDER,
                HansWakeSourceClassifier.classifyKernel(1));
        assertResolved(WakeSource.TRANSACTION_BINDER,
                HansWakeSourceClassifier.classifyKernel(2));
        assertResolved(WakeSource.SIGNAL,
                HansWakeSourceClassifier.classifyKernel(3));
        assertResolved(WakeSource.PACKET,
                HansWakeSourceClassifier.classifyKernel(4));
    }

    @Test
    public void unknownKernelTypesFailOpen() {
        assertFailure(HansWakeSourceClassifier.Failure.UNKNOWN_KERNEL_TYPE,
                HansWakeSourceClassifier.classifyKernel(-1));
        assertFailure(HansWakeSourceClassifier.Failure.UNKNOWN_KERNEL_TYPE,
                HansWakeSourceClassifier.classifyKernel(5));
    }

    @Test
    public void classifiesVerifiedTerminalReasonFamilies() {
        Map<String, WakeSource> reasons = new LinkedHashMap<>();
        reasons.put("Packet", WakeSource.PACKET);
        reasons.put("Alarm", WakeSource.ALARM);
        reasons.put("Proxy2AlignAlarm", WakeSource.ALARM);
        reasons.put("AsyncBinderMax", WakeSource.ASYNC_BINDER);
        reasons.put("SyncBinder", WakeSource.SYNC_BINDER);
        reasons.put("TransBinder", WakeSource.TRANSACTION_BINDER);
        reasons.put("Signal", WakeSource.SIGNAL);
        reasons.put("inputDispatcher", WakeSource.ACTIVITY_INPUT);
        reasons.put("BindService", WakeSource.SERVICE);
        reasons.put("Broadcast", WakeSource.BROADCAST);
        reasons.put("Provider", WakeSource.PROVIDER);
        reasons.put("Sync", WakeSource.JOB_SYNC);
        reasons.put("Wakelock", WakeSource.WAKE_LOCK);
        reasons.put("MediaUnmute", WakeSource.AUDIO_MEDIA);
        reasons.put("TcpRetransmission", WakeSource.CONNECTIVITY);
        reasons.put("Watchdog", WakeSource.SYSTEM_SCENE);

        for (Map.Entry<String, WakeSource> entry : reasons.entrySet()) {
            assertResolved(entry.getValue(), HansWakeSourceClassifier.classifyTerminal(
                    entry.getKey(), "component"));
        }
    }

    @Test
    public void moduleRecoveryIsExactAndAlwaysIdentifiable() {
        assertTrue(HansWakeSourceClassifier.isModuleRecovery(
                "force", "BgEnhancePolicy"));
        assertResolved(WakeSource.INTERNAL_OPERATION,
                HansWakeSourceClassifier.classifyTerminal(
                        "force", "BgEnhancePolicy"));

        assertFalse(HansWakeSourceClassifier.isModuleRecovery(
                "force", "bgenhancepolicy"));
        assertResolved(WakeSource.SYSTEM_SCENE,
                HansWakeSourceClassifier.classifyTerminal(
                        "force", "bgenhancepolicy"));
    }

    @Test
    public void terminalReasonsMatchExactlyAndUnknownsFailOpen() {
        assertFailure(HansWakeSourceClassifier.Failure.NULL_REASON,
                HansWakeSourceClassifier.classifyTerminal(null, ""));
        assertFailure(HansWakeSourceClassifier.Failure.EMPTY_REASON,
                HansWakeSourceClassifier.classifyTerminal("", ""));
        assertFailure(HansWakeSourceClassifier.Failure.UNKNOWN_REASON,
                HansWakeSourceClassifier.classifyTerminal(" packet", ""));
        assertFailure(HansWakeSourceClassifier.Failure.UNKNOWN_REASON,
                HansWakeSourceClassifier.classifyTerminal("packet", ""));
        assertFailure(HansWakeSourceClassifier.Failure.UNKNOWN_REASON,
                HansWakeSourceClassifier.classifyTerminal("futureReason", ""));
    }

    private static void assertResolved(
            WakeSource expected, HansWakeSourceClassifier.Resolution resolution) {
        assertTrue(resolution.isResolved());
        assertEquals(expected, resolution.sourceOrNull());
        assertNull(resolution.failureOrNull());
    }

    private static void assertFailure(
            HansWakeSourceClassifier.Failure expected,
            HansWakeSourceClassifier.Resolution resolution) {
        assertFalse(resolution.isResolved());
        assertNull(resolution.sourceOrNull());
        assertEquals(expected, resolution.failureOrNull());
    }
}
