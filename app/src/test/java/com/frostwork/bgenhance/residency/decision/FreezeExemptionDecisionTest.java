package com.frostwork.bgenhance.residency.decision;

import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.MANAGED_PACKAGE;
import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.MANAGED_UID;
import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.managedTarget;
import static com.frostwork.bgenhance.residency.decision.DecisionTestEnvironment.policy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.UidPolicyScope;
import org.junit.Test;

public final class FreezeExemptionDecisionTest {
    @Test
    public void kindsOwnIndependentUidPolicyScopes() {
        assertEquals(
                UidPolicyScope.CORE_EXEMPTION,
                FreezeExemptionContext.Kind.CORE.uidPolicyScope());
        assertEquals(
                UidPolicyScope.LCD_ON_EXEMPTION,
                FreezeExemptionContext.Kind.LCD_ON.uidPolicyScope());
    }

    @Test
    public void fullExemptionProducesGrantIntentForBothTypedQueries() {
        ResidencyDecisionEngine engine = engine(customPolicy(
                new FreezePolicy(
                        true, true, true, true, false, 0L, 0L,
                        true, false, false)),
                true,
                false);

        for (FreezeExemptionContext.Kind kind : FreezeExemptionContext.Kind.values()) {
            FreezeExemptionDecision decision = engine.decideFreezeExemption(
                    new FreezeExemptionContext(
                            managedTarget(), kind, "", "exemption." + kind.name()));

            assertEquals(
                    FreezeExemptionDecision.Action.GRANT_EXEMPTION,
                    decision.effectiveAction());
            assertEquals(
                    FreezeExemptionDecision.Action.GRANT_EXEMPTION,
                    decision.policyAction());
            assertTrue(decision.grantsExemption());
            assertEquals(DecisionDisposition.ENFORCED, decision.trace().disposition());
            assertEquals(DecisionReason.FULL_EXEMPTION, decision.trace().reason());
        }
    }

    @Test
    public void lcdOnFlagOnlyGrantsTheVerifiedLcdQuery() {
        ResidencyDecisionEngine engine = engine(customPolicy(
                new FreezePolicy(
                        true, true, true, true, false, 0L, 0L,
                        false, true, true)),
                true,
                false);

        for (FreezeExemptionContext.Kind kind : FreezeExemptionContext.Kind.values()) {
            FreezeExemptionDecision decision = engine.decideFreezeExemption(
                    new FreezeExemptionContext(managedTarget(), kind));

            if (kind == FreezeExemptionContext.Kind.LCD_ON) {
                assertEquals(
                        FreezeExemptionDecision.Action.GRANT_EXEMPTION,
                        decision.effectiveAction());
                assertTrue(decision.grantsExemption());
                assertEquals(
                        DecisionReason.LCD_ON_EXEMPTION,
                        decision.trace().reason());
            } else {
                assertEquals(
                        FreezeExemptionDecision.Action.FOLLOW_SYSTEM,
                        decision.effectiveAction());
                assertFalse(decision.grantsExemption());
                assertEquals(
                        DecisionReason.POLICY_FOLLOWS_EXEMPTION,
                        decision.trace().reason());
            }
        }
    }

    @Test
    public void dryRunPreservesIntentWithoutGrantingExemption() {
        ResidencyDecisionEngine engine = engine(customPolicy(
                new FreezePolicy(
                        true, true, true, true, false, 0L, 0L,
                        false, false, true)),
                true,
                true);

        FreezeExemptionDecision decision = engine.decideFreezeExemption(
                new FreezeExemptionContext(
                        managedTarget(), FreezeExemptionContext.Kind.LCD_ON));

        assertEquals(
                FreezeExemptionDecision.Action.FOLLOW_SYSTEM,
                decision.effectiveAction());
        assertEquals(
                FreezeExemptionDecision.Action.GRANT_EXEMPTION,
                decision.policyAction());
        assertFalse(decision.grantsExemption());
        assertEquals(DecisionDisposition.DRY_RUN, decision.trace().disposition());
    }

    @Test
    public void unknownIdentityFailsOpen() {
        ResidencyDecisionEngine engine = engine(customPolicy(
                new FreezePolicy(
                        true, true, true, true, false, 0L, 0L,
                        true, false, false)),
                true,
                false);

        FreezeExemptionDecision decision = engine.decideFreezeExemption(
                new FreezeExemptionContext(
                        ResidencyTarget.forUid(MANAGED_UID + 50),
                        FreezeExemptionContext.Kind.CORE));

        assertEquals(
                FreezeExemptionDecision.Action.FOLLOW_SYSTEM,
                decision.effectiveAction());
        assertEquals(DecisionDisposition.FAIL_OPEN, decision.trace().disposition());
        assertEquals(DecisionReason.IDENTITY_UNKNOWN, decision.trace().reason());
    }

    private static AppResidencyPolicy customPolicy(FreezePolicy freezePolicy) {
        return policy(
                MANAGED_PACKAGE,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                freezePolicy,
                WakePolicy.allowAll(),
                ResourcePolicy.followSystem(),
                RefreezePolicy.followSystem());
    }

    private static ResidencyDecisionEngine engine(
            AppResidencyPolicy policy,
            boolean residencyEnabled,
            boolean dryRun) {
        return DecisionTestEnvironment.engine(
                policy, true, residencyEnabled, dryRun,
                DecisionTestEnvironment.POLICY_REVISION);
    }
}
