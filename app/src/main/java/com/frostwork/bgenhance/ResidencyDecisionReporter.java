package com.frostwork.bgenhance;

import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionTrace;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.WakeDecision;

/** Converts typed residency decisions into the existing bounded diagnostics stream. */
public final class ResidencyDecisionReporter {
    private ResidencyDecisionReporter() {}

    /** Runtime outcome needed when a typed wake decision delegates to a throttle gate. */
    public enum WakeRuntimeOutcome {
        DECISION_APPLIED,
        THROTTLE_ALLOWED,
        THROTTLE_BLOCKED,
        FAIL_OPEN
    }

    public static void recordKill(HookEvent event, KillDecision decision) {
        recordKill(event, decision, true);
    }

    /** Records only in-memory diagnostics for callbacks that already hold system locks. */
    public static void recordKillFromLockedPath(HookEvent event, KillDecision decision) {
        recordKill(event, decision, false);
    }

    /** Records a freeze decision without scheduling work while a HANS lock is held. */
    public static void recordFreezeFromLockedPath(
            HookEvent event, FreezeDecision decision) {
        if (event == null || decision == null) return;
        try {
            HookDiagnostics.recordDecisionFromLockedPath(
                    event, resultFor(decision), decision.trace());
        } catch (Throwable ignored) {
            // Diagnostics must never delay or change a HANS-locked callback.
        }
    }

    /** Records a wake decision without scheduling work while a HANS lock is held. */
    public static void recordWakeFromLockedPath(
            HookEvent event,
            WakeDecision decision,
            WakeRuntimeOutcome runtimeOutcome) {
        if (event == null || decision == null) return;
        try {
            HookDiagnostics.recordDecisionFromLockedPath(
                    event, resultFor(decision, runtimeOutcome), decision.trace());
        } catch (Throwable ignored) {
            // Diagnostics must never delay or change a HANS-locked callback.
        }
    }

    /** Records a resource decision without scheduling work from a HANS callback. */
    public static void recordResourceFromLockedPath(
            HookEvent event, ResourceDecision decision) {
        if (event == null || decision == null) return;
        try {
            HookDiagnostics.recordDecisionFromLockedPath(
                    event, resultFor(decision), decision.trace());
        } catch (Throwable ignored) {
            // Diagnostics must never delay or change a HANS callback.
        }
    }

    /** Records a timed refreeze decision without scheduling work from a HANS callback. */
    public static void recordRefreezeFromLockedPath(
            HookEvent event, RefreezeDecision decision) {
        if (event == null || decision == null) return;
        try {
            HookDiagnostics.recordDecisionFromLockedPath(
                    event, resultFor(decision), decision.trace());
        } catch (Throwable ignored) {
            // Diagnostics must never delay or change a HANS state transition.
        }
    }

    private static void recordKill(
            HookEvent event, KillDecision decision, boolean writeLog) {
        if (event == null || decision == null) return;
        String result = resultFor(decision);
        try {
            if (writeLog) {
                HookDiagnostics.recordDecision(event, result, decision.trace());
            } else {
                HookDiagnostics.recordDecisionFromLockedPath(
                        event, result, decision.trace());
            }
            if (!writeLog) return;
            DecisionTrace trace = decision.trace();
            ModuleLog.d(event
                    + " result=" + result
                    + " disposition=" + trace.disposition()
                    + " decisionReason=" + trace.reason()
                    + " identity=" + trace.identity().status()
                    + " configRevision=" + trace.configRevision()
                    + " policyRevision=" + trace.policyRevision()
                    + " identityRevision=" + trace.identityIndexRevision()
                    + " identityGeneration=" + trace.identityGeneration());
        } catch (Throwable error) {
            if (writeLog) {
                ModuleLog.w("residency decision diagnostics failed: " + error);
            }
        }
    }

    static String resultFor(KillDecision decision) {
        DecisionDisposition disposition = decision.trace().disposition();
        if (disposition == DecisionDisposition.FAIL_OPEN) return DiagnosticsResult.FAIL_OPEN;
        if (disposition == DecisionDisposition.DRY_RUN) return DiagnosticsResult.OBSERVED;
        return decision.blocksAction()
                ? DiagnosticsResult.BLOCKED : DiagnosticsResult.PASSED;
    }

    static String resultFor(FreezeDecision decision) {
        DecisionDisposition disposition = decision.trace().disposition();
        if (disposition == DecisionDisposition.FAIL_OPEN) return DiagnosticsResult.FAIL_OPEN;
        if (disposition == DecisionDisposition.DRY_RUN) return DiagnosticsResult.OBSERVED;
        return decision.blocksFreeze()
                ? DiagnosticsResult.BLOCKED : DiagnosticsResult.PASSED;
    }

    static String resultFor(
            WakeDecision decision, WakeRuntimeOutcome runtimeOutcome) {
        DecisionDisposition disposition = decision.trace().disposition();
        if (disposition == DecisionDisposition.FAIL_OPEN) return DiagnosticsResult.FAIL_OPEN;
        if (disposition == DecisionDisposition.DRY_RUN) return DiagnosticsResult.OBSERVED;
        if (runtimeOutcome == WakeRuntimeOutcome.FAIL_OPEN) {
            return DiagnosticsResult.RUNTIME_FAIL_OPEN;
        }
        if (decision.effectiveAction() == WakeDecision.Action.BLOCK_WAKE) {
            return DiagnosticsResult.BLOCKED;
        }
        if (decision.effectiveAction() != WakeDecision.Action.THROTTLE_WAKE) {
            return DiagnosticsResult.PASSED;
        }
        if (runtimeOutcome == WakeRuntimeOutcome.THROTTLE_ALLOWED) {
            return DiagnosticsResult.THROTTLE_ALLOWED;
        }
        if (runtimeOutcome == WakeRuntimeOutcome.THROTTLE_BLOCKED) {
            return DiagnosticsResult.THROTTLE_BLOCKED;
        }
        return DiagnosticsResult.RUNTIME_FAIL_OPEN;
    }

    static String resultFor(ResourceDecision decision) {
        DecisionDisposition disposition = decision.trace().disposition();
        if (disposition == DecisionDisposition.FAIL_OPEN) return DiagnosticsResult.FAIL_OPEN;
        if (disposition == DecisionDisposition.DRY_RUN) return DiagnosticsResult.OBSERVED;
        return decision.keepsResource()
                ? DiagnosticsResult.BLOCKED : DiagnosticsResult.PASSED;
    }

    static String resultFor(RefreezeDecision decision) {
        DecisionDisposition disposition = decision.trace().disposition();
        if (disposition == DecisionDisposition.FAIL_OPEN) return DiagnosticsResult.FAIL_OPEN;
        if (disposition == DecisionDisposition.DRY_RUN) return DiagnosticsResult.OBSERVED;
        return decision.effectiveAction() == RefreezeDecision.Action.DISABLE_REFREEZE
                ? DiagnosticsResult.BLOCKED : DiagnosticsResult.PASSED;
    }
}
