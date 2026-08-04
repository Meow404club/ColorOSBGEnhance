package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.R;
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
                        currentStage(),
                        "older-boot")),
                CURRENT_BOOT,
                7,
                BuildConfig.VERSION_CODE);

        assertEquals(HomeDiagnosticsStatus.Activation.INACTIVE, status.activation);
        assertFalse(status.systemProcessReady);
    }

    @Test
    public void reportsActiveOnlyWhenBothProcessesAndPrimaryHooksAreReady() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                currentStage(), CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                currentStage(), CURRENT_BOOT),
                        config(BridgeContract.PROCESS_SYSTEM_SERVER, 8),
                        config(BridgeContract.PROCESS_ATHENA, 8),
                        identity(BridgeContract.PROCESS_SYSTEM_SERVER, 8, 2),
                        identity(BridgeContract.PROCESS_ATHENA, 8, 2),
                        hook(HookTargetIds.OGUARD_KILL, "INSTALLED:1"),
                        hook(HookTargetIds.OGUARD_FORCE_STOP, "INSTALLED:1"),
                        hook(HookTargetIds.ATHENA_PERIODIC, "INSTALLED:1"),
                        hook(HookTargetIds.ATHENA_PERIODIC, "INSTALLED:duplicate")),
                CURRENT_BOOT,
                8,
                BuildConfig.VERSION_CODE);

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
                1,
                BuildConfig.VERSION_CODE);

        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL, status.activation);
        assertFalse(status.systemProcessReady);
    }

    @Test
    public void optionalHookCannotReplaceAMissingPrimaryHook() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                currentStage(), CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                currentStage(), CURRENT_BOOT),
                        hook(HookTargetIds.OGUARD_KILL, "INSTALLED:1"),
                        hook(HookTargetIds.OGUARD_FORCE_STOP, "INSTALLED:1"),
                        hook(HookTargetIds.FAST_CLEAR_DISPATCH, "INSTALLED:1")),
                CURRENT_BOOT,
                1,
                BuildConfig.VERSION_CODE);

        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL, status.activation);
        assertEquals(2, status.installedPrimaryHooks);
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
                7,
                BuildConfig.VERSION_CODE);

        assertFalse(status.systemConfigSynced);
        assertFalse(status.athenaConfigSynced);
        assertTrue(status.athenaConfigError);
        assertTrue(status.hasConfigError());
        assertEquals(R.string.effect_config_error, status.effectNotice(true, false, false));
    }

    @Test
    public void identityFailureOrStaleRevisionPreventsHealthyStatus() {
        HomeDiagnosticsStatus failed = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                currentStage(), CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                currentStage(), CURRENT_BOOT),
                        identity(BridgeContract.PROCESS_SYSTEM_SERVER,
                                8, 3, "FAILED_FAIL_OPEN_IDENTITY_REBUILD"),
                        identity(BridgeContract.PROCESS_ATHENA, 8, 3)),
                CURRENT_BOOT,
                8,
                3L,
                BuildConfig.VERSION_CODE);

        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL, failed.activation);
        assertTrue(failed.hasIdentityError());
        assertEquals(R.string.effect_identity_error,
                failed.effectNotice(true, true, false));

        HomeDiagnosticsStatus stale = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                currentStage(), CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                currentStage(), CURRENT_BOOT),
                        identity(BridgeContract.PROCESS_SYSTEM_SERVER, 7, 2),
                        identity(BridgeContract.PROCESS_ATHENA, 8, 2)),
                CURRENT_BOOT,
                8,
                3L,
                BuildConfig.VERSION_CODE);

        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL, stale.activation);
        assertFalse(stale.hasIdentityError());
        assertEquals(R.string.effect_identity_pending,
                stale.effectNotice(true, true, false));
    }

    @Test
    public void explicitlyUnavailablePrimaryHookDisablesOnlyItsOwnControl() {
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                currentStage(), CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                currentStage(), CURRENT_BOOT),
                        config(BridgeContract.PROCESS_SYSTEM_SERVER, 7),
                        config(BridgeContract.PROCESS_ATHENA, 7),
                        identity(BridgeContract.PROCESS_SYSTEM_SERVER, 7, 1),
                        identity(BridgeContract.PROCESS_ATHENA, 7, 1),
                        hook(HookTargetIds.ATHENA_PERIODIC, "FAILED_FAIL_OPEN"),
                        hook(HookTargetIds.OGUARD_KILL, "UNSUPPORTED"),
                        hook(HookTargetIds.OGUARD_FORCE_STOP, "INSTALLED")),
                CURRENT_BOOT,
                7,
                BuildConfig.VERSION_CODE);

        assertTrue(status.isPrimaryHookExplicitlyUnavailable(
                HookTargetIds.ATHENA_PERIODIC));
        assertTrue(status.isPrimaryHookExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));
        assertFalse(status.isPrimaryHookExplicitlyUnavailable(
                HookTargetIds.OGUARD_FORCE_STOP));
        assertEquals(R.string.effect_primary_hook_unavailable,
                status.effectNotice(true, true, false));
    }

    @Test
    public void effectNoticeSeparatesRestartSyncAndHealthyStates() {
        HomeDiagnosticsStatus inactive = HomeDiagnosticsStatus.evaluate(
                Collections.emptyList(), CURRENT_BOOT, 7, BuildConfig.VERSION_CODE);
        assertEquals(R.string.effect_restart_required,
                inactive.effectNotice(false, false, false));

        HomeDiagnosticsStatus active = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER,
                                currentStage(), CURRENT_BOOT),
                        process(BridgeContract.PROCESS_ATHENA,
                                currentStage(), CURRENT_BOOT),
                        config(BridgeContract.PROCESS_SYSTEM_SERVER, 7),
                        config(BridgeContract.PROCESS_ATHENA, 7),
                        identity(BridgeContract.PROCESS_SYSTEM_SERVER, 7, 1),
                        identity(BridgeContract.PROCESS_ATHENA, 7, 1),
                        hook(HookTargetIds.ATHENA_PERIODIC, "INSTALLED"),
                        hook(HookTargetIds.OGUARD_KILL, "INSTALLED"),
                        hook(HookTargetIds.OGUARD_FORCE_STOP, "INSTALLED")),
                CURRENT_BOOT,
                7,
                BuildConfig.VERSION_CODE);
        assertEquals(R.string.effect_config_service_waiting,
                active.effectNotice(false, true, false));
        assertEquals(R.string.effect_config_pending,
                active.effectNotice(true, false, false));
        assertEquals(R.string.effect_config_error,
                active.effectNotice(true, false, true));
        assertEquals(0, active.effectNotice(true, true, false));
    }

    @Test
    public void legacyOrDifferentModuleVersionRequiresRestart() {
        HomeDiagnosticsStatus legacy = HomeDiagnosticsStatus.evaluate(
                Collections.singletonList(process(
                        BridgeContract.PROCESS_SYSTEM_SERVER,
                        "FEATURES_INSTALLED",
                        CURRENT_BOOT)),
                CURRENT_BOOT,
                1,
                BuildConfig.VERSION_CODE);
        HomeDiagnosticsStatus differentVersion = HomeDiagnosticsStatus.evaluate(
                Collections.singletonList(process(
                        BridgeContract.PROCESS_SYSTEM_SERVER,
                        ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE + 1),
                        CURRENT_BOOT)),
                CURRENT_BOOT,
                1,
                BuildConfig.VERSION_CODE);

        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL, legacy.activation);
        assertFalse(legacy.systemProcessReady);
        assertEquals(HomeDiagnosticsStatus.Activation.PARTIAL,
                differentVersion.activation);
        assertFalse(differentVersion.systemProcessReady);
    }

    private static String currentStage() {
        return ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE);
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
                .processId(HookTargetIds.expectedProcessId(targetId))
                .hookTargetId(targetId)
                .installStatus(installStatus)
                .bootId(CURRENT_BOOT)
                .build();
    }

    private static DiagnosticsHookState identity(
            String processId, int configRevision, int policyRevision) {
        return identity(processId, configRevision, policyRevision, "IDENTITY_READY");
    }

    private static DiagnosticsHookState identity(
            String processId,
            int configRevision,
            int policyRevision,
            String installStatus) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_IDENTITY)
                .installStatus(installStatus)
                .configRevision(configRevision)
                .policyRevision(policyRevision)
                .bootId(CURRENT_BOOT)
                .build();
    }
}
