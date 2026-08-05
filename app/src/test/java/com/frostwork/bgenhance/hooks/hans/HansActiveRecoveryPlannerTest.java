package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.DesiredState;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.Capabilities;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.RecoveryIntent;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.Revalidation;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.Update;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.PackageUidSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class HansActiveRecoveryPlannerTest {
    private static final String PACKAGE = "com.example.recovery.target";
    private static final String SIBLING = "com.example.recovery.sibling";
    private static final int UID = 10_921;

    @Test
    public void initialEffectivePhysicalPolicyRequestsOneRecovery() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();

        Update update = planner.update(ready(activePolicy(PACKAGE)), 512);

        assertEquals(Update.Status.STABLE, update.status());
        assertEquals(1, update.intents().size());
        DesiredState desired = update.intents().get(0).desiredState();
        assertEquals(UID, desired.uid());
        assertEquals(Set.of(PACKAGE), desired.expectedPackages());
        assertEquals(6, Long.bitCount(desired.signature()));
    }

    @Test
    public void killWakeRefreezeAndPureTimingChangesDoNotRequestRecovery() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        AppResidencyPolicy first = nonPhysicalPolicy(false, 2_000L);
        AppResidencyPolicy second = nonPhysicalPolicy(true, 90_000L);

        assertTrue(planner.update(ready(first), 512).intents().isEmpty());
        assertTrue(planner.update(ready(second), 512).intents().isEmpty());
    }

    @Test
    public void freezeAndResourceBehaviorChangesUseDifferentSignatures() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        RecoveryIntent active = only(planner.update(ready(activePolicy(PACKAGE)), 512));
        AppResidencyPolicy resource = resourcePolicy(PACKAGE);

        RecoveryIntent changed = only(planner.update(ready(resource), 512));

        assertNotEquals(
                active.desiredState().signature(), changed.desiredState().signature());
        assertEquals(11, Long.bitCount(changed.desiredState().signature()));
    }

    @Test
    public void masterDryRunAndRuleDeletionRestoreSystemWithOldUidOwnership() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        DesiredState enabled = only(planner.update(
                ready(true, true, false, Map.of(PACKAGE, activePolicy(PACKAGE))),
                512)).desiredState();

        DesiredState dryRun = only(planner.update(
                ready(true, true, true, Map.of(PACKAGE, activePolicy(PACKAGE))),
                512)).desiredState();
        assertEquals(0L, dryRun.signature());
        assertEquals(enabled.expectedPackages(), dryRun.expectedPackages());

        only(planner.update(
                ready(true, true, false, Map.of(PACKAGE, activePolicy(PACKAGE))),
                512));
        DesiredState masterOff = only(planner.update(
                ready(false, true, false, Map.of(PACKAGE, activePolicy(PACKAGE))),
                512)).desiredState();
        assertEquals(0L, masterOff.signature());

        only(planner.update(
                ready(true, true, false, Map.of(PACKAGE, activePolicy(PACKAGE))),
                512));
        DesiredState deleted = only(planner.update(
                ready(true, true, false, Collections.emptyMap()), 512)).desiredState();
        assertEquals(0L, deleted.signature());
        assertEquals(Set.of(PACKAGE), deleted.expectedPackages());
    }

    @Test
    public void unavailableConfigurationRestoresOldNonzeroStateButPendingWaits() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        only(planner.update(ready(activePolicy(PACKAGE)), 512));

        Update pending = planner.update(pending(activePolicy(PACKAGE)), 512);
        assertEquals(Update.Status.WAITING, pending.status());
        assertTrue(pending.intents().isEmpty());

        ResidencyRuntimeController unavailable = new ResidencyRuntimeController(Runnable::run);
        unavailable.updateConfig(RuntimeConfigSnapshot.unavailable(9L));
        Update recovery = planner.update(unavailable.state(), 512);
        assertEquals(Update.Status.RECOVERY_TO_SYSTEM, recovery.status());
        assertEquals(0L, only(recovery).desiredState().signature());
    }

    @Test
    public void sameEffectiveSharedUidPolicyProducesOneFullMembershipRequest() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        Map<String, AppResidencyPolicy> policies = new LinkedHashMap<>();
        policies.put(PACKAGE, activePolicy(PACKAGE));
        policies.put(SIBLING, activePolicy(SIBLING));

        Update update = planner.update(ready(
                true,
                true,
                false,
                policies,
                Map.of(PACKAGE, UID, SIBLING, UID),
                Map.of(UID, Set.of(PACKAGE, SIBLING))), 512);

        assertEquals(1, update.intents().size());
        assertEquals(Set.of(PACKAGE, SIBLING),
                update.intents().get(0).desiredState().expectedPackages());
    }

    @Test
    public void changedConfigRevisionWithSamePhysicalSignatureDoesNothing() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        ResidencyRuntimeController controller = controllerFor(Map.of(PACKAGE, UID));
        controller.updateConfig(snapshot(
                1L, 1L, true, true, false, Map.of(PACKAGE, activePolicy(PACKAGE))));
        only(planner.update(controller.state(), 512));

        controller.updateConfig(snapshot(
                2L, 1L, true, true, false, Map.of(PACKAGE, activePolicy(PACKAGE))));

        assertTrue(planner.update(controller.state(), 512).intents().isEmpty());
    }

    @Test
    public void revalidationUsesFallbackMembershipAfterRuleRemoval() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        DesiredState active = only(planner.update(ready(activePolicy(PACKAGE)), 512))
                .desiredState();
        ResidencyRuntimeState empty = ready(
                true, true, false, Collections.emptyMap());

        Revalidation result = planner.revalidate(
                empty, UID, active.expectedPackages());

        assertEquals(Revalidation.Status.STABLE, result.status());
        assertEquals(0L, result.desiredStateOrNull().signature());
        assertEquals(Set.of(PACKAGE), result.desiredStateOrNull().expectedPackages());
    }

    @Test
    public void transitionBudgetDefersRemainingUidsWithoutLosingThem() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        String second = "com.example.recovery.second";
        int secondUid = UID + 1;
        Map<String, AppResidencyPolicy> policies = Map.of(
                PACKAGE, activePolicy(PACKAGE),
                second, activePolicy(second));
        ResidencyRuntimeState state = ready(
                true,
                true,
                false,
                policies,
                Map.of(PACKAGE, UID, second, secondUid),
                Map.of(UID, Set.of(PACKAGE), secondUid, Set.of(second)));

        Update first = planner.update(state, 1);
        Update secondWave = planner.update(state, 1);

        assertEquals(1, first.intents().size());
        assertTrue(first.moreWork());
        assertEquals(1, secondWave.intents().size());
        assertFalse(secondWave.moreWork());
        assertNotEquals(first.intents().get(0).desiredState().uid(),
                secondWave.intents().get(0).desiredState().uid());
    }

    @Test
    public void forcedSystemRecoverySurvivesZeroBaselineTrimAt512Boundary() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        Map<String, AppResidencyPolicy> initialPolicies = new LinkedHashMap<>();
        Map<String, Integer> initialPackageUids = new LinkedHashMap<>();
        Map<Integer, Set<String>> initialUidPackages = new LinkedHashMap<>();
        for (int index = 0; index < 512; index++) {
            String packageName = boundaryPackage(index);
            int uid = UID + index;
            initialPolicies.put(packageName, activePolicy(packageName));
            initialPackageUids.put(packageName, uid);
            initialUidPackages.put(uid, Set.of(packageName));
        }
        Update initial = planner.update(ready(
                true,
                true,
                false,
                initialPolicies,
                initialPackageUids,
                initialUidPackages), 512);
        assertEquals(512, initial.intents().size());

        Map<String, AppResidencyPolicy> replacementPolicies =
                new LinkedHashMap<>(initialPolicies);
        Map<String, Integer> replacementPackageUids =
                new LinkedHashMap<>(initialPackageUids);
        Map<Integer, Set<String>> replacementUidPackages =
                new LinkedHashMap<>(initialUidPackages);
        String removedPackage = boundaryPackage(0);
        replacementPolicies.remove(removedPackage);
        replacementPackageUids.remove(removedPackage);
        replacementUidPackages.remove(UID);
        String replacementPackage = boundaryPackage(512);
        int replacementUid = UID + 512;
        replacementPolicies.put(replacementPackage, activePolicy(replacementPackage));
        replacementPackageUids.put(replacementPackage, replacementUid);
        replacementUidPackages.put(replacementUid, Set.of(replacementPackage));
        ResidencyRuntimeState replacementState = ready(
                true,
                true,
                false,
                replacementPolicies,
                replacementPackageUids,
                replacementUidPackages);

        Update transition = planner.update(replacementState, 512);
        assertEquals(2, transition.intents().size());
        DesiredState restoreSystem = transition.intents().stream()
                .map(RecoveryIntent::desiredState)
                .filter(desired -> desired.uid() == UID)
                .findFirst()
                .orElseThrow();
        assertEquals(0L, restoreSystem.signature());

        assertTrue(planner.requireRecovery(
                restoreSystem.uid(),
                restoreSystem.expectedPackages(),
                restoreSystem.runtimeGeneration(),
                restoreSystem.signature()));

        Update retry = planner.update(replacementState, 512);
        DesiredState retried = only(retry).desiredState();
        assertEquals(UID, retried.uid());
        assertEquals(0L, retried.signature());
        assertEquals(Set.of(removedPackage), retried.expectedPackages());
    }

    @Test
    public void repeatedRequireRecoveryForSameUidCoalescesIntoOneIntent() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        ResidencyRuntimeState state = ready(activePolicy(PACKAGE));
        DesiredState active = only(planner.update(state, 512)).desiredState();

        for (int attempt = 0;
                attempt < HansActiveRecoveryPlanner.MAX_FORCED_RECOVERIES * 2;
                attempt++) {
            assertTrue(planner.requireRecovery(
                    active.uid(),
                    active.expectedPackages(),
                    active.runtimeGeneration() + attempt,
                    active.signature()));
        }

        Update retry = planner.update(state, 512);
        assertEquals(1, retry.intents().size());
        assertEquals(UID, retry.intents().get(0).desiredState().uid());
        assertTrue(planner.update(state, 512).intents().isEmpty());
    }

    @Test
    public void forcedRecoveryCapacityRejectsNewUidWithoutEvictingPendingEntries() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner();
        for (int index = 0;
                index < HansActiveRecoveryPlanner.MAX_FORCED_RECOVERIES;
                index++) {
            assertTrue(planner.requireRecovery(
                    UID + index,
                    Set.of(boundaryPackage(index)),
                    1L,
                    0L));
        }

        assertFalse(planner.requireRecovery(
                UID + HansActiveRecoveryPlanner.MAX_FORCED_RECOVERIES,
                Set.of(boundaryPackage(HansActiveRecoveryPlanner.MAX_FORCED_RECOVERIES)),
                1L,
                0L));

        Update update = planner.update(ready(
                true, true, false, Collections.emptyMap()), 512);
        assertEquals(HansActiveRecoveryPlanner.MAX_FORCED_RECOVERIES,
                update.intents().size());
    }

    @Test
    public void unavailableBehaviorHooksDoNotContributeToRecoverySignature() {
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner(
                Capabilities.none());

        Update update = planner.update(ready(activePolicy(PACKAGE)), 512);

        assertTrue(update.intents().isEmpty());
    }

    @Test
    public void productionLikeCapabilitiesExcludeFastAndCoreBehavior() {
        Capabilities capabilities = new Capabilities(
                EnumSet.of(FreezeSource.NORMAL, FreezeSource.SUPER, FreezeSource.PRELOAD),
                false,
                true,
                false,
                Collections.emptySet());
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner(capabilities);

        DesiredState desired = only(planner.update(
                ready(activePolicy(PACKAGE)), 512)).desiredState();

        assertEquals(4, Long.bitCount(desired.signature()));
        assertEquals(0L, desired.signature() & (1L << FreezeSource.FAST.ordinal()));
    }

    @Test
    public void resourceChangesAreIgnoredWhenResourceGateDidNotInstall() {
        Capabilities capabilities = new Capabilities(
                Collections.emptySet(), false, false, false, Collections.emptySet());
        HansActiveRecoveryPlanner planner = new HansActiveRecoveryPlanner(capabilities);

        assertTrue(planner.update(ready(resourcePolicy(PACKAGE)), 512)
                .intents().isEmpty());
    }

    private static RecoveryIntent only(Update update) {
        assertEquals(1, update.intents().size());
        return update.intents().get(0);
    }

    private static AppResidencyPolicy activePolicy(String packageName) {
        return ResidencyPresets.create(packageName, ResidencyMode.ACTIVE);
    }

    private static String boundaryPackage(int index) {
        return "com.example.recovery.boundary" + index;
    }

    private static AppResidencyPolicy resourcePolicy(String packageName) {
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                WakePolicy.allowAll(),
                new ResourcePolicy(true, EnumSet.allOf(ResourceType.class)),
                RefreezePolicy.followSystem());
    }

    private static AppResidencyPolicy nonPhysicalPolicy(
            boolean restrictiveAuxiliaryPolicies, long timingMs) {
        FreezePolicy timing = new FreezePolicy(
                true, true, true, true, true, timingMs, timingMs,
                false, false, false);
        WakePolicy wake = restrictiveAuxiliaryPolicies
                ? new WakePolicy(
                        new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                        new WakeChannelPolicy(WakeMode.THROTTLE, 60_000L),
                        EnumSet.of(WakeSource.SERVICE))
                : WakePolicy.allowAll();
        RefreezePolicy refreeze = restrictiveAuxiliaryPolicies
                ? new RefreezePolicy(
                        RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
                : RefreezePolicy.followSystem();
        return new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                restrictiveAuxiliaryPolicies ? KillPolicy.all() : KillPolicy.none(),
                timing,
                wake,
                ResourcePolicy.followSystem(),
                refreeze);
    }

    private static ResidencyRuntimeState ready(AppResidencyPolicy policy) {
        return ready(true, true, false, Map.of(policy.packageName(), policy));
    }

    private static ResidencyRuntimeState ready(
            boolean master,
            boolean residency,
            boolean dryRun,
            Map<String, AppResidencyPolicy> policies) {
        Map<String, Integer> packageUids = new LinkedHashMap<>();
        Map<Integer, Set<String>> uidPackages = new LinkedHashMap<>();
        int nextUid = UID;
        for (String packageName : policies.keySet()) {
            packageUids.put(packageName, nextUid);
            uidPackages.put(nextUid, Set.of(packageName));
            nextUid++;
        }
        return ready(master, residency, dryRun, policies, packageUids, uidPackages);
    }

    private static ResidencyRuntimeState ready(
            boolean master,
            boolean residency,
            boolean dryRun,
            Map<String, AppResidencyPolicy> policies,
            Map<String, Integer> packageUids,
            Map<Integer, Set<String>> uidPackages) {
        ResidencyRuntimeController controller = controllerFor(packageUids, uidPackages);
        controller.updateConfig(snapshot(
                1L, 1L, master, residency, dryRun, policies));
        assertTrue(controller.state().ready());
        return controller.state();
    }

    private static ResidencyRuntimeState pending(AppResidencyPolicy policy) {
        AtomicReference<Runnable> build = new AtomicReference<>();
        ResidencyRuntimeController controller = new ResidencyRuntimeController(build::set);
        controller.setPackageUidSource(source(
                Map.of(PACKAGE, UID), Map.of(UID, Set.of(PACKAGE))));
        controller.updateConfig(snapshot(
                1L, 1L, true, true, false, Map.of(PACKAGE, policy)));
        assertEquals(ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                controller.state().status());
        return controller.state();
    }

    private static ResidencyRuntimeController controllerFor(
            Map<String, Integer> packageUids) {
        Map<Integer, Set<String>> uidPackages = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : packageUids.entrySet()) {
            uidPackages.put(entry.getValue(), Set.of(entry.getKey()));
        }
        return controllerFor(packageUids, uidPackages);
    }

    private static ResidencyRuntimeController controllerFor(
            Map<String, Integer> packageUids,
            Map<Integer, Set<String>> uidPackages) {
        ResidencyRuntimeController controller = new ResidencyRuntimeController(Runnable::run);
        controller.setPackageUidSource(source(packageUids, uidPackages));
        return controller;
    }

    private static PackageUidSource source(
            Map<String, Integer> packageUids,
            Map<Integer, Set<String>> uidPackages) {
        return new PackageUidSource() {
            @Override public boolean hasCompleteUidEnumeration() { return true; }

            @Override
            public OptionalInt findUid(String packageName) {
                Integer uid = packageUids.get(packageName);
                return uid == null ? OptionalInt.empty() : OptionalInt.of(uid);
            }

            @Override
            public Set<String> findPackagesForUid(int uid) {
                return uidPackages.getOrDefault(uid, Collections.emptySet());
            }
        };
    }

    private static RuntimeConfigSnapshot snapshot(
            long configRevision,
            long policyRevision,
            boolean master,
            boolean residency,
            boolean dryRun,
            Map<String, AppResidencyPolicy> policies) {
        return RuntimeConfigSnapshot.available(
                configRevision,
                master,
                residency,
                dryRun,
                policyRevision,
                policies);
    }
}
