package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.BuildConfig;
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
            HookTargetIds.HANS_CORE_EXEMPTION,
            HookTargetIds.HANS_LCD_ON_EXEMPTION,
            HookTargetIds.HANS_ACTIVE_RECOVERY));

    enum Tone { INFO, WARNING, ERROR, SUCCESS }

    private ResidencyApplyStatus() {}

    static int message(
            HookStatusViewModel.State state,
            boolean serviceConnected,
            boolean localConfigError,
            int localConfigRevision,
            boolean masterEnabled,
            boolean residencyEnabled) {
        return message(state, serviceConnected, localConfigError, localConfigRevision,
                -1L, masterEnabled, residencyEnabled);
    }

    static int message(
            HookStatusViewModel.State state,
            boolean serviceConnected,
            boolean localConfigError,
            int localConfigRevision,
            long localPolicyRevision,
            boolean masterEnabled,
            boolean residencyEnabled) {
        if (state == null
                || state.status() == HookStatusViewModel.Status.IDLE
                || state.status() == HookStatusViewModel.Status.LOADING) {
            return R.string.status_checking_summary;
        }
        if (state.status() != HookStatusViewModel.Status.READY) {
            return R.string.effect_runtime_unavailable;
        }
        if (state.hasConfigError(BridgeContract.PROCESS_SYSTEM_SERVER)
                || state.hasConfigError(BridgeContract.PROCESS_ATHENA)
                || localConfigError) {
            return R.string.residency_apply_config_error;
        }

        boolean anySystemReport = state.hasProcessReport(
                BridgeContract.PROCESS_SYSTEM_SERVER);
        boolean anyAthenaReport = state.hasProcessReport(BridgeContract.PROCESS_ATHENA);
        boolean systemReported = state.hasCurrentProcess(
                BridgeContract.PROCESS_SYSTEM_SERVER, BuildConfig.VERSION_CODE);
        boolean athenaReported = state.hasCurrentProcess(
                BridgeContract.PROCESS_ATHENA, BuildConfig.VERSION_CODE);
        if (!serviceConnected && !anySystemReport && !anyAthenaReport) {
            return R.string.residency_apply_after_activation;
        }
        if (!systemReported || !athenaReported) {
            return R.string.residency_apply_after_restart;
        }
        HookStatusViewModel.Identity systemIdentity = state.identity(
                BridgeContract.PROCESS_SYSTEM_SERVER,
                localConfigRevision,
                localPolicyRevision);
        HookStatusViewModel.Identity athenaIdentity = state.identity(
                BridgeContract.PROCESS_ATHENA,
                localConfigRevision,
                localPolicyRevision);
        if (systemIdentity == HookStatusViewModel.Identity.FAILED
                || athenaIdentity == HookStatusViewModel.Identity.FAILED) {
            return R.string.residency_apply_identity_error;
        }
        if (!serviceConnected) {
            return R.string.residency_apply_sync_unavailable;
        }
        for (String hookTargetId : REQUIRED_HOOKS) {
            if (!state.isInstalled(hookTargetId)) {
                return R.string.residency_apply_partial;
            }
        }
        if (state.configRevision(BridgeContract.PROCESS_SYSTEM_SERVER) < localConfigRevision
                || state.configRevision(BridgeContract.PROCESS_ATHENA) < localConfigRevision) {
            return R.string.residency_apply_sync_pending;
        }
        if (systemIdentity != HookStatusViewModel.Identity.READY
                || athenaIdentity != HookStatusViewModel.Identity.READY) {
            return R.string.residency_apply_identity_pending;
        }
        if (!masterEnabled) return R.string.residency_apply_master_disabled;
        if (!residencyEnabled) return R.string.residency_apply_disabled;
        return R.string.residency_apply_without_restart;
    }

    static Tone tone(int messageRes) {
        if (messageRes == R.string.residency_apply_without_restart) return Tone.SUCCESS;
        if (messageRes == R.string.residency_apply_config_error
                || messageRes == R.string.residency_apply_identity_error
                || messageRes == R.string.effect_runtime_unavailable) {
            return Tone.ERROR;
        }
        if (messageRes == R.string.status_checking_summary
                || messageRes == R.string.residency_apply_master_disabled
                || messageRes == R.string.residency_apply_disabled) {
            return Tone.INFO;
        }
        return Tone.WARNING;
    }
}
