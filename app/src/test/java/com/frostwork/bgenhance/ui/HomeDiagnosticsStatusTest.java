package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class HomeDiagnosticsStatusTest {
    private static final String CURRENT_BOOT = "current-boot";

    @Test
    public void ignoresRowsFromOlderBoots() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Collections.singletonList(process(
                        BridgeContract.PROCESS_SYSTEM_SERVER,
                        "FEATURES_INSTALLED",
                        "older-boot")),
                CURRENT_BOOT,
                7);

        assertEquals(HomeDiagnosticsStatus.Activation.INACTIVE, status.activation);
        assertFalse(status.systemProcessReady);
    }

    @Test
    public void reportsActiveOnlyWhenBothProcessesAndPrimaryHooksAreReady() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                "FEATURES_INSTALLED", CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                "FEATURES_INSTALLED", CURRENT_BOOT),
                        config(BridgeContract.PROCESS_SYSTEM_SERVER, 8),
                        config(BridgeContract.PROCESS_ATHENA, 8),
                        hook(HookTargetIds.OGUARD_KILL, "INSTALLED:1"),
                        hook(HookTargetIds.OGUARD_FORCE_STOP, "INSTALLED:1"),
                        hook(HookTargetIds.ATHENA_PERIODIC, "INSTALLED:1"),
                        hook(HookTargetIds.ATHENA_PERIODIC, "INSTALLED:duplicate")),
                CURRENT_BOOT,
                8);

        assertEquals(HomeDiagnosticsStatus.Activation.ACTIVE, status.activation);
        assertEquals(3, status.installedPrimaryHooks);
        assertTrue(status.systemConfigSynced);
        assertTrue(status.athenaConfigSynced);
    }

    @Test
    public void currentButIncompleteRuntimeIsPartial() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Collections.singletonList(process(
                        BridgeContract.PROCESS_SYSTEM_SERVER,
                        "ENTRY_LOADED",
                        CURRENT_BOOT)),
                CURRENT_BOOT,
                1);

        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL, status.activation);
        assertFalse(status.systemProcessReady);
    }

    @Test
    public void configMustBeReadyAtCurrentRevision() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        config(BridgeContract.PROCESS_SYSTEM_SERVER, 6),
                        DiagnosticsHookState.builder()
                                .processId(BridgeContract.PROCESS_ATHENA)
                                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                                .installStatus("CONFIG_ERROR")
                                .configRevision(9)
                                .bootId(CURRENT_BOOT)
                                .build()),
                CURRENT_BOOT,
                7);

        assertFalse(status.systemConfigSynced);
        assertFalse(status.athenaConfigSynced);
    }

    private static DiagnosticsHookState process(
            String processId, String stage, String bootId) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_PROCESS)
                .installStatus(stage)
                .bootId(bootId)
                .build();
    }

    private static DiagnosticsHookState config(String processId, int revision) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus("CONFIG_READY")
                .configRevision(revision)
                .bootId(CURRENT_BOOT)
                .build();
    }

    private static DiagnosticsHookState hook(String targetId, String installStatus) {
        return DiagnosticsHookState.builder()
                .processId(BridgeContract.PROCESS_SYSTEM_SERVER)
                .hookTargetId(targetId)
                .installStatus(installStatus)
                .bootId(CURRENT_BOOT)
                .build();
    }
}
