package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FeatureApplyStatusTest {
    private static final String BOOT = "boot";
    private static final int REVISION = 8;

    @Test
    public void healthyCurrentPageNeedsNoPersistentNotice() {
        assertEquals(0, message(readyRows("INSTALLED", "INSTALLED"), true, REVISION));
    }

    @Test
    public void staleProcessRequiresRestart() {
        List<DiagnosticsHookState> rows = readyRows("INSTALLED", "INSTALLED");
        rows.set(0, process(ModuleRuntimeStatus.featuresInstalled(
                BuildConfig.VERSION_CODE + 1)));

        assertEquals(R.string.effect_restart_required,
                message(rows, true, REVISION));
    }

    @Test
    public void unavailablePageHookIsReportedWithoutDisablingOtherPages() {
        assertEquals(R.string.effect_feature_hook_unavailable,
                message(readyRows("FAILED_FAIL_OPEN", "INSTALLED"), true, REVISION));
    }

    @Test
    public void disabledFeatureHookDoesNotCreatePersistentWarning() {
        List<FeatureCatalog.FeatureSpec> onlyTournament = java.util.Collections.singletonList(
                FeatureCatalog.forGroup(FeatureCatalog.GROUP_FAST_CLEAR).get(4));
        assertEquals(0, message(
                readyRows("FAILED_FAIL_OPEN", "INSTALLED"),
                true,
                REVISION,
                onlyTournament));
    }

    @Test
    public void localRevisionWaitsForTheHookProcess() {
        assertEquals(R.string.effect_config_pending,
                message(readyRows("INSTALLED", "INSTALLED"), true, REVISION + 1));
    }

    @Test
    public void disconnectedServiceExplainsThatTheLocalSaveIsRetained() {
        assertEquals(R.string.effect_config_service_waiting,
                message(readyRows("INSTALLED", "INSTALLED"), false, REVISION));
    }

    private static int message(
            List<DiagnosticsHookState> rows,
            boolean serviceConnected,
            int localRevision) {
        return message(rows, serviceConnected, localRevision,
                FeatureCatalog.forGroup(FeatureCatalog.GROUP_FAST_CLEAR));
    }

    private static int message(
            List<DiagnosticsHookState> rows,
            boolean serviceConnected,
            int localRevision,
            List<FeatureCatalog.FeatureSpec> specs) {
        HookStatusViewModel.State state = HookStatusViewModel.State.ready(BOOT, rows);
        return FeatureApplyStatus.message(
                state,
                serviceConnected,
                false,
                localRevision,
                specs);
    }

    private static List<DiagnosticsHookState> readyRows(
            String fastClearStatus, String tournamentStatus) {
        return new java.util.ArrayList<>(Arrays.asList(
                process(ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE)),
                config(REVISION),
                hook(HookTargetIds.FAST_CLEAR_DISPATCH, fastClearStatus),
                hook(HookTargetIds.TOURNAMENT, tournamentStatus)));
    }

    private static DiagnosticsHookState process(String status) {
        return DiagnosticsHookState.builder()
                .processId(BridgeContract.PROCESS_ATHENA)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_PROCESS)
                .installStatus(status)
                .bootId(BOOT)
                .build();
    }

    private static DiagnosticsHookState config(int revision) {
        return DiagnosticsHookState.builder()
                .processId(BridgeContract.PROCESS_ATHENA)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus("CONFIG_READY")
                .configRevision(revision)
                .bootId(BOOT)
                .build();
    }

    private static DiagnosticsHookState hook(String target, String status) {
        return DiagnosticsHookState.builder()
                .processId(HookTargetIds.expectedProcessId(target))
                .hookTargetId(target)
                .installStatus(status)
                .bootId(BOOT)
                .build();
    }
}
