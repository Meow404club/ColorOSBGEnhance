package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.hooks.hans.WakeThrottleState;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionReason;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.IdentityResolutionStatus;
import com.frostwork.bgenhance.residency.runtime.PackageUidSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndexBuilder;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.Test;

/** End-to-end regressions for full-UID HANS kernel wake callbacks. */
public final class HansWakeUidOnlyPolicyRegressionTest {
    private static final String PACKAGE = "com.example.hans.uidonly";
    private static final String SHARED_PACKAGE = "com.example.hans.uidonly.shared";
    private static final int CURRENT_USER_UID = 10_902;
    private static final int OTHER_USER_SAME_APP_ID_UID = 110_902;

    @Test
    public void currentUserFullUidAppliesSavedPackageWakePolicy() throws Throwable {
        RuntimeConfigSnapshot snapshot = snapshot(blockingPolicy(PACKAGE));
        FakePackageUidSource packageUids = new FakePackageUidSource(
                Map.of(PACKAGE, CURRENT_USER_UID),
                Map.of(CURRENT_USER_UID, Set.of(PACKAGE)));
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook(snapshot, packageUids, reporter);
        Object vendorResult = new Object();
        FakeChain chain = kernelChain(vendorResult, CURRENT_USER_UID);

        assertNull(hook.interceptKernel(chain));

        assertEquals(0, chain.proceedCalls());
        WakeDecision decision = reporter.onlyDecision();
        assertEquals(WakeDecision.Action.BLOCK_WAKE, decision.effectiveAction());
        assertEquals(DecisionDisposition.ENFORCED, decision.trace().disposition());
        assertEquals(DecisionReason.WAKE_BLOCKED, decision.trace().reason());
        assertEquals(
                IdentityResolutionStatus.UID_SINGLE_RULE,
                decision.trace().identity().status());
        assertEquals(
                PACKAGE,
                decision.trace().identity().matchedPackageName().orElse(null));
        assertEquals(Set.of(PACKAGE), decision.trace().identity().candidatePackages());
    }

    @Test
    public void sameAppIdInAnotherUserDoesNotReuseCurrentUserPolicy() throws Throwable {
        RuntimeConfigSnapshot snapshot = snapshot(blockingPolicy(PACKAGE));
        FakePackageUidSource packageUids = new FakePackageUidSource(
                Map.of(PACKAGE, CURRENT_USER_UID),
                Map.of(CURRENT_USER_UID, Set.of(PACKAGE)));
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook(snapshot, packageUids, reporter);
        Object vendorResult = new Object();
        FakeChain chain = kernelChain(vendorResult, OTHER_USER_SAME_APP_ID_UID);

        assertSame(vendorResult, hook.interceptKernel(chain));

        assertEquals(1, chain.proceedCalls());
        assertEquals(
                ResidencyIdentityIndex.appId(CURRENT_USER_UID),
                ResidencyIdentityIndex.appId(OTHER_USER_SAME_APP_ID_UID));
        WakeDecision decision = reporter.onlyDecision();
        assertEquals(WakeDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.IDENTITY_UNKNOWN, decision.trace().reason());
        assertEquals(IdentityResolutionStatus.UNKNOWN, decision.trace().identity().status());
        assertEquals(OTHER_USER_SAME_APP_ID_UID, decision.trace().identity().target().uid());
        assertFalse(decision.trace().identity().matchedPackageName().isPresent());
    }

    @Test
    public void sharedUidWithUnmanagedSiblingRemainsFailOpen() throws Throwable {
        RuntimeConfigSnapshot snapshot = snapshot(blockingPolicy(PACKAGE));
        FakePackageUidSource packageUids = new FakePackageUidSource(
                Map.of(PACKAGE, CURRENT_USER_UID),
                Map.of(CURRENT_USER_UID, Set.of(PACKAGE, SHARED_PACKAGE)));
        RecordingReporter reporter = new RecordingReporter();
        HansWakeHook hook = hook(snapshot, packageUids, reporter);
        Object vendorResult = new Object();
        FakeChain chain = kernelChain(vendorResult, CURRENT_USER_UID);

        assertSame(vendorResult, hook.interceptKernel(chain));

        assertEquals(1, chain.proceedCalls());
        WakeDecision decision = reporter.onlyDecision();
        assertEquals(WakeDecision.Action.ALLOW_SYSTEM, decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.SHARED_UID_CONFLICT, decision.trace().reason());
        assertEquals(
                IdentityResolutionStatus.SHARED_UID_CONFLICT,
                decision.trace().identity().status());
        assertEquals(
                Set.of(PACKAGE, SHARED_PACKAGE),
                decision.trace().identity().candidatePackages());
        assertFalse(decision.trace().identity().matchedPackageName().isPresent());
    }

