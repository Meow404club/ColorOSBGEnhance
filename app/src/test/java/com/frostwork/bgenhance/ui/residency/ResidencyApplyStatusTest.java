package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.ui.HookStatusViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class ResidencyApplyStatusTest {
    private static final String CURRENT_BOOT = "current-boot";

    @Test
    public void loadingAndDatabaseFailureNeverPromiseImmediateApplication() {
        Source source = new Source();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);

        viewModel.refresh();
        int loading = ResidencyApplyStatus.message(last(states), true, 7);
        assertEquals(R.string.status_checking_summary, loading);
        assertNotEquals(R.string.residency_apply_without_restart, loading);

        source.future.complete(DiagnosticsQuery.HookStatePage.empty("database_unavailable"));
        int failed = ResidencyApplyStatus.message(last(states), true, 7);
        assertEquals(R.string.effect_runtime_unavailable, failed);
        assertNotEquals(R.string.residency_apply_without_restart, failed);
    }

    @Test
    public void bothProcessesMustReportCurrentConfigRevision() {
        List<DiagnosticsHookState> rows = completeRows(7);
        replaceConfig(rows, BridgeContract.PROCESS_ATHENA, 6);

        HookStatusViewModel.State state = ready(rows);
        assertEquals(R.string.residency_apply_sync_pending,
                ResidencyApplyStatus.message(state, true, 7));

        replaceConfig(rows, BridgeContract.PROCESS_ATHENA, 7);
        state = ready(rows);
        assertEquals(R.string.residency_apply_without_restart,
                ResidencyApplyStatus.message(state, true, 7));
    }

    @Test
    public void everyRequiredHookMustBeInstalled() {
        List<DiagnosticsHookState> rows = completeRows(7);
        String missing = ResidencyApplyStatus.REQUIRED_HOOKS.iterator().next();
        rows.removeIf(row -> missing.equals(row.hookTargetId));

        assertEquals(R.string.residency_apply_partial,
                ResidencyApplyStatus.message(ready(rows), true, 7));
    }

    @Test
    public void missingCurrentBootProcessRequiresActivationOrRestart() {
        HookStatusViewModel.State empty = ready(new ArrayList<>());
        assertEquals(R.string.residency_apply_after_activation,
                ResidencyApplyStatus.message(empty, false, 7));

        List<DiagnosticsHookState> oneProcess = new ArrayList<>();
        oneProcess.add(process(BridgeContract.PROCESS_SYSTEM_SERVER));
        assertEquals(R.string.residency_apply_after_restart,
                ResidencyApplyStatus.message(ready(oneProcess), true, 7));
    }

    private static List<DiagnosticsHookState> completeRows(int revision) {
        ArrayList<DiagnosticsHookState> rows = new ArrayList<>();
        rows.add(process(BridgeContract.PROCESS_SYSTEM_SERVER));
        rows.add(process(BridgeContract.PROCESS_ATHENA));
        rows.add(config(BridgeContract.PROCESS_SYSTEM_SERVER, revision));
        rows.add(config(BridgeContract.PROCESS_ATHENA, revision));
        for (String hookTargetId : ResidencyApplyStatus.REQUIRED_HOOKS) {
            rows.add(DiagnosticsHookState.builder()
                    .processId(BridgeContract.PROCESS_SYSTEM_SERVER)
                    .hookTargetId(hookTargetId)
                    .installStatus("INSTALLED")
                    .bootId(CURRENT_BOOT)
                    .build());
        }
        return rows;
    }

    private static void replaceConfig(
            List<DiagnosticsHookState> rows, String processId, int revision) {
        rows.removeIf(row -> processId.equals(row.processId)
                && DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId));
        rows.add(config(processId, revision));
    }

    private static DiagnosticsHookState process(String processId) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_PROCESS)
                .installStatus("FEATURES_INSTALLED")
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
