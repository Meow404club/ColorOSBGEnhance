package com.frostwork.bgenhance;

import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.DecisionTrace;
import com.frostwork.bgenhance.residency.decision.KillDecision;

/** Converts typed residency decisions into the existing bounded diagnostics stream. */
public final class ResidencyDecisionReporter {
    private ResidencyDecisionReporter() {}

    public static void recordKill(HookEvent event, KillDecision decision) {
        recordKill(event, decision, true);
    }

    /** Records only in-memory diagnostics for callbacks that already hold system locks. */
    public static void recordKillFromLockedPath(HookEvent event, KillDecision decision) {
        recordKill(event, decision, false);
    }

    private static void recordKill(
            HookEvent event, KillDecision decision, boolean writeLog) {
        if (event == null || decision == null) return;
        String result = resultFor(decision);
        try {
            if (writeLog) {
                HookDiagnostics.recordEvent(event, result);
            } else {
                HookDiagnostics.recordEventFromLockedPath(event, result);
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
                    + " identityRevision=" + trace.identityIndexRevision());
        } catch (Throwable error) {
            if (writeLog) {
                ModuleLog.w("residency decision diagnostics failed: " + error);
            }
        }
    }

    static String resultFor(KillDecision decision) {
        DecisionDisposition disposition = decision.trace().disposition();
        if (disposition == DecisionDisposition.FAIL_OPEN) return "fail_open";
        if (disposition == DecisionDisposition.DRY_RUN) return "observed";
        return decision.blocksAction() ? "blocked" : "passed";
    }
}
