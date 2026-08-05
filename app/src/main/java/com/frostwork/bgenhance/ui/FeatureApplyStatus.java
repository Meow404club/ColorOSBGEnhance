package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.R;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Maps current Hook and configuration state to a warning shown on feature pages. */
final class FeatureApplyStatus {
    enum Tone { WARNING, ERROR }

    private FeatureApplyStatus() {}

    static int message(
            HookStatusViewModel.State state,
            boolean serviceConnected,
            boolean localConfigError,
            int localConfigRevision,
            List<FeatureCatalog.FeatureSpec> specs) {
        if (state == null
                || state.status() == HookStatusViewModel.Status.IDLE
                || state.status() == HookStatusViewModel.Status.LOADING) {
            return 0;
        }
        if (specs == null || specs.isEmpty()) return 0;
        if (state.status() != HookStatusViewModel.Status.READY) {
            return R.string.effect_runtime_unavailable;
        }
        if (localConfigError
                || state.hasConfigError(BridgeContract.PROCESS_SYSTEM_SERVER)
                || state.hasConfigError(BridgeContract.PROCESS_ATHENA)) {
            return R.string.effect_config_error;
        }

        Set<String> targets = new LinkedHashSet<>();
        Set<String> processes = new LinkedHashSet<>();
        if (specs != null) {
            for (FeatureCatalog.FeatureSpec spec : specs) {
                if (spec == null || spec.hookTargetId == null
                        || spec.hookTargetId.isEmpty()) continue;
                targets.add(spec.hookTargetId);
                String processId = HookTargetIds.expectedProcessId(spec.hookTargetId);
                if (!processId.isEmpty()) processes.add(processId);
            }
        }
        for (String processId : processes) {
            if (!state.hasCurrentProcess(processId, BuildConfig.VERSION_CODE)) {
                return R.string.effect_restart_required;
            }
        }
        for (String hookTargetId : targets) {
            if (!state.isInstalled(hookTargetId)) {
                return R.string.effect_feature_hook_unavailable;
            }
        }
        if (!serviceConnected) return R.string.effect_config_service_waiting;
        for (String processId : processes) {
            if (state.configRevision(processId) < localConfigRevision) {
                return R.string.effect_config_pending;
            }
        }
        return 0;
    }

    static Tone tone(int messageRes) {
        return messageRes == R.string.effect_config_error
                || messageRes == R.string.effect_runtime_unavailable
                ? Tone.ERROR : Tone.WARNING;
    }
}
