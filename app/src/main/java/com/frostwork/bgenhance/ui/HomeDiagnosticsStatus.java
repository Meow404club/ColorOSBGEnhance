package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps current-boot diagnostics rows to the compact status shown on the home page. */
final class HomeDiagnosticsStatus {
    enum Activation { ACTIVE, PARTIAL, INACTIVE }

    private enum IdentityStatus { READY, PENDING, FAILED, MISSING }

    private static final Set<String> PRIMARY_HOOKS = Set.of(
            HookTargetIds.ATHENA_PERIODIC,
            HookTargetIds.OGUARD_KILL,
            HookTargetIds.OGUARD_FORCE_STOP);

    final Activation activation;
    final int installedPrimaryHooks;
    final boolean systemProcessReady;
    final boolean athenaProcessReady;
    final boolean systemConfigSynced;
    final boolean athenaConfigSynced;
    final boolean systemConfigError;
    final boolean athenaConfigError;
    final boolean systemIdentityReady;
    final boolean athenaIdentityReady;
    final boolean systemIdentityError;
    final boolean athenaIdentityError;
    private final Set<String> unavailablePrimaryHooks;

    private HomeDiagnosticsStatus(
            Activation activation,
            int installedPrimaryHooks,
            boolean systemProcessReady,
            boolean athenaProcessReady,
            boolean systemConfigSynced,
            boolean athenaConfigSynced,
            boolean systemConfigError,
            boolean athenaConfigError,
            boolean systemIdentityReady,
            boolean athenaIdentityReady,
            boolean systemIdentityError,
            boolean athenaIdentityError,
            Set<String> unavailablePrimaryHooks) {
        this.activation = activation;
        this.installedPrimaryHooks = installedPrimaryHooks;
        this.systemProcessReady = systemProcessReady;
        this.athenaProcessReady = athenaProcessReady;
        this.systemConfigSynced = systemConfigSynced;
        this.athenaConfigSynced = athenaConfigSynced;
        this.systemConfigError = systemConfigError;
        this.athenaConfigError = athenaConfigError;
        this.systemIdentityReady = systemIdentityReady;
        this.athenaIdentityReady = athenaIdentityReady;
        this.systemIdentityError = systemIdentityError;
        this.athenaIdentityError = athenaIdentityError;
        this.unavailablePrimaryHooks = Collections.unmodifiableSet(
                new HashSet<>(unavailablePrimaryHooks));
    }

    static HomeDiagnosticsStatus evaluate(
            List<DiagnosticsHookState> rows,
            String currentBootId,
            int localConfigRevision,
            int localModuleVersionCode) {
        return evaluate(rows, currentBootId, localConfigRevision, -1L,
                localModuleVersionCode);
    }

