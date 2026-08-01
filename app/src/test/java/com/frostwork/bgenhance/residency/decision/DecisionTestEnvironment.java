package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

final class DecisionTestEnvironment {
    static final String MANAGED_PACKAGE = "com.example.decision.managed";
    static final String SECOND_PACKAGE = "com.example.decision.second";
    static final String UNMANAGED_PACKAGE = "com.example.decision.unmanaged";
    static final int MANAGED_UID = 10_321;
    static final int MANAGED_PID = 20_321;
    static final long CONFIG_REVISION = 31L;
    static final long POLICY_REVISION = 17L;

    private DecisionTestEnvironment() {}

    static ResidencyTarget managedTarget() {
        return new ResidencyTarget(MANAGED_PACKAGE, MANAGED_UID, MANAGED_PID);
    }

    static AppResidencyPolicy policy(
            String packageName,
            boolean enabled,
            ResidencyMode mode,
            KillPolicy kill,
            FreezePolicy freeze,
            WakePolicy wake,
            ResourcePolicy resource,
            RefreezePolicy refreeze) {
        return new AppResidencyPolicy(
                packageName, enabled, mode, kill, freeze, wake, resource, refreeze);
    }

    static ResidencyDecisionEngine engine(AppResidencyPolicy policy) {
        return engine(policy, true, true, false, POLICY_REVISION);
    }

    static ResidencyDecisionEngine engine(
            AppResidencyPolicy policy,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            long policyRevision) {
        Map<String, AppResidencyPolicy> policies = new LinkedHashMap<>();
        policies.put(policy.packageName(), policy);
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                CONFIG_REVISION,
                masterEnabled,
                residencyEnabled,
                dryRun,
                policyRevision,
                policies);
        Map<String, Integer> packageUids = new LinkedHashMap<>();
        packageUids.put(MANAGED_PACKAGE, MANAGED_UID);
        packageUids.put(UNMANAGED_PACKAGE, 10_322);
        return new ResidencyDecisionEngine(
                snapshot, ResidencyIdentityIndex.build(snapshot, packageUids));
    }
}
