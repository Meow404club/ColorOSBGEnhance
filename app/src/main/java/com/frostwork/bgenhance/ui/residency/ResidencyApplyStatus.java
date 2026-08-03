package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.HookStatusViewModel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Maps current Hook and configuration state to the save-effect notice. */
final class ResidencyApplyStatus {
    static final List<String> REQUIRED_HOOKS = Collections.unmodifiableList(Arrays.asList(
            HookTargetIds.ATHENA_PERIODIC,
            HookTargetIds.OGUARD_KILL,
            HookTargetIds.OGUARD_FORCE_STOP,
            HookTargetIds.FAST_CLEAR_DISPATCH,
            HookTargetIds.TOURNAMENT,
            HookTargetIds.MEMORY_GUARD_DISPATCH,
            HookTargetIds.OSENSE_PROCESS_KILL,
            HookTargetIds.HANS_FREEZE_DISPATCH,
            HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
            HookTargetIds.HANS_WAKE_DISPATCH,
            HookTargetIds.ALARM_WAKE_DELIVERY,
            HookTargetIds.HANS_RESOURCE_DISPATCH,
            HookTargetIds.HANS_R_TO_M_TIMING,
            HookTargetIds.HANS_M_TO_F_TIMING,
            HookTargetIds.HANS_LCD_ON_EXEMPTION,
            HookTargetIds.HANS_ACTIVE_RECOVERY));

    private ResidencyApplyStatus() {}

    static int message(
            HookStatusViewModel.State state,
            boolean serviceConnected,
            int localConfigRevision) {
        if (state == null
                || state.status() == HookStatusViewModel.Status.IDLE
                || state.status() == HookStatusViewModel.Status.LOADING) {
            return R.string.status_checking_summary;
        }
        if (state.status() != HookStatusViewModel.Status.READY) {
            return R.string.effect_runtime_unavailable;
        }

        boolean systemReported = state.hasProcessReport(BridgeContract.PROCESS_SYSTEM_SERVER);
        boolean athenaReported = state.hasProcessReport(BridgeContract.PROCESS_ATHENA);
        if (!serviceConnected && !systemReported && !athenaReported) {
            return R.string.residency_apply_after_activation;
        }
        if (!systemReported || !athenaReported) {
            return R.string.residency_apply_after_restart;
        }
        if (!serviceConnected) {
            return R.string.residency_apply_sync_unavailable;
        }
        if (state.configRevision(BridgeContract.PROCESS_SYSTEM_SERVER) < localConfigRevision
                || state.configRevision(BridgeContract.PROCESS_ATHENA) < localConfigRevision) {
            return R.string.residency_apply_sync_pending;
        }
        for (String hookTargetId : REQUIRED_HOOKS) {
            if (!state.isInstalled(hookTargetId)) {
                return R.string.residency_apply_partial;
            }
        }
        return R.string.residency_apply_without_restart;
    }
}
