package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.ui.HookStatusViewModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class ResidencyApplyStatusTest {
    private static final String CURRENT_BOOT = "current-boot";
    private static final List<String> REQUIRED_HOOKS = HookTargetIds.residencyRequired();

    @Test
    public void loadingAndDatabaseFailureNeverPromiseImmediateApplication() {
        Source source = new Source();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);

        viewModel.refresh();
        int loading = message(
                last(states), true, false, 7, true, true);
        assertEquals(R.string.status_checking_summary, loading);
        assertNotEquals(R.string.residency_apply_without_restart, loading);

        source.future.complete(DiagnosticsQuery.HookStatePage.empty("database_unavailable"));
        int failed = message(
                last(states), true, false, 7, true, true);
        assertEquals(R.string.effect_runtime_unavailable, failed);
        assertNotEquals(R.string.residency_apply_without_restart, failed);
    }

    @Test
    public void bothProcessesMustReportCurrentConfigRevision() {
        List<DiagnosticsHookState> rows = completeRows(7);
        replaceConfig(rows, BridgeContract.PROCESS_ATHENA, 6);

        HookStatusViewModel.State state = ready(rows);
        assertEquals(R.string.residency_apply_sync_pending,
                message(state, true, false, 7, true, true));

        replaceConfig(rows, BridgeContract.PROCESS_ATHENA, 7);
        state = ready(rows);
        assertEquals(R.string.residency_apply_without_restart,
                message(state, true, false, 7, true, true));
    }

    @Test
    public void everyRequiredHookMustBeInstalled() {
        List<DiagnosticsHookState> rows = completeRows(7);
        String missing = REQUIRED_HOOKS.iterator().next();
        rows.removeIf(row -> missing.equals(row.hookTargetId));

        assertEquals(R.string.residency_apply_partial,
                message(
                        ready(rows), true, false, 7, true, true));
        assertTrue(REQUIRED_HOOKS.contains(
                HookTargetIds.HANS_CORE_EXEMPTION));
    }

    @Test
    public void unusedUnsupportedHookDoesNotMakeResidencyPartial() {
        List<DiagnosticsHookState> rows = completeRows(7);
        rows.removeIf(row -> HookTargetIds.HANS_CORE_EXEMPTION.equals(row.hookTargetId));
        rows.add(DiagnosticsHookState.builder()
                .processId(BridgeContract.PROCESS_SYSTEM_SERVER)
                .hookTargetId(HookTargetIds.HANS_CORE_EXEMPTION)
                .installStatus("UNSUPPORTED_FREEZE_GATE_INCOMPLETE")
                .bootId(CURRENT_BOOT)
                .build());

        HookStatusViewModel.State state = ready(rows);
        assertEquals(R.string.residency_apply_without_restart,
                ResidencyApplyStatus.message(
                        state, true, false, 7, 4L, true, true,
                        Collections.emptySet()));
        assertEquals(R.string.residency_apply_partial,
                ResidencyApplyStatus.message(
                        state, true, false, 7, 4L, true, true,
                        Collections.singleton(HookTargetIds.HANS_CORE_EXEMPTION)));
    }

    @Test
    public void missingCurrentBootProcessRequiresActivationOrRestart() {
        HookStatusViewModel.State empty = ready(new ArrayList<>());
        assertEquals(R.string.residency_apply_after_activation,
                message(empty, false, false, 7, true, true));

        List<DiagnosticsHookState> oneProcess = new ArrayList<>();
        oneProcess.add(process(BridgeContract.PROCESS_SYSTEM_SERVER));
        assertEquals(R.string.residency_apply_after_restart,
                message(
                        ready(oneProcess), true, false, 7, true, true));
    }

    @Test
    public void processLoadedFromAnOlderModuleVersionRequiresRestart() {
        List<DiagnosticsHookState> rows = completeRows(7);
        rows.removeIf(row -> BridgeContract.PROCESS_SYSTEM_SERVER.equals(row.processId)
                && DiagnosticsTargetIds.RUNTIME_PROCESS.equals(row.hookTargetId));
        rows.add(process(
                BridgeContract.PROCESS_SYSTEM_SERVER,
                ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE + 1)));

        assertEquals(R.string.residency_apply_after_restart,
                message(
                        ready(rows), true, false, 7, true, true));
    }

    @Test
    public void disabledRuntimeLayersNeverPromiseImmediateApplication() {
        HookStatusViewModel.State state = ready(completeRows(7));

        assertEquals(R.string.residency_apply_master_disabled,
                message(state, true, false, 7, false, true));
        assertEquals(R.string.residency_apply_disabled,
                message(state, true, false, 7, true, false));
    }

    @Test
    public void configurationFailureIsNotReportedAsPendingSynchronization() {
        List<DiagnosticsHookState> rows = completeRows(7);
        rows.removeIf(row -> BridgeContract.PROCESS_ATHENA.equals(row.processId)
                && DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId));
        rows.add(configError(BridgeContract.PROCESS_ATHENA));

        assertEquals(R.string.residency_apply_config_error,
                message(
                        ready(rows), true, false, 7, true, true));
    }

    @Test
    public void identityFailureAndStaleIdentityNeverPromiseImmediateApplication() {
        List<DiagnosticsHookState> rows = completeRows(7);
        replaceIdentity(rows, BridgeContract.PROCESS_ATHENA,
                "FAILED_FAIL_OPEN_IDENTITY_REBUILD", 7, 4);
        assertEquals(R.string.residency_apply_identity_error,
                message(
                        ready(rows), true, false, 7, 4L, true, true));

        rows = completeRows(7);
        replaceIdentity(rows, BridgeContract.PROCESS_SYSTEM_SERVER,
                "IDENTITY_READY", 7, 3);
        assertEquals(R.string.residency_apply_identity_pending,
                message(
                        ready(rows), true, false, 7, 4L, true, true));
    }

    @Test
    public void localConfigurationFailureIsNotReportedAsPendingSynchronization() {
        HookStatusViewModel.State state = ready(completeRows(7));

        assertEquals(R.string.residency_apply_config_error,
                message(state, true, true, 7, true, true));
    }

    @Test
    public void disabledPolicyDoesNotHideActivationOrConfigurationFailures() {
        assertEquals(R.string.residency_apply_after_activation,
                message(
                        ready(new ArrayList<>()), false, false, 7, false, false));

        List<DiagnosticsHookState> rows = completeRows(7);
        rows.removeIf(row -> BridgeContract.PROCESS_ATHENA.equals(row.processId)
                && DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId));
        rows.add(configError(BridgeContract.PROCESS_ATHENA));
        assertEquals(R.string.residency_apply_config_error,
                message(
                        ready(rows), true, false, 7, false, false));
    }

    @Test
    public void noticeToneMatchesApplicationState() {
        assertEquals(ResidencyApplyStatus.Tone.SUCCESS,
                ResidencyApplyStatus.tone(R.string.residency_apply_without_restart));
        assertEquals(ResidencyApplyStatus.Tone.ERROR,
                ResidencyApplyStatus.tone(R.string.residency_apply_config_error));
        assertEquals(ResidencyApplyStatus.Tone.ERROR,
                ResidencyApplyStatus.tone(R.string.residency_apply_identity_error));
        assertEquals(ResidencyApplyStatus.Tone.WARNING,
                ResidencyApplyStatus.tone(R.string.residency_apply_after_restart));
        assertEquals(ResidencyApplyStatus.Tone.INFO,
                ResidencyApplyStatus.tone(R.string.residency_apply_disabled));
    }

    private static List<DiagnosticsHookState> completeRows(int revision) {
        ArrayList<DiagnosticsHookState> rows = new ArrayList<>();
        rows.add(process(BridgeContract.PROCESS_SYSTEM_SERVER));
        rows.add(process(BridgeContract.PROCESS_ATHENA));
        rows.add(config(BridgeContract.PROCESS_SYSTEM_SERVER, revision));
        rows.add(config(BridgeContract.PROCESS_ATHENA, revision));
        rows.add(identity(BridgeContract.PROCESS_SYSTEM_SERVER, revision, 4));
        rows.add(identity(BridgeContract.PROCESS_ATHENA, revision, 4));
        for (String hookTargetId : REQUIRED_HOOKS) {
            rows.add(DiagnosticsHookState.builder()
                    .processId(HookTargetIds.expectedProcessId(hookTargetId))
                    .hookTargetId(hookTargetId)
                    .installStatus("INSTALLED")
                    .bootId(CURRENT_BOOT)
                    .build());
        }
        return rows;
    }

    private static int message(
            HookStatusViewModel.State state,
            boolean serviceConnected,
            boolean localConfigError,
            int localConfigRevision,
            boolean masterEnabled,
            boolean residencyEnabled) {
        return ResidencyApplyStatus.message(
                state, serviceConnected, localConfigError, localConfigRevision,
                masterEnabled, residencyEnabled, Set.copyOf(REQUIRED_HOOKS));
    }

    private static int message(
            HookStatusViewModel.State state,
            boolean serviceConnected,
            boolean localConfigError,
            int localConfigRevision,
            long localPolicyRevision,
            boolean masterEnabled,
            boolean residencyEnabled) {
        return ResidencyApplyStatus.message(
                state, serviceConnected, localConfigError, localConfigRevision,
                localPolicyRevision, masterEnabled, residencyEnabled,
                Set.copyOf(REQUIRED_HOOKS));
    }

    private static void replaceConfig(
            List<DiagnosticsHookState> rows, String processId, int revision) {
        rows.removeIf(row -> processId.equals(row.processId)
                && DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId));
        rows.add(config(processId, revision));
    }

    private static DiagnosticsHookState process(String processId) {
        return process(processId,
                ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE));
    }

    private static DiagnosticsHookState process(String processId, String installStatus) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_PROCESS)
                .installStatus(installStatus)
                .bootId(CURRENT_BOOT)
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

    private static DiagnosticsHookState configError(String processId) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus("CONFIG_ERROR")
                .bootId(CURRENT_BOOT)
                .build();
    }

    private static void replaceIdentity(
            List<DiagnosticsHookState> rows,
            String processId,
            String status,
            int configRevision,
            int policyRevision) {
        rows.removeIf(row -> processId.equals(row.processId)
                && DiagnosticsTargetIds.RUNTIME_IDENTITY.equals(row.hookTargetId));
        rows.add(identity(processId, configRevision, policyRevision, status));
    }

    private static DiagnosticsHookState identity(
            String processId, int configRevision, int policyRevision) {
        return identity(processId, configRevision, policyRevision, "IDENTITY_READY");
    }

    private static DiagnosticsHookState identity(
            String processId,
            int configRevision,
            int policyRevision,
            String status) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_IDENTITY)
                .installStatus(status)
                .configRevision(configRevision)
                .policyRevision(policyRevision)
                .bootId(CURRENT_BOOT)
                .build();
    }

    private static HookStatusViewModel.State ready(List<DiagnosticsHookState> rows) {
        Source source = new Source();
        source.future.complete(new DiagnosticsQuery.HookStatePage(rows, ""));
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);
        viewModel.refresh();
        HookStatusViewModel.State state = last(states);
        viewModel.close();
        return state;
    }

    private static HookStatusViewModel viewModel(
            Source source, List<HookStatusViewModel.State> states) {
        HookStatusViewModel viewModel = new HookStatusViewModel(source, Runnable::run);
        viewModel.setListener(states::add);
        return viewModel;
    }

    private static HookStatusViewModel.State last(List<HookStatusViewModel.State> states) {
        return states.get(states.size() - 1);
    }

    private static final class Source implements HookStatusViewModel.Source {
        final CompletableFuture<DiagnosticsQuery.HookStatePage> future =
                new CompletableFuture<>();

        @Override
        public String currentBootId() {
            return CURRENT_BOOT;
        }

        @Override
        public CompletableFuture<DiagnosticsQuery.HookStatePage> queryHookStatesAsync(int limit) {
            return future;
        }
    }
}
