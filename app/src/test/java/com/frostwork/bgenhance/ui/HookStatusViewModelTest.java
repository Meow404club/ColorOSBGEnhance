package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class HookStatusViewModelTest {
    private static final String CURRENT_BOOT = "current-boot";

    @Test
    public void acceptsOnlyRowsFromCurrentBoot() {
        Source source = new Source();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);

        viewModel.refresh();
        source.future.complete(page(
                hook("old-boot", HookTargetIds.OGUARD_KILL, "FAILED"),
                hook(CURRENT_BOOT, HookTargetIds.OGUARD_FORCE_STOP, "UNSUPPORTED"),
                hook(CURRENT_BOOT, HookTargetIds.ATHENA_PERIODIC, "FAILED"),
                config(CURRENT_BOOT, BridgeContract.PROCESS_SYSTEM_SERVER, 7),
                configError(CURRENT_BOOT, BridgeContract.PROCESS_ATHENA),
                config("old-boot", BridgeContract.PROCESS_SYSTEM_SERVER, 99)));

        HookStatusViewModel.State state = last(states);
        assertEquals(HookStatusViewModel.Status.READY, state.status());
        assertEquals(CURRENT_BOOT, state.bootId());
        assertEquals(4, state.rows().size());
        assertEquals(HookStatusViewModel.Installation.UNKNOWN,
                state.installation(HookTargetIds.OGUARD_KILL));
        assertFalse(state.isExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));
        assertEquals(HookStatusViewModel.Installation.UNSUPPORTED,
                state.installation(HookTargetIds.OGUARD_FORCE_STOP));
        assertEquals(HookStatusViewModel.Installation.FAILED,
                state.installation(HookTargetIds.ATHENA_PERIODIC));
        assertTrue(state.isExplicitlyUnavailable(HookTargetIds.ATHENA_PERIODIC));
        assertEquals(7, state.configRevision(BridgeContract.PROCESS_SYSTEM_SERVER));
        assertFalse(state.hasConfigError(BridgeContract.PROCESS_SYSTEM_SERVER));
        assertTrue(state.hasConfigError(BridgeContract.PROCESS_ATHENA));
    }

    @Test
    public void databaseFailureKeepsHookAvailabilityUnknown() {
        Source source = new Source();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);

        viewModel.refresh();
        assertEquals(HookStatusViewModel.Status.LOADING, last(states).status());
        assertFalse(last(states).isExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));
        source.future.complete(DiagnosticsQuery.HookStatePage.empty("database_unavailable"));

        HookStatusViewModel.State state = last(states);
        assertEquals(HookStatusViewModel.Status.ERROR, state.status());
        assertEquals("database_unavailable", state.errorCode());
        assertEquals(HookStatusViewModel.Installation.UNKNOWN,
                state.installation(HookTargetIds.OGUARD_KILL));
        assertFalse(state.isExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));
    }

    @Test
    public void refreshKeepsLastReadyCapabilitiesWhileLoadingAndAfterError() {
        SwitchingSource source = new SwitchingSource();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = new HookStatusViewModel(source, Runnable::run);
        viewModel.setListener(states::add);

        CompletableFuture<DiagnosticsQuery.HookStatePage> first = source.nextFuture();
        viewModel.refresh();
        first.complete(page(hook(
                CURRENT_BOOT, HookTargetIds.OGUARD_KILL, "UNSUPPORTED")));
        assertTrue(last(states).isExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));

        CompletableFuture<DiagnosticsQuery.HookStatePage> second = source.nextFuture();
        viewModel.refresh();
        assertEquals(HookStatusViewModel.Status.LOADING, last(states).status());
        assertTrue(last(states).isExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));
        second.complete(DiagnosticsQuery.HookStatePage.empty("database_unavailable"));

        assertEquals(HookStatusViewModel.Status.ERROR, last(states).status());
        assertTrue(last(states).isExplicitlyUnavailable(HookTargetIds.OGUARD_KILL));
    }

    @Test
    public void hookStatusFromTheWrongProcessCannotMaskExpectedProcessFailure() {
        Source source = new Source();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);

        viewModel.refresh();
        source.future.complete(page(
                hook(CURRENT_BOOT, HookTargetIds.ATHENA_PERIODIC, "FAILED"),
                DiagnosticsHookState.builder()
                        .processId(BridgeContract.PROCESS_SYSTEM_SERVER)
                        .hookTargetId(HookTargetIds.ATHENA_PERIODIC)
                        .installStatus("INSTALLED")
                        .bootId(CURRENT_BOOT)
                        .build()));

        assertEquals(HookStatusViewModel.Installation.FAILED,
                last(states).installation(HookTargetIds.ATHENA_PERIODIC));
    }

    @Test
    public void lateOlderResultCannotReplaceNewerRefresh() {
        SwitchingSource source = new SwitchingSource();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = new HookStatusViewModel(source, Runnable::run);
        viewModel.setListener(states::add);

        CompletableFuture<DiagnosticsQuery.HookStatePage> older = source.nextFuture();
        viewModel.refresh();
        CompletableFuture<DiagnosticsQuery.HookStatePage> newer = source.nextFuture();
        viewModel.refresh();
        newer.complete(page(hook(
                CURRENT_BOOT, HookTargetIds.OGUARD_KILL, "INSTALLED")));
        older.complete(page(hook(
                CURRENT_BOOT, HookTargetIds.OGUARD_KILL, "FAILED")));

        HookStatusViewModel.State state = last(states);
        assertEquals(HookStatusViewModel.Status.READY, state.status());
        assertEquals(HookStatusViewModel.Installation.INSTALLED,
                state.installation(HookTargetIds.OGUARD_KILL));
    }

    @Test
    public void bootChangeBeforeCompletionRejectsResult() {
        Source source = new Source();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = viewModel(source, states);

        viewModel.refresh();
        source.bootId = "next-boot";
        source.future.complete(page(hook(
                CURRENT_BOOT, HookTargetIds.OGUARD_KILL, "INSTALLED")));

        assertEquals(HookStatusViewModel.Status.ERROR, last(states).status());
        assertEquals("boot_changed", last(states).errorCode());
    }

    @Test
    public void closeDropsPendingAndQueuedCallbacks() {
        Source source = new Source();
        QueuedExecutor executor = new QueuedExecutor();
        List<HookStatusViewModel.State> states = new ArrayList<>();
        HookStatusViewModel viewModel = new HookStatusViewModel(source, executor);
        viewModel.setListener(states::add);
        viewModel.refresh();
        int queuedBeforeClose = executor.tasks.size();

        viewModel.close();
        source.future.complete(page(hook(
                CURRENT_BOOT, HookTargetIds.OGUARD_KILL, "INSTALLED")));
        executor.runAll();

        assertTrue(viewModel.isClosed());
        assertTrue(queuedBeforeClose > 0);
        assertTrue(states.isEmpty());
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

    private static DiagnosticsQuery.HookStatePage page(DiagnosticsHookState... rows) {
        return new DiagnosticsQuery.HookStatePage(Arrays.asList(rows), "");
    }

    private static DiagnosticsHookState hook(
            String bootId, String hookTargetId, String status) {
        return DiagnosticsHookState.builder()
                .processId(HookTargetIds.expectedProcessId(hookTargetId))
                .hookTargetId(hookTargetId)
                .installStatus(status)
                .bootId(bootId)
                .build();
    }

    private static DiagnosticsHookState config(
            String bootId, String processId, int revision) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus("CONFIG_READY")
                .configRevision(revision)
                .bootId(bootId)
                .build();
    }

    private static DiagnosticsHookState configError(String bootId, String processId) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus("CONFIG_ERROR")
                .bootId(bootId)
                .build();
    }

    private static class Source implements HookStatusViewModel.Source {
        String bootId = CURRENT_BOOT;
        CompletableFuture<DiagnosticsQuery.HookStatePage> future = new CompletableFuture<>();

        @Override
        public String currentBootId() {
            return bootId;
        }

        @Override
        public CompletableFuture<DiagnosticsQuery.HookStatePage> queryHookStatesAsync(int limit) {
            assertEquals(256, limit);
            return future;
        }
    }

    private static final class SwitchingSource implements HookStatusViewModel.Source {
        private final List<CompletableFuture<DiagnosticsQuery.HookStatePage>> futures =
                new ArrayList<>();
        private int index;

        CompletableFuture<DiagnosticsQuery.HookStatePage> nextFuture() {
            CompletableFuture<DiagnosticsQuery.HookStatePage> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }

        @Override
        public String currentBootId() {
            return CURRENT_BOOT;
        }

        @Override
        public CompletableFuture<DiagnosticsQuery.HookStatePage> queryHookStatesAsync(int limit) {
            return futures.get(index++);
        }
    }

    private static final class QueuedExecutor implements java.util.concurrent.Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : pending) task.run();
        }
    }
}
