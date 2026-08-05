package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Physical Hook targets required by the effective, enabled residency policies. */
public final class ResidencyHookRequirements {
    private ResidencyHookRequirements() {}

    public static Set<String> forDocument(ResidencyPolicyDocument document) {
        if (document == null || !document.enabled()) return Collections.emptySet();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (AppResidencyPolicy policy : document.rules()) {
            addPolicy(targets, policy);
        }
        if (targets.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(targets);
    }

    static Set<String> forPolicy(AppResidencyPolicy policy) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        addPolicy(targets, policy);
        if (targets.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(targets);
    }

    private static void addPolicy(Set<String> targets, AppResidencyPolicy policy) {
        if (policy == null || !policy.enabled() || policy.mode() == ResidencyMode.SYSTEM) return;
        addKillTargets(targets, policy.killPolicy());
        addFreezeTargets(targets, policy.freezePolicy());
        addWakeTargets(targets, policy.freezePolicy(), policy.wakePolicy());
        addResourceTargets(targets, policy.resourcePolicy());
        if (policy.freezePolicy().fullExemption()
                || policy.refreezePolicy().mode() != RefreezeMode.SYSTEM) {
            targets.add(HookTargetIds.HANS_M_TO_F_TIMING);
        }
    }

    private static void addKillTargets(Set<String> targets, KillPolicy policy) {
        if (policy.protectOguardKill()) targets.add(HookTargetIds.OGUARD_KILL);
        if (policy.protectOguardForceStop()) targets.add(HookTargetIds.OGUARD_FORCE_STOP);
        if (policy.protectAthenaPeriodic()) targets.add(HookTargetIds.ATHENA_PERIODIC);
        if (policy.protectFastClear()) targets.add(HookTargetIds.FAST_CLEAR_DISPATCH);
        if (policy.protectTournament()) targets.add(HookTargetIds.TOURNAMENT);
        if (policy.protectMemoryGuard()) targets.add(HookTargetIds.MEMORY_GUARD_DISPATCH);
        if (policy.protectNirvana()) targets.add(HookTargetIds.OSENSE_GROUP_CLEAN);
        if (policy.protectOsensePerProcess()) {
            targets.add(HookTargetIds.OSENSE_PROCESS_KILL);
        }
    }

    private static void addFreezeTargets(Set<String> targets, FreezePolicy policy) {
        boolean recoveryRequired = false;
        if (policy.fullExemption()
                || !policy.allowNormalFreeze()
                || !policy.allowSuperFreeze()
                || !policy.allowPreloadFreeze()) {
            targets.add(HookTargetIds.HANS_FREEZE_DISPATCH);
            recoveryRequired = true;
        }
        if (policy.fullExemption() || !policy.allowFastFreeze()) {
            targets.add(HookTargetIds.HANS_FAST_FREEZE_DISPATCH);
            recoveryRequired = true;
        }
        if (!policy.fullExemption() && policy.customTiming()) {
            targets.add(HookTargetIds.HANS_R_TO_M_TIMING);
            targets.add(HookTargetIds.HANS_M_TO_F_TIMING);
        }
        if (!policy.fullExemption() && policy.coreExemption()) {
            targets.add(HookTargetIds.HANS_CORE_EXEMPTION);
            recoveryRequired = true;
        }
        if (policy.fullExemption() || policy.lcdOnExemption()) {
            targets.add(HookTargetIds.HANS_LCD_ON_EXEMPTION);
            recoveryRequired = true;
        }
        if (recoveryRequired) targets.add(HookTargetIds.HANS_ACTIVE_RECOVERY);
    }

    private static void addWakeTargets(
            Set<String> targets, FreezePolicy freezePolicy, WakePolicy policy) {
        if (freezePolicy.fullExemption()) return;
        if (policy.packet().mode() != WakeMode.ALLOW || blocksComponentWake(policy)) {
            targets.add(HookTargetIds.HANS_WAKE_DISPATCH);
        }
        if (policy.alarm().mode() != WakeMode.ALLOW) {
            targets.add(HookTargetIds.ALARM_WAKE_DELIVERY);
        }
    }

    private static boolean blocksComponentWake(WakePolicy policy) {
        for (WakeSource source : policy.blockedSources()) {
            if (source != WakeSource.PACKET
                    && source != WakeSource.ALARM
                    && !source.isAlwaysAllowed()) {
                return true;
            }
        }
        return false;
    }

    private static void addResourceTargets(Set<String> targets, ResourcePolicy policy) {
        if (!policy.keepNetwork() && policy.keptResources().isEmpty()) return;
        targets.add(HookTargetIds.HANS_RESOURCE_DISPATCH);
        targets.add(HookTargetIds.HANS_ACTIVE_RECOVERY);
    }
}
