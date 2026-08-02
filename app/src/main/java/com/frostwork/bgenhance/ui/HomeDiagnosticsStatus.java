package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps current-boot diagnostics rows to the compact status shown on the home page. */
final class HomeDiagnosticsStatus {
    enum Activation { ACTIVE, PARTIAL, INACTIVE }

    private static final Set<String> PRIMARY_HOOKS = Set.of(
            HookTargetIds.ATHENA_PERIODIC,
            HookTargetIds.OGUARD_KILL,
            HookTargetIds.OGUARD_FORCE_STOP,
            HookTargetIds.FAST_CLEAR_DISPATCH);

    final Activation activation;
    final int installedPrimaryHooks;
    final boolean systemProcessReady;
    final boolean athenaProcessReady;
    final boolean systemConfigSynced;
    final boolean athenaConfigSynced;

    private HomeDiagnosticsStatus(
            Activation activation,
            int installedPrimaryHooks,
            boolean systemProcessReady,
            boolean athenaProcessReady,
            boolean systemConfigSynced,
            boolean athenaConfigSynced) {
        this.activation = activation;
        this.installedPrimaryHooks = installedPrimaryHooks;
        this.systemProcessReady = systemProcessReady;
        this.athenaProcessReady = athenaProcessReady;
        this.systemConfigSynced = systemConfigSynced;
        this.athenaConfigSynced = athenaConfigSynced;
    }

    static HomeDiagnosticsStatus evaluate(
            List<DiagnosticsHookState> rows,
            String currentBootId,
            int localConfigRevision) {
        boolean hasCurrentRows = false;
        boolean systemReady = false;
        boolean athenaReady = false;
        boolean systemSynced = false;
        boolean athenaSynced = false;
        Set<String> installedHooks = new HashSet<>();

        if (rows != null && currentBootId != null && !currentBootId.isEmpty()) {
            for (DiagnosticsHookState row : rows) {
                if (row == null || !currentBootId.equals(row.bootId)) continue;
                hasCurrentRows = true;
                if (DiagnosticsTargetIds.RUNTIME_PROCESS.equals(row.hookTargetId)) {
                    boolean ready = "FEATURES_INSTALLED".equals(row.installStatus);
                    if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(row.processId)) {
                        systemReady |= ready;
                    } else if (BridgeContract.PROCESS_ATHENA.equals(row.processId)) {
                        athenaReady |= ready;
                    }
                } else if (DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId)) {
                    boolean synced = "CONFIG_READY".equals(row.installStatus)
                            && row.configRevision >= localConfigRevision;
                    if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(row.processId)) {
                        systemSynced |= synced;
                    } else if (BridgeContract.PROCESS_ATHENA.equals(row.processId)) {
                        athenaSynced |= synced;
                    }
                } else if (PRIMARY_HOOKS.contains(row.hookTargetId)
                        && row.installStatus.startsWith("INSTALLED")) {
                    installedHooks.add(row.hookTargetId);
                }
            }
        }

        Activation activation;
        if (systemReady && athenaReady && installedHooks.size() >= 3) {
            activation = Activation.ACTIVE;
        } else if (hasCurrentRows) {
            activation = Activation.PARTIAL;
        } else {
            activation = Activation.INACTIVE;
        }
        return new HomeDiagnosticsStatus(
                activation,
                installedHooks.size(),
                systemReady,
                athenaReady,
                systemSynced,
                athenaSynced);
    }
}