    private static HansWakeHook hook(
            RuntimeConfigSnapshot snapshot,
            PackageUidSource packageUids,
            RecordingReporter reporter) {
        ResidencyIdentityIndex identities =
                new ResidencyIdentityIndexBuilder(packageUids).build(snapshot);
        ResidencyDecisionEngine engine =
                new ResidencyDecisionEngine(snapshot, identities, 19L);
        return new HansWakeHook(
                (target, source) -> engine.decideWake(new WakeContext(
                        target,
                        source,
                        FeatureIds.HANS_WAKE,
                        HookTargetIds.HANS_WAKE_DISPATCH)),
                new WakeThrottleState(),
                () -> 1_000L,
                reporter);
    }

    private static RuntimeConfigSnapshot snapshot(AppResidencyPolicy policy) {
        return RuntimeConfigSnapshot.available(
                17L,
                true,
                true,
                false,
                23L,
                Map.of(policy.packageName(), policy));
    }

    private static AppResidencyPolicy blockingPolicy(String packageName) {
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                FreezePolicy.followSystem(),
                new WakePolicy(
                        WakeChannelPolicy.allow(),
                        WakeChannelPolicy.allow(),
                        Set.of(WakeSource.ASYNC_BINDER)),
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem());
    }

    private static FakeChain kernelChain(Object result, int uid) {
        return FakeChain.returning(
                result,
                0,
                101,
                1_001,
                202,
                uid,
                "rpc",
                7);
    }

    private static final class FakePackageUidSource implements PackageUidSource {
        private final Map<String, Integer> packageUids;
        private final Map<Integer, Set<String>> packagesByUid;

        private FakePackageUidSource(
                Map<String, Integer> packageUids,
                Map<Integer, Set<String>> packagesByUid) {
            this.packageUids = new LinkedHashMap<>(packageUids);
            this.packagesByUid = new LinkedHashMap<>();
            for (Map.Entry<Integer, Set<String>> entry : packagesByUid.entrySet()) {
                this.packagesByUid.put(
                        entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }

        @Override
        public boolean hasCompleteUidEnumeration() {
            return true;
        }

        @Override
        public OptionalInt findUid(String packageName) {
            Integer uid = packageUids.get(packageName);
            return uid == null ? OptionalInt.empty() : OptionalInt.of(uid);
        }

        @Override
        public Set<String> findPackagesForUid(int uid) {
            Set<String> packages = packagesByUid.get(uid);
            return packages == null ? Set.of() : new LinkedHashSet<>(packages);
        }
    }

    private static final class RecordingReporter
            implements HansWakeHook.WakeDiagnosticsRecorder {
        private final List<WakeDecision> decisions = new ArrayList<>();
        private final List<HookEvent> failures = new ArrayList<>();

        @Override
        public void recordDecision(
                HookEvent event,
                WakeDecision decision,
                WakeThrottleState.Outcome throttleOutcome) {
            decisions.add(decision);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            failures.add(event);
        }

        @Override
        public void recordUnparsedArguments() {
            throw new AssertionError("test callbacks use structurally valid arguments");
        }

        private WakeDecision onlyDecision() {
            assertEquals(0, failures.size());
            assertEquals(1, decisions.size());
            return decisions.get(0);
        }
    }

    private static final class FakeChain implements XposedInterface.Chain {
        private final Object result;
        private final List<Object> args;
        private int proceedCalls;

        private FakeChain(Object result, Object... args) {
            this.result = result;
            this.args = Arrays.asList(args);
        }

        private static FakeChain returning(Object result, Object... args) {
            return new FakeChain(result, args);
        }

        private int proceedCalls() {
            return proceedCalls;
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return null; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }

        @Override
        public Object proceed() {
            proceedCalls++;
            return result;
        }

        @Override
        public Object proceed(Object[] nextArgs) {
            proceedCalls++;
            return result;
        }

        @Override
        public Object proceedWith(Object thisObject) {
            proceedCalls++;
            return result;
        }

        @Override
        public Object proceedWith(Object thisObject, Object[] nextArgs) {
            proceedCalls++;
            return result;
        }
    }
}
