package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusHansManager;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.hooks.hans.HansRuntime;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionContext;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class HansExemptionHookTest {
    private static final String PACKAGE = "com.example.hans.exemption";
    private static final int UID = 10_852;

    @Test
    public void callsOriginalBeforeDecisionAndOnlyExpandsFalseToTrue() throws Throwable {
        List<String> order = new ArrayList<>();
        FreezeExemptionDecision decision = decision(
                FreezeExemptionContext.Kind.LCD_ON, false, false, false, true);

        Object result = HansExemptionHook.complete(
                () -> {
                    order.add("original");
                    return Boolean.FALSE;
                },
                () -> {
                    order.add("decision");
                    return decision;
                });

        assertEquals(List.of("original", "decision"), order);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    public void vendorExemptionAndUnexpectedResultTypeSkipDecision() throws Throwable {
        AtomicInteger decisions = new AtomicInteger();
        Object unexpected = new Object();

        assertEquals(Boolean.TRUE, HansExemptionHook.complete(
                () -> Boolean.TRUE,
                () -> {
                    decisions.incrementAndGet();
                    return null;
                }));
        assertSame(unexpected, HansExemptionHook.complete(
                () -> unexpected,
                () -> {
                    decisions.incrementAndGet();
                    return null;
                }));
        assertEquals(0, decisions.get());
    }

    @Test
    public void followSystemAndDryRunKeepVendorFalse() throws Throwable {
        FreezeExemptionDecision follow = decision(
                FreezeExemptionContext.Kind.CORE, false, false, false, false);
        FreezeExemptionDecision dryRun = decision(
                FreezeExemptionContext.Kind.LCD_ON, true, false, false, true);

        assertEquals(Boolean.FALSE, HansExemptionHook.complete(
                () -> Boolean.FALSE, () -> follow));
        assertEquals(Boolean.FALSE, HansExemptionHook.complete(
                () -> Boolean.FALSE, () -> dryRun));
    }

    @Test
    public void lcdFailOpenDecisionKeepsVendorFalseAtHookBoundary() throws Throwable {
        FreezeExemptionDecision failOpen = decision(
                FreezeExemptionContext.Kind.LCD_ON,
                false,
                false,
                false,
                true,
                ResidencyTarget.forUid(UID + 1));

        assertEquals(
                DecisionDisposition.FAIL_OPEN,
                failOpen.trace().disposition());
        assertEquals(Boolean.FALSE, HansExemptionHook.complete(
                () -> Boolean.FALSE,
                () -> failOpen));
    }

    @Test
    public void failuresPreserveOriginalSemantics() throws Throwable {
        OriginalFailure failure = new OriginalFailure();
        AtomicInteger decisions = new AtomicInteger();

        assertEquals(Boolean.FALSE, HansExemptionHook.complete(
                () -> Boolean.FALSE,
                () -> {
                    throw new IllegalStateException("decision failed");
                }));
        OriginalFailure actual = assertThrows(OriginalFailure.class, () ->
                HansExemptionHook.complete(
                        () -> { throw failure; },
                        () -> {
                            decisions.incrementAndGet();
                            return null;
                        }));

        assertSame(failure, actual);
        assertEquals(0, decisions.get());
    }

    @Test
    public void exposesIndependentCapabilitiesTargetsAndCoreSafetyBoundary() {
        HansExemptionHook core = HansExemptionHook.core();
        HansExemptionHook lcdOn = HansExemptionHook.lcdOn();
        AtomicInteger coreInstalls = new AtomicInteger();
        AtomicInteger lcdInstalls = new AtomicInteger();

        assertEquals(HansRuntime.Capability.CORE_EXEMPTION, core.capability());
        assertEquals(HansRuntime.Capability.LCD_ON_EXEMPTION, lcdOn.capability());
        assertEquals(HookTargetIds.HANS_CORE_EXEMPTION, core.hookTargetId());
        assertEquals(HookTargetIds.HANS_LCD_ON_EXEMPTION, lcdOn.hookTargetId());
        assertFalse(core.enforcesBehavior());
        assertTrue(lcdOn.enforcesBehavior());
        assertEquals(
                HansExemptionHook.FREEZE_GATE_INCOMPLETE,
                core.install(OplusHansManager.class.getClassLoader()));
        assertEquals(
                HansExemptionHook.FREEZE_GATE_INCOMPLETE,
                core.installVerifiedTarget(() -> {
                    coreInstalls.incrementAndGet();
                    return 1;
                }));
        assertEquals("INSTALLED:1", lcdOn.installVerifiedTarget(() -> {
            lcdInstalls.incrementAndGet();
            return 1;
        }));
        assertEquals(0, coreInstalls.get());
        assertEquals(1, lcdInstalls.get());
    }

    @Test
    public void reportsCapabilityAndInstallationStatesPrecisely() {
        assertEquals(
                "UNSUPPORTED_MISSING_CLASS",
                HansExemptionHook.capabilityStatus(HansRuntime.Status.MISSING_CLASS));
        assertEquals(
                "FAILED_FAIL_OPEN:LOOKUP_FAILED",
                HansExemptionHook.capabilityStatus(HansRuntime.Status.LOOKUP_FAILED));
        assertEquals("UNSUPPORTED_CLASS", HansExemptionHook.installationStatus(-1));
        assertEquals(
                "FAILED_FAIL_OPEN:HOOK_NOT_INSTALLED",
                HansExemptionHook.installationStatus(0));
        assertEquals("INSTALLED:1", HansExemptionHook.installationStatus(1));
        assertEquals(
                "FAILED_FAIL_OPEN:UNEXPECTED_HOOK_COUNT=2",
                HansExemptionHook.installationStatus(2));
    }

    @Test
    public void onlyProductionExemptionEventsReplaceTheRawOnlyDiagnostic() {
        ResidencyTarget target = new ResidencyTarget(
                PACKAGE, UID, ResidencyTarget.UNKNOWN_PID);
        FreezeExemptionDecision decision = decision(
                FreezeExemptionContext.Kind.LCD_ON,
                false,
                false,
                false,
                true);

        assertTrue(HansExemptionHook.producesStandardEvent(
                FreezeExemptionContext.Kind.LCD_ON, target, decision));
        assertFalse(HansExemptionHook.producesStandardEvent(
                FreezeExemptionContext.Kind.CORE, target, decision));
        assertFalse(HansExemptionHook.producesStandardEvent(
                FreezeExemptionContext.Kind.LCD_ON, null, decision));
        assertFalse(HansExemptionHook.producesStandardEvent(
                FreezeExemptionContext.Kind.LCD_ON, target, null));
    }

    @Test
    public void targetParsingRequiresExactUidAndPackageTypes() {
        ResidencyTarget expected = new ResidencyTarget(
                PACKAGE, UID, ResidencyTarget.UNKNOWN_PID);

        assertEquals(expected, HansExemptionHook.target(UID, PACKAGE));
        assertNull(HansExemptionHook.target(ResidencyTarget.UNKNOWN_UID, PACKAGE));
        assertNull(HansExemptionHook.target(UID, null));
        assertNull(HansExemptionHook.target(UID, ""));
        assertEquals(expected, HansExemptionHook.target(new FakeChain(UID, PACKAGE)));
        assertNull(HansExemptionHook.target(new FakeChain((long) UID, PACKAGE)));
        assertNull(HansExemptionHook.target(new FakeChain(UID, new Object())));
        assertNull(HansExemptionHook.target(new FakeChain(UID)));
    }

    private static FreezeExemptionDecision decision(
            FreezeExemptionContext.Kind kind,
            boolean dryRun,
            boolean fullExemption,
            boolean coreExemption,
            boolean lcdOnExemption) {
        return decision(
                kind,
                dryRun,
                fullExemption,
                coreExemption,
                lcdOnExemption,
                new ResidencyTarget(PACKAGE, UID, ResidencyTarget.UNKNOWN_PID));
    }

    private static FreezeExemptionDecision decision(
            FreezeExemptionContext.Kind kind,
            boolean dryRun,
            boolean fullExemption,
            boolean coreExemption,
            boolean lcdOnExemption,
            ResidencyTarget target) {
        FreezePolicy freezePolicy = new FreezePolicy(
                true,
                true,
                true,
                true,
                false,
                0L,
                0L,
                fullExemption,
                coreExemption,
                lcdOnExemption);
        AppResidencyPolicy base = ResidencyPresets.create(PACKAGE, ResidencyMode.CUSTOM);
        AppResidencyPolicy policy = new AppResidencyPolicy(
                PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                base.killPolicy(),
                freezePolicy,
                base.wakePolicy(),
                base.resourcePolicy(),
                base.refreezePolicy());
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                1L, true, true, dryRun, 2L, Map.of(PACKAGE, policy));
        ResidencyDecisionEngine engine = new ResidencyDecisionEngine(
                snapshot,
                ResidencyIdentityIndex.build(snapshot, Map.of(PACKAGE, UID)));
        return engine.decideFreezeExemption(new FreezeExemptionContext(
                target,
                kind,
                "",
                "hans_exemption_test"));
    }

    private static final class OriginalFailure extends Exception {}

    private static final class FakeChain implements XposedInterface.Chain {
        private final List<Object> args;

        private FakeChain(Object... args) {
            this.args = Arrays.asList(args);
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return null; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }
        @Override public Object proceed() { throw new UnsupportedOperationException(); }
        @Override public Object proceed(Object[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public Object proceedWith(Object thisObject) {
            throw new UnsupportedOperationException();
        }
        @Override public Object proceedWith(Object thisObject, Object[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
