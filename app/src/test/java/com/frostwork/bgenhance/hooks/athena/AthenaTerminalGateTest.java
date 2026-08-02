package com.frostwork.bgenhance.hooks.athena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import com.oplus.app.athena.ClearRecord;
import com.oplus.app.athena.KeepRecord;
import com.oplus.app.athena.ProcDetailInfo;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AthenaTerminalGateTest {
    private static final String PROTECTED = "example.protected";
    private static final String ALLOWED = "example.allowed";
    private static final String SHARED = "example.shared";
    private static final int UID = 10_321;
    private static final int ALLOWED_UID = 10_322;
    private static final int PID = 4321;
    private static final Object ORIGINAL = new Object();

    @Test
    public void allSixUniqueHooksMustInstallBeforeAnyCallbackActivates() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        FakeInstaller successfulInstaller = new FakeInstaller(-1, 1);
        RecordingDeoptimizer deoptimizer = new RecordingDeoptimizer(-1, false);
        AthenaTerminalGate installed = gateForInstall(
                target -> {
                    decisions.incrementAndGet();
                    return resolverFor(
                            snapshot(true, false, policies(KillPolicy.all()), Map.of()),
                            Map.of(PROTECTED, UID),
                            false).decide(target, periodicProfile());
                },
                diagnostics,
                successfulInstaller,
                AthenaIdentityAdapter::prepare,
                deoptimizer);

        assertEquals(
                "INSTALLED:terminals=6,deopt=11",
                installed.install(getClass().getClassLoader()));
        assertEquals(11, deoptimizer.calls.get());
        for (int index = 0;
                index < AthenaTerminalRuntime.DirectCaller.values().length;
                index++) {
            assertTrue(AthenaTerminalRuntime.matches(
                    AthenaTerminalRuntime.DirectCaller.values()[index],
                    deoptimizer.methods.get(index)));
        }
        assertEquals(6, successfulInstaller.calls.get());
        assertEquals(6, successfulInstaller.hookers.size());
        FakeChain installedChain = returning(
                ORIGINAL, null, "key", 1, process(PROTECTED, UID, PID), new ClearRecord());
        assertNull(installed.interceptPeriodicSingle(installedChain));
        assertEquals(0, installedChain.proceedCalls());
        assertEquals(1, decisions.get());

        for (int failingCall = 1; failingCall <= 6; failingCall++) {
            for (int failingCount : new int[]{-1, 0, 2}) {
                AtomicInteger partialDecisions = new AtomicInteger();
                RecordingDiagnostics partialDiagnostics = new RecordingDiagnostics();
                FakeInstaller installer = new FakeInstaller(failingCall, failingCount);
                AthenaTerminalGate partial = gateForInstall(
                        target -> {
                            partialDecisions.incrementAndGet();
                            return null;
                        },
                        partialDiagnostics,
                        installer,
                        AthenaIdentityAdapter::prepare);

                String status = partial.install(getClass().getClassLoader());
                assertTrue(status, status.startsWith("FAILED_FAIL_OPEN:PARTIAL="));
                assertEquals(status, partial.install(getClass().getClassLoader()));
                assertEquals(failingCall, installer.calls.get());

                FakeChain chain = returning(
                        ORIGINAL,
                        null,
                        "key",
                        1,
                        process(PROTECTED, UID, PID),
                        new ClearRecord());
                assertSame(ORIGINAL, partial.interceptPeriodicSingle(chain));
                assertEquals(1, chain.proceedCalls());
                assertEquals(0, partialDecisions.get());
                assertTrue(partialDiagnostics.decisions.isEmpty());
                assertTrue(partialDiagnostics.failures.isEmpty());
            }
        }
    }

    @Test
    public void installedHooksStayInactiveWhenAnyDirectCallerCannotDeoptimize()
            throws Throwable {
        for (int failingCall = 1; failingCall <= 11; failingCall++) {
            FakeInstaller installer = new FakeInstaller(-1, 1);
            RecordingDeoptimizer deoptimizer = new RecordingDeoptimizer(
                    failingCall, false);
            AthenaTerminalGate gate = gateForInstall(
                    target -> null,
                    new RecordingDiagnostics(),
                    installer,
                    AthenaIdentityAdapter::prepare,
                    deoptimizer);

            String status = gate.install(getClass().getClassLoader());

            assertTrue(status, status.startsWith("FAILED_FAIL_OPEN:DEOPTIMIZE="));
            assertEquals(failingCall, deoptimizer.calls.get());
            assertEquals(6, installer.calls.get());
            FakeChain chain = returning(
                    ORIGINAL, null, "key", 1,
                    process(PROTECTED, UID, PID), new ClearRecord());
            assertSame(ORIGINAL, gate.interceptPeriodicSingle(chain));
            assertEquals(1, chain.proceedCalls());
        }

        FakeInstaller installer = new FakeInstaller(-1, 1);
        RecordingDeoptimizer throwing = new RecordingDeoptimizer(4, true);
        AthenaTerminalGate gate = gateForInstall(
                target -> null,
                new RecordingDiagnostics(),
                installer,
                AthenaIdentityAdapter::prepare,
                throwing);
        String status = gate.install(getClass().getClassLoader());
        assertTrue(status, status.startsWith("FAILED_FAIL_OPEN:DEOPTIMIZATION="));
        assertEquals(4, throwing.calls.get());
        assertEquals(6, installer.calls.get());
    }

    @Test
    public void stableFeatureHooksShareOneAtomicInstallation() {
        FakeInstaller installer = new FakeInstaller(-1, 1);
        RecordingDeoptimizer deoptimizer = new RecordingDeoptimizer(-1, false);
        AthenaTerminalGate gate = gateForInstall(
                target -> null,
                new RecordingDiagnostics(),
                installer,
                AthenaIdentityAdapter::prepare,
                deoptimizer);
        List<FeatureHook> hooks = gate.targetHooks();

        assertEquals(Arrays.asList(
                HookTargetIds.ATHENA_PERIODIC,
                HookTargetIds.FAST_CLEAR_DISPATCH,
                HookTargetIds.TOURNAMENT,
                HookTargetIds.MEMORY_GUARD_DISPATCH),
                Arrays.asList(
                        hooks.get(0).hookTargetId(),
                        hooks.get(1).hookTargetId(),
                        hooks.get(2).hookTargetId(),
                        hooks.get(3).hookTargetId()));
        for (FeatureHook hook : hooks) {
            assertEquals(
                    "INSTALLED:terminals=6,deopt=11",
                    hook.install(getClass().getClassLoader()));
        }
        assertEquals(11, deoptimizer.calls.get());
        assertEquals(6, installer.calls.get());
    }

    @Test
    public void accessorPreparationFailureInstallsNothingAndStaysInactive() throws Throwable {
        FakeInstaller installer = new FakeInstaller(-1, 1);
        AtomicInteger decisions = new AtomicInteger();
        AthenaTerminalGate gate = gateForInstall(
                target -> {
                    decisions.incrementAndGet();
                    return null;
                },
                new RecordingDiagnostics(),
                installer,
                matrix -> null);

        assertEquals(
                "FAILED_FAIL_OPEN:ACCESSOR_PREPARE=NULL",
                gate.install(getClass().getClassLoader()));
        assertEquals(0, installer.calls.get());

        FakeChain chain = returning(
                ORIGINAL, null, "key", 1, process(PROTECTED, UID, PID), new ClearRecord());
        assertSame(ORIGINAL, gate.interceptPeriodicSingle(chain));
        assertEquals(1, chain.proceedCalls());
        assertEquals(0, decisions.get());
    }

    @Test
    public void singleAndGroupTerminalsBlockOnlyExplicitProtectedDecisions() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AthenaTerminalGate gate = activeGate(
                resolverFor(
                        snapshot(true, false, policies(KillPolicy.all()), Map.of()),
                        Map.of(PROTECTED, UID),
                        false),
                diagnostics);
        ProcDetailInfo process = process(PROTECTED, UID, PID);

        FakeChain periodic = returning(
                ORIGINAL, null, "key", 1, process, new ClearRecord());
        assertNull(gate.interceptPeriodicSingle(periodic));
        assertEquals(0, periodic.proceedCalls());

        FakeChain tournament = returning(
                ORIGINAL,
                null,
                process,
                new ClearRecord(),
                new KeepRecord(),
                1,
                78,
                "tournamentmode");
        assertNull(gate.interceptTournament(tournament));
        assertEquals(0, tournament.proceedCalls());

        e4.t owner = new e4.t(4012);
        FakeChain single = returning(ORIGINAL, owner, new e4.a0(process), new e4.y());
        assertNull(gate.interceptMemorySingle(single));
        assertEquals(0, single.proceedCalls());

        e4.z group = new e4.z(PROTECTED, 0)
                .add(new e4.a0(process))
                .add(new e4.a0(process(PROTECTED, UID, PID + 1)));
        FakeChain grouped = returning(
                ORIGINAL, owner, group, new e4.y(), new d3.a());
        assertNull(gate.interceptMemoryGroup(grouped));
        assertEquals(0, grouped.proceedCalls());

        assertEquals(4, diagnostics.decisions.size());
        assertTrue(diagnostics.failures.isEmpty());
    }

    @Test
    public void tournamentUsesCallerCodeAndStopTypeWithoutReasonCoupling() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        AthenaTerminalGate gate = activeGate(
                (target, profile) -> {
                    decisions.incrementAndGet();
                    return blockingDecision(target, profile.path());
                },
                new RecordingDiagnostics());
        ProcDetailInfo process = process(PROTECTED, UID, PID);

        for (Object[] args : new Object[][]{
                {process, new ClearRecord(), new KeepRecord(), 1, 77, "tournamentmode"},
                {process, new ClearRecord(), new KeepRecord(), 3, 78, "tournamentmode"}
        }) {
            FakeChain chain = returning(ORIGINAL, null, args);
            assertSame(ORIGINAL, gate.interceptTournament(chain));
            assertEquals(1, chain.proceedCalls());
        }
        assertEquals(0, decisions.get());

        for (Object[] args : new Object[][]{
                {process, new ClearRecord(), new KeepRecord(), 1, 78, "free text"},
                {process, new ClearRecord(), new KeepRecord(), 2, 78, null}
        }) {
            FakeChain chain = returning(ORIGINAL, null, args);
            assertNull(gate.interceptTournament(chain));
            assertEquals(0, chain.proceedCalls());
        }
        assertEquals(2, decisions.get());
    }

    @Test
    public void periodicAndPrereleaseListsUseDefensiveFilteredCopies() throws Throwable {
        RuntimeConfigSnapshot snapshot = snapshot(
                true,
                false,
                Map.of(
                        PROTECTED, policy(PROTECTED, KillPolicy.all()),
                        ALLOWED, policy(ALLOWED, KillPolicy.none())),
                Map.of());
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AthenaTerminalGate gate = activeGate(
                resolverFor(
                        snapshot,
                        Map.of(PROTECTED, UID, ALLOWED, ALLOWED_UID),
                        false),
                diagnostics);
        ProcDetailInfo protectedProcess = process(PROTECTED, UID, PID);
        ProcDetailInfo allowedProcess = process(ALLOWED, ALLOWED_UID, PID + 1);

        List<ProcDetailInfo> periodicSource = Collections.unmodifiableList(
                Arrays.asList(protectedProcess, allowedProcess));
        FakeChain periodic = listReturning(
                null,
                periodicSource,
                new ClearRecord(),
                new KeepRecord(),
                1);
        Object periodicResult = gate.interceptPeriodicList(periodic);
        assertTrue(periodicResult instanceof List);
        assertNotSame(periodicSource, periodicResult);
        assertEquals(List.of(allowedProcess), periodicResult);
        assertEquals(Arrays.asList(protectedProcess, allowedProcess), periodicSource);
        assertEquals(1, periodic.proceedCalls());

        e4.a0 protectedCandidate = new e4.a0(protectedProcess);
        e4.a0 allowedCandidate = new e4.a0(allowedProcess);
        List<e4.a0> prereleaseSource = Collections.unmodifiableList(
                Arrays.asList(protectedCandidate, allowedCandidate));
        FakeChain prerelease = listReturning(
                new e4.t(4012),
                prereleaseSource,
                Map.of(),
                new e4.y(),
                Map.of());
        Object prereleaseResult = gate.interceptMemoryPrerelease(prerelease);
        assertTrue(prereleaseResult instanceof List);
        assertNotSame(prereleaseSource, prereleaseResult);
        assertEquals(List.of(allowedCandidate), prereleaseResult);
        assertEquals(Arrays.asList(protectedCandidate, allowedCandidate), prereleaseSource);
        assertEquals(1, prerelease.proceedCalls());
        assertEquals(2, diagnostics.candidates.size());
        assertEquals(2, diagnostics.decisions.size());
        assertTrue(diagnostics.failures.isEmpty());
    }

    @Test
    public void allBlockedListProducesAnEmptyDefensiveInput() throws Throwable {
        AthenaTerminalGate gate = activeGate(
                resolverFor(
                        snapshot(true, false, policies(KillPolicy.all()), Map.of()),
                        Map.of(PROTECTED, UID),
                        false),
                new RecordingDiagnostics());
        List<e4.a0> source = Collections.singletonList(
                new e4.a0(process(PROTECTED, UID, PID)));
        FakeChain chain = listReturning(
                new e4.t(4013), source, Map.of(), new e4.y(), Map.of());

        Object result = gate.interceptMemoryPrerelease(chain);

        assertTrue(result instanceof List);
        assertTrue(((List<?>) result).isEmpty());
        assertEquals(1, source.size());
        assertEquals(1, chain.proceedCalls());
    }

    @Test
    public void masterOffDryRunUnmanagedSharedUidAndUidMismatchPassThrough()
            throws Throwable {
        List<AthenaTerminalGate.DecisionResolver> resolvers = new ArrayList<>();
        resolvers.add(resolverFor(
                snapshot(false, false, policies(KillPolicy.all()), Map.of()),
                Map.of(PROTECTED, UID),
                false));
        resolvers.add(resolverFor(
                snapshot(true, true, policies(KillPolicy.all()), Map.of()),
                Map.of(PROTECTED, UID),
                false));
        resolvers.add(resolverFor(
                snapshot(true, false, policies(KillPolicy.all()), Map.of()),
                Map.of(PROTECTED, UID),
                false));

        Map<String, AppResidencyPolicy> sharedPolicies = new LinkedHashMap<>();
        sharedPolicies.put(PROTECTED, policy(PROTECTED, KillPolicy.all()));
        sharedPolicies.put(SHARED, policy(SHARED, KillPolicy.none()));
        resolvers.add(resolverFor(
                snapshot(true, false, sharedPolicies, Map.of()),
                Map.of(PROTECTED, UID, SHARED, UID),
                false));
        resolvers.add(resolverFor(
                snapshot(true, false, policies(KillPolicy.all()), Map.of()),
                Map.of(PROTECTED, UID),
                false));

        ProcDetailInfo[] processes = new ProcDetailInfo[]{
                process(PROTECTED, UID, PID),
                process(PROTECTED, UID, PID),
                process(ALLOWED, ALLOWED_UID, PID),
                process(PROTECTED, UID, PID),
                process(PROTECTED, ALLOWED_UID, PID)
        };
        for (int index = 0; index < resolvers.size(); index++) {
            RecordingDiagnostics diagnostics = new RecordingDiagnostics();
            AthenaTerminalGate gate = activeGate(resolvers.get(index), diagnostics);
            FakeChain chain = returning(
                    ORIGINAL, null, "key", 1, processes[index], new ClearRecord());

            assertSame(ORIGINAL, gate.interceptPeriodicSingle(chain));
            assertEquals(1, chain.proceedCalls());
            assertEquals(1, diagnostics.decisions.size());
            assertTrue(diagnostics.failures.isEmpty());
        }
    }

    @Test
    public void disabledLegacyFeaturePassesBeforeApplyingPerAppPolicy() throws Throwable {
        RuntimeConfigSnapshot snapshot = snapshot(
                true,
                false,
                policies(KillPolicy.all()),
                Map.of(ModuleSettings.KEY_PERIODIC, false));
        AthenaTerminalGate gate = activeGate(
                resolverFor(snapshot, Map.of(PROTECTED, UID), true),
                new RecordingDiagnostics());
        FakeChain chain = returning(
                ORIGINAL,
                null,
                "key",
                1,
                process(PROTECTED, UID, PID),
                new ClearRecord());

        assertSame(ORIGINAL, gate.interceptPeriodicSingle(chain));
        assertEquals(1, chain.proceedCalls());
    }

    @Test
    public void unknownObjectsMixedUidGroupsAndUnknownActionsFailOpen() throws Throwable {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger decisions = new AtomicInteger();
        AthenaTerminalGate gate = activeGate(
                (target, profile) -> {
                    decisions.incrementAndGet();
                    return blockingDecision(target, profile.path());
                },
                diagnostics);

        FakeChain invalid = returning(
                ORIGINAL, null, "key", 1, new Object(), new ClearRecord());
        assertSame(ORIGINAL, gate.interceptPeriodicSingle(invalid));

        e4.z mixed = new e4.z(PROTECTED, 0)
                .add(new e4.a0(process(PROTECTED, UID, PID)))
                .add(new e4.a0(process(PROTECTED, UID + 1, PID + 1)));
        FakeChain group = returning(
                ORIGINAL, new e4.t(4012), mixed, new e4.y(), new d3.a());
        assertSame(ORIGINAL, gate.interceptMemoryGroup(group));

        FakeChain unknownAction = returning(
                ORIGINAL,
                new e4.t(9999),
                new e4.a0(process(PROTECTED, UID, PID)),
                new e4.y());
        assertSame(ORIGINAL, gate.interceptMemorySingle(unknownAction));

        assertEquals(0, decisions.get());
        assertEquals(3, diagnostics.failures.size());
        assertEquals(FeatureIds.ATHENA_OTHER, diagnostics.failures.get(2).feature);
        assertEquals("memory_guard_unknown", diagnostics.failures.get(2).scene);
    }

    @Test
    public void decisionAndDiagnosticsFailuresNeverChangeTerminalBehavior() throws Throwable {
        AthenaTerminalGate decisionFailure = activeGate(
                (target, profile) -> {
                    throw new IllegalStateException("decision unavailable");
                },
                new ThrowingDiagnostics());
        FakeChain pass = returning(
                ORIGINAL,
                null,
                "key",
                1,
                process(PROTECTED, UID, PID),
                new ClearRecord());
        assertSame(ORIGINAL, decisionFailure.interceptPeriodicSingle(pass));
        assertEquals(1, pass.proceedCalls());

        AthenaTerminalGate diagnosticFailure = activeGate(
                resolverFor(
                        snapshot(true, false, policies(KillPolicy.all()), Map.of()),
                        Map.of(PROTECTED, UID),
                        false),
                new ThrowingDiagnostics());
        FakeChain blocked = returning(
                ORIGINAL,
                null,
                "key",
                1,
                process(PROTECTED, UID, PID),
                new ClearRecord());
        assertNull(diagnosticFailure.interceptPeriodicSingle(blocked));
        assertEquals(0, blocked.proceedCalls());
    }

    @Test
    public void actionCodeMatrixSeparatesFastClearAndMemoryGuardPaths() {
        for (int code : new int[]{2000, 2009, 2011, 4004, 4005, 4006, 4007}) {
            assertEquals(
                    KillContext.Path.FAST_CLEAR,
                    AthenaTerminalGate.profileForActionCode(code).path());
        }
        for (int code : new int[]{392, 4001, 4003, 4008, 4012, 4013, 4014}) {
            assertEquals(
                    KillContext.Path.MEMORY_GUARD,
                    AthenaTerminalGate.profileForActionCode(code).path());
        }
        assertNull(AthenaTerminalGate.profileForActionCode(78));
        assertNull(AthenaTerminalGate.profileForActionCode(9999));
        assertEquals(
                FeatureIds.FAST_CLEAR_OTHER,
                AthenaTerminalGate.profileForActionCode(4004).featureId());
        assertEquals(
                HookTargetIds.FAST_CLEAR_DISPATCH,
                AthenaTerminalGate.profileForActionCode(4004).hookTargetId());
        assertEquals(
                FeatureIds.MEMORY_GUARD_OTHER,
                AthenaTerminalGate.profileForActionCode(4001).featureId());
        assertEquals(
                HookTargetIds.MEMORY_GUARD_DISPATCH,
                AthenaTerminalGate.profileForActionCode(4001).hookTargetId());
        AthenaTerminalGate.ActionProfile unknown =
                AthenaTerminalGate.unknownActionProfile(9999);
        assertEquals(KillContext.Path.MEMORY_GUARD, unknown.path());
        assertEquals(FeatureIds.ATHENA_OTHER, unknown.featureId());
        assertEquals(HookTargetIds.MEMORY_GUARD_DISPATCH, unknown.hookTargetId());
        assertEquals("memory_guard_unknown", unknown.scene());
    }

    @Test
    public void unavailableReplacementArgumentsFailOpenBeforeListDecisions()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AthenaTerminalGate gate = activeGate(
                (target, profile) -> {
                    decisions.incrementAndGet();
                    return blockingDecision(target, profile.path());
                },
                diagnostics);
        List<ProcDetailInfo> source = List.of(process(PROTECTED, UID, PID));
        FakeChain chain = listReturning(
                null, source, new ClearRecord(), new KeepRecord(), 1).failGetArgs();

        assertSame(source, gate.interceptPeriodicList(chain));
        assertEquals(1, chain.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(1, diagnostics.failures.size());
    }

    @Test
    public void unavailableInitialArgumentsFailOpenWithoutRunningDecision()
            throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AthenaTerminalGate gate = activeGate(
                (target, profile) -> {
                    decisions.incrementAndGet();
                    return blockingDecision(target, profile.path());
                },
                diagnostics);
        FakeChain chain = returning(
                ORIGINAL,
                null,
                "key",
                1,
                process(PROTECTED, UID, PID),
                new ClearRecord()).failGetArgsAt(1);

        assertSame(ORIGINAL, gate.interceptPeriodicSingle(chain));
        assertEquals(1, chain.proceedCalls());
        assertEquals(0, decisions.get());
        assertEquals(1, diagnostics.failures.size());
    }

    @Test
    public void vendorExceptionPropagatesWithoutASecondProceed() throws Throwable {
        RuntimeException vendorFailure = new RuntimeException("vendor failure");
        AthenaTerminalGate gate = activeGate(
                resolverFor(
                        snapshot(true, false, policies(KillPolicy.none()), Map.of()),
                        Map.of(PROTECTED, UID),
                        false),
                new RecordingDiagnostics());
        FakeChain chain = new FakeChain(
                null,
                ignored -> { throw vendorFailure; },
                "key",
                1,
                process(PROTECTED, UID, PID),
                new ClearRecord());

        try {
            gate.interceptPeriodicSingle(chain);
            throw new AssertionError("vendor exception must propagate");
        } catch (RuntimeException error) {
            assertSame(vendorFailure, error);
        }
        assertEquals(1, chain.proceedCalls());
    }

    private AthenaTerminalGate activeGate(
            AthenaTerminalGate.DecisionResolver resolver,
            AthenaTerminalGate.DiagnosticsRecorder diagnostics) {
        return new AthenaTerminalGate(resolver, diagnostics, prepared(), true);
    }

    private AthenaTerminalGate gateForInstall(
            TargetOnlyResolver resolver,
            AthenaTerminalGate.DiagnosticsRecorder diagnostics,
            AthenaTerminalGate.HookInstaller installer,
            AthenaTerminalGate.AdapterPreparer preparer) {
        return gateForInstall(
                resolver,
                diagnostics,
                installer,
                preparer,
                method -> true);
    }

    private AthenaTerminalGate gateForInstall(
            TargetOnlyResolver resolver,
            AthenaTerminalGate.DiagnosticsRecorder diagnostics,
            AthenaTerminalGate.HookInstaller installer,
            AthenaTerminalGate.AdapterPreparer preparer,
            AthenaTerminalGate.MethodDeoptimizer deoptimizer) {
        return new AthenaTerminalGate(
                (target, profile) -> resolver.decide(target),
                diagnostics,
                installer,
                preparer,
                deoptimizer);
    }

    private AthenaIdentityAdapter.Prepared prepared() {
        AthenaIdentityAdapter.Preparation preparation = AthenaIdentityAdapter.prepare(
                AthenaTerminalRuntime.inspect(getClass().getClassLoader()));
        assertTrue(preparation.status().name(), preparation.isReady());
        return preparation.preparedOrNull();
    }

    private static AthenaTerminalGate.DecisionResolver resolverFor(
            RuntimeConfigSnapshot snapshot,
            Map<String, Integer> packageUids,
            boolean respectFeature) {
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(snapshot, packageUids);
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(snapshot, index);
        return (target, profile) -> {
            ResidencyTarget uidTarget = new ResidencyTarget(
                    null, target.uid(), target.pid());
            KillContext context = new KillContext(
                    uidTarget,
                    profile.path(),
                    profile.featureId(),
                    profile.hookTargetId());
            if (!respectFeature || profile.featureKey().isEmpty()) {
                return engine.decideKill(context);
            }
            return engine.decideKill(
                    context,
                    profile.featureKey(),
                    ModuleSettings.defaultFor(profile.featureKey()));
        };
    }

    private static KillDecision blockingDecision(
            ResidencyTarget target, KillContext.Path path) {
        RuntimeConfigSnapshot snapshot = snapshot(
                true,
                false,
                Map.of(target.packageName(), policy(target.packageName(), KillPolicy.all())),
                Map.of());
        ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                snapshot, Map.of(target.packageName(), target.uid()));
        return new ResidencyDecisionEngine(snapshot, index).decideKill(
                new KillContext(target, path));
    }

    private static AthenaTerminalGate.ActionProfile periodicProfile() {
        return AthenaTerminalGate.profileForActionCode(4012);
    }

    private static Map<String, AppResidencyPolicy> policies(KillPolicy killPolicy) {
        return Map.of(PROTECTED, policy(PROTECTED, killPolicy));
    }

    private static AppResidencyPolicy policy(String packageName, KillPolicy killPolicy) {
        AppResidencyPolicy base = ResidencyPresets.create(packageName, ResidencyMode.CUSTOM);
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                killPolicy,
                base.freezePolicy(),
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());
    }

    private static RuntimeConfigSnapshot snapshot(
            boolean masterEnabled,
            boolean dryRun,
            Map<String, AppResidencyPolicy> policies,
            Map<String, Boolean> features) {
        return RuntimeConfigSnapshot.available(
                1L,
                masterEnabled,
                true,
                dryRun,
                features,
                2L,
                policies);
    }

    private static ProcDetailInfo process(String packageName, int uid, int pid) {
        return new ProcDetailInfo(
                packageName,
                packageName + ":worker",
                uid,
                pid,
                uid / 100_000);
    }

    private static FakeChain returning(
            Object result, Object thisObject, Object... args) {
        return new FakeChain(thisObject, ignored -> result, args);
    }

    private static FakeChain listReturning(Object thisObject, Object... args) {
        return new FakeChain(thisObject, nextArgs -> nextArgs[0], args);
    }

    private interface TargetOnlyResolver {
        KillDecision decide(ResidencyTarget target);
    }

    private static final class RecordingDiagnostics
            implements AthenaTerminalGate.DiagnosticsRecorder {
        private final List<DecisionRecord> decisions = new ArrayList<>();
        private final List<HookEvent> candidates = new ArrayList<>();
        private final List<HookEvent> failures = new ArrayList<>();

        @Override
        public void recordDecision(HookEvent event, KillDecision decision) {
            decisions.add(new DecisionRecord(event, decision));
        }

        @Override
        public void recordCandidateFiltered(HookEvent event) {
            candidates.add(event);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            failures.add(event);
        }
    }

    private static final class ThrowingDiagnostics
            implements AthenaTerminalGate.DiagnosticsRecorder {
        @Override
        public void recordDecision(HookEvent event, KillDecision decision) {
            throw new AssertionError("diagnostics unavailable");
        }

        @Override
        public void recordCandidateFiltered(HookEvent event) {
            throw new AssertionError("diagnostics unavailable");
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            throw new AssertionError("diagnostics unavailable");
        }
    }

    private static final class DecisionRecord {
        private final HookEvent event;
        private final KillDecision decision;

        private DecisionRecord(HookEvent event, KillDecision decision) {
            this.event = event;
            this.decision = decision;
        }
    }

    private static final class FakeInstaller implements AthenaTerminalGate.HookInstaller {
        private final int failingCall;
        private final int failingCount;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<XposedInterface.Hooker> hookers = new ArrayList<>();

        private FakeInstaller(int failingCall, int failingCount) {
            this.failingCall = failingCall;
            this.failingCount = failingCount;
        }

        @Override
        public int hookMatching(
                String className,
                ClassLoader loader,
                com.frostwork.bgenhance.HookUtils.MethodMatcher matcher,
                XposedInterface.Hooker hooker) {
            int matching = 0;
            try {
                for (Method method : Class.forName(className, false, loader)
                        .getDeclaredMethods()) {
                    if (matcher.matches(method)) matching++;
                }
            } catch (ClassNotFoundException error) {
                throw new AssertionError(error);
            }
            assertEquals(className, 1, matching);
            hookers.add(hooker);
            int call = calls.incrementAndGet();
            return call == failingCall ? failingCount : 1;
        }
    }

    private static final class RecordingDeoptimizer
            implements AthenaTerminalGate.MethodDeoptimizer {
        private final int failingCall;
        private final boolean throwsAtFailure;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Method> methods = new ArrayList<>();

        private RecordingDeoptimizer(int failingCall, boolean throwsAtFailure) {
            this.failingCall = failingCall;
            this.throwsAtFailure = throwsAtFailure;
        }

        @Override
        public boolean deoptimize(Method method) {
            methods.add(method);
            int call = calls.incrementAndGet();
            if (call != failingCall) return true;
            if (throwsAtFailure) throw new IllegalStateException("deoptimize");
            return false;
        }
    }

    private interface Proceeding {
        Object call(Object[] args) throws Throwable;
    }

    private static final class FakeChain implements XposedInterface.Chain {
        private final Object thisObject;
        private final Proceeding proceeding;
        private final List<Object> args;
        private final AtomicInteger proceedCalls = new AtomicInteger();
        private Object[] proceededArgs;
        private final AtomicInteger getArgsCalls = new AtomicInteger();
        private int failGetArgsAt = -1;

        private FakeChain(Object thisObject, Proceeding proceeding, Object... args) {
            this.thisObject = thisObject;
            this.proceeding = proceeding;
            this.args = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)));
        }

        private int proceedCalls() { return proceedCalls.get(); }
        private FakeChain failGetArgs() {
            return failGetArgsAt(2);
        }
        private FakeChain failGetArgsAt(int call) {
            failGetArgsAt = call;
            return this;
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return thisObject; }
        @Override public List<Object> getArgs() {
            if (getArgsCalls.incrementAndGet() == failGetArgsAt) {
                throw new IllegalStateException("arguments unavailable");
            }
            return args;
        }
        @Override public Object getArg(int index) { return args.get(index); }

        @Override
        public Object proceed() throws Throwable {
            return proceed(args.toArray(new Object[0]));
        }

        @Override
        public Object proceed(Object[] nextArgs) throws Throwable {
            proceedCalls.incrementAndGet();
            proceededArgs = nextArgs.clone();
            return proceeding.call(nextArgs);
        }

        @Override
        public Object proceedWith(Object nextThisObject) throws Throwable {
            return proceed();
        }

        @Override
        public Object proceedWith(Object nextThisObject, Object[] nextArgs) throws Throwable {
            return proceed(nextArgs);
        }
    }
}