    static HomeDiagnosticsStatus evaluate(
            List<DiagnosticsHookState> rows,
            String currentBootId,
            int localConfigRevision,
            long localPolicyRevision,
            int localModuleVersionCode) {
        boolean hasCurrentRows = false;
        boolean systemReady = false;
        boolean athenaReady = false;
        boolean systemSynced = false;
        boolean athenaSynced = false;
        boolean systemConfigError = false;
        boolean athenaConfigError = false;
        IdentityStatus systemIdentity = IdentityStatus.MISSING;
        IdentityStatus athenaIdentity = IdentityStatus.MISSING;
        Set<String> installedHooks = new HashSet<>();
        Set<String> unavailableHooks = new HashSet<>();

        if (rows != null && currentBootId != null && !currentBootId.isEmpty()) {
            for (DiagnosticsHookState row : rows) {
                if (row == null || !currentBootId.equals(row.bootId)) continue;
                hasCurrentRows = true;
                if (DiagnosticsTargetIds.RUNTIME_PROCESS.equals(row.hookTargetId)) {
                    boolean ready = ModuleRuntimeStatus.isCurrentVersion(
                            row.installStatus, localModuleVersionCode);
                    if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(row.processId)) {
                        systemReady |= ready;
                    } else if (BridgeContract.PROCESS_ATHENA.equals(row.processId)) {
                        athenaReady |= ready;
                    }
                } else if (DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId)) {
                    String installStatus = safe(row.installStatus);
                    boolean synced = installStatus.startsWith("CONFIG_READY")
                            && row.configRevision >= localConfigRevision;
                    boolean failed = installStatus.startsWith("CONFIG_ERROR");
                    if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(row.processId)) {
                        systemSynced |= synced;
                        systemConfigError |= failed;
                    } else if (BridgeContract.PROCESS_ATHENA.equals(row.processId)) {
                        athenaSynced |= synced;
                        athenaConfigError |= failed;
                    }
                } else if (DiagnosticsTargetIds.RUNTIME_IDENTITY.equals(row.hookTargetId)) {
                    IdentityStatus identity = identityStatus(
                            row, localConfigRevision, localPolicyRevision);
                    if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(row.processId)) {
                        systemIdentity = identity;
                    } else if (BridgeContract.PROCESS_ATHENA.equals(row.processId)) {
                        athenaIdentity = identity;
                    }
                } else if (PRIMARY_HOOKS.contains(row.hookTargetId)) {
                    String expectedProcessId = HookTargetIds.expectedProcessId(row.hookTargetId);
                    if (!expectedProcessId.isEmpty()
                            && !expectedProcessId.equals(row.processId)) {
                        continue;
                    }
                    String installStatus = safe(row.installStatus);
                    if (installStatus.startsWith("INSTALLED")) {
                        installedHooks.add(row.hookTargetId);
                    } else if (installStatus.startsWith("FAILED")
                            || installStatus.startsWith("UNSUPPORTED")) {
                        unavailableHooks.add(row.hookTargetId);
                    }
                }
            }
        }
        unavailableHooks.removeAll(installedHooks);

        Activation activation;
        if (systemReady && athenaReady
                && systemIdentity == IdentityStatus.READY
                && athenaIdentity == IdentityStatus.READY
                && installedHooks.containsAll(PRIMARY_HOOKS)) {
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
                athenaSynced,
                systemConfigError,
                athenaConfigError,
                systemIdentity == IdentityStatus.READY,
                athenaIdentity == IdentityStatus.READY,
                systemIdentity == IdentityStatus.FAILED,
                athenaIdentity == IdentityStatus.FAILED,
                unavailableHooks);
    }

    boolean hasConfigError() {
        return systemConfigError || athenaConfigError;
    }

    boolean hasIdentityError() {
        return systemIdentityError || athenaIdentityError;
    }

    boolean isPrimaryHookExplicitlyUnavailable(String hookTargetId) {
        return unavailablePrimaryHooks.contains(hookTargetId);
    }

    boolean hasExplicitlyUnavailablePrimaryHook() {
        return !unavailablePrimaryHooks.isEmpty();
    }

    int effectNotice(
            boolean serviceConnected,
            boolean localConfigSynced,
            boolean localConfigError) {
        if (hasConfigError()) return R.string.effect_config_error;
        if (localConfigError) return R.string.effect_config_error;
        if (hasIdentityError()) return R.string.effect_identity_error;
        if (hasExplicitlyUnavailablePrimaryHook()) {
            return R.string.effect_primary_hook_unavailable;
        }
        if (!systemProcessReady || !athenaProcessReady) {
            return R.string.effect_restart_required;
        }
        if (!systemIdentityReady || !athenaIdentityReady) {
            return R.string.effect_identity_pending;
        }
        if (activation != Activation.ACTIVE) {
            return R.string.effect_primary_hook_unavailable;
        }
        if (!serviceConnected) return R.string.effect_config_service_waiting;
        if (!localConfigSynced || !systemConfigSynced || !athenaConfigSynced) {
            return R.string.effect_config_pending;
        }
        return 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static IdentityStatus identityStatus(
            DiagnosticsHookState row,
            int localConfigRevision,
            long localPolicyRevision) {
        String value = safe(row.installStatus);
        if (value.startsWith("FAILED")) return IdentityStatus.FAILED;
        if (value.startsWith("IDENTITY_READY")
                && row.configRevision >= localConfigRevision
                && revisionAtLeast(row.policyRevision, localPolicyRevision)) {
            return IdentityStatus.READY;
        }
        return IdentityStatus.PENDING;
    }

    private static boolean revisionAtLeast(int reported, long local) {
        return local < 0L || reported >= Math.min(local, Integer.MAX_VALUE);
    }
}
