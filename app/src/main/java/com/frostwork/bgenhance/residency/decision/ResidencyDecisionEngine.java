package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.IdentityResolutionStatus;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityResolution;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import com.frostwork.bgenhance.residency.runtime.UidPolicyScope;
import java.util.Objects;

/** Deterministic, platform-independent policy engine for all residency behaviors. */
public final class ResidencyDecisionEngine {
    private final RuntimeConfigSnapshot snapshot;
    private final ResidencyIdentityIndex identityIndex;
    private final long identityGeneration;

    public ResidencyDecisionEngine(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex identityIndex) {
        this(snapshot, identityIndex, 0L);
    }

    public ResidencyDecisionEngine(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex identityIndex,
            long identityGeneration) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.identityIndex = Objects.requireNonNull(identityIndex, "identityIndex");
        if (identityGeneration < 0L) {
            throw new IllegalArgumentException("identityGeneration < 0");
        }
        this.identityGeneration = identityGeneration;
    }

    public KillDecision decideKill(KillContext context) {
        return decideKill(context, "", true);
    }

    public KillDecision decideKill(
            KillContext context, String featureKey, boolean defaultEnabled) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(context, featureKey, defaultEnabled);
        if (!evaluation.active()) {
            return new KillDecision(
                    KillDecision.Action.ALLOW_SYSTEM,
                    KillDecision.Action.ALLOW_SYSTEM,
                    evaluation.trace());
        }

        boolean protectedPath = protects(evaluation.policy().killPolicy(), context.path());
        KillDecision.Action policyAction = protectedPath
                ? KillDecision.Action.BLOCK_ACTION
                : KillDecision.Action.ALLOW_SYSTEM;
        DecisionReason reason = protectedPath
                ? DecisionReason.POLICY_PROTECTS_KILL
                : DecisionReason.POLICY_ALLOWS_KILL;
        return finishKill(context, evaluation, policyAction, reason);
    }

    public FreezeDecision decideFreeze(FreezeContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(
                context, "", true, UidPolicyScope.forFreezeSource(context.source()));
        if (!evaluation.active()) {
            return new FreezeDecision(
                    FreezeDecision.Action.ALLOW_SYSTEM,
                    FreezeDecision.Action.ALLOW_SYSTEM,
                    0L,
                    0L,
                    evaluation.trace());
        }

        FreezePolicy policy = evaluation.policy().freezePolicy();
        FreezeDecision.Action action;
        DecisionReason reason;
        if (policy.fullExemption()) {
            action = FreezeDecision.Action.BLOCK_FREEZE;
            reason = DecisionReason.FULL_EXEMPTION;
        } else if (!policy.allows(context.source())) {
            action = FreezeDecision.Action.BLOCK_FREEZE;
            reason = DecisionReason.POLICY_BLOCKS_FREEZE;
        } else if (policy.customTiming()) {
            action = FreezeDecision.Action.APPLY_CUSTOM_TIMING;
            reason = DecisionReason.CUSTOM_FREEZE_TIMING;
        } else {
            action = FreezeDecision.Action.ALLOW_SYSTEM;
            reason = DecisionReason.POLICY_ALLOWS_FREEZE;
        }
        return finishFreeze(
                context,
                evaluation,
                action,
                policy.rToMDelayMs(),
                policy.mToFDelayMs(),
                reason);
    }

    public FreezeTimingDecision decideFreezeTiming(FreezeTimingContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(
                context, "", true, context.uidPolicyScope());
        if (!evaluation.active()) {
            return new FreezeTimingDecision(
                    FreezeTimingDecision.Action.FOLLOW_SYSTEM,
                    FreezeTimingDecision.Action.FOLLOW_SYSTEM,
                    0L,
                    evaluation.trace());
        }

        FreezePolicy policy = evaluation.policy().freezePolicy();
        if (policy.fullExemption()) {
            return finishFreezeTiming(
                    context,
                    evaluation,
                    FreezeTimingDecision.Action.FOLLOW_SYSTEM,
                    0L,
                    DecisionReason.FULL_EXEMPTION);
        }
        if (!policy.customTiming()) {
            return finishFreezeTiming(
                    context,
                    evaluation,
                    FreezeTimingDecision.Action.FOLLOW_SYSTEM,
                    0L,
                    DecisionReason.POLICY_FOLLOWS_FREEZE_TIMING);
        }

        long delayMs;
        switch (context.transition()) {
            case R_TO_M:
                delayMs = policy.rToMDelayMs();
                break;
            case M_TO_F:
                delayMs = policy.mToFDelayMs();
                break;
            default:
                throw new AssertionError(context.transition());
        }
        return finishFreezeTiming(
                context,
                evaluation,
                FreezeTimingDecision.Action.APPLY_CUSTOM_TIMING,
                delayMs,
                DecisionReason.CUSTOM_FREEZE_TIMING);
    }

    public FreezeExemptionDecision decideFreezeExemption(
            FreezeExemptionContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(
                context, "", true, context.uidPolicyScope());
        if (!evaluation.active()) {
            return new FreezeExemptionDecision(
                    FreezeExemptionDecision.Action.FOLLOW_SYSTEM,
                    FreezeExemptionDecision.Action.FOLLOW_SYSTEM,
                    evaluation.trace());
        }

        FreezePolicy freezePolicy = evaluation.policy().freezePolicy();
        boolean fullExemption = freezePolicy.fullExemption();
        boolean lcdOnExemption = context.kind() == FreezeExemptionContext.Kind.LCD_ON
                && freezePolicy.lcdOnExemption();
        FreezeExemptionDecision.Action action = fullExemption || lcdOnExemption
                ? FreezeExemptionDecision.Action.GRANT_EXEMPTION
                : FreezeExemptionDecision.Action.FOLLOW_SYSTEM;
        return finishFreezeExemption(
                context,
                evaluation,
                action,
                fullExemption
                        ? DecisionReason.FULL_EXEMPTION
                        : lcdOnExemption
                                ? DecisionReason.LCD_ON_EXEMPTION
                                : DecisionReason.POLICY_FOLLOWS_EXEMPTION);
    }

    public WakeDecision decideWake(WakeContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(
                context, "", true, UidPolicyScope.forWakeSource(context.source()));
        if (!evaluation.active()) {
            return new WakeDecision(
                    WakeDecision.Action.ALLOW_SYSTEM,
                    WakeDecision.Action.ALLOW_SYSTEM,
                    0L,
                    evaluation.trace());
        }

        AppResidencyPolicy appPolicy = evaluation.policy();
        if (appPolicy.freezePolicy().fullExemption()) {
            return finishWake(
                    context,
                    evaluation,
                    WakeDecision.Action.ALLOW_WAKE,
                    0L,
                    DecisionReason.FULL_EXEMPTION);
        }
        if (context.source().isAlwaysAllowed()) {
            return finishWake(
                    context,
                    evaluation,
                    WakeDecision.Action.ALLOW_SYSTEM,
                    0L,
                    DecisionReason.SAFE_WAKE_SOURCE);
        }

        WakeMode mode = appPolicy.wakePolicy().modeFor(context.source());
        long cooldownMs = cooldownFor(appPolicy, context.source());
        switch (mode) {
            case ALLOW:
                return finishWake(
                        context,
                        evaluation,
                        WakeDecision.Action.ALLOW_SYSTEM,
                        0L,
                        DecisionReason.WAKE_FOLLOWS_SYSTEM);
            case THROTTLE:
                return finishWake(
                        context,
                        evaluation,
                        WakeDecision.Action.THROTTLE_WAKE,
                        cooldownMs,
                        DecisionReason.WAKE_THROTTLED);
            case BLOCK:
                return finishWake(
                        context,
                        evaluation,
                        WakeDecision.Action.BLOCK_WAKE,
                        0L,
                        DecisionReason.WAKE_BLOCKED);
            default:
                throw new AssertionError(mode);
        }
    }

    public ResourceDecision decideResource(ResourceContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(context);
        if (!evaluation.active()) {
            return new ResourceDecision(
                    ResourceDecision.Action.ALLOW_SYSTEM,
                    ResourceDecision.Action.ALLOW_SYSTEM,
                    evaluation.trace());
        }

        ResourcePolicy policy = evaluation.policy().resourcePolicy();
        boolean keep = context.kind() == ResourceContext.Kind.NETWORK
                ? policy.keepNetwork()
                : policy.keeps(context.resourceType());
        ResourceDecision.Action action = keep
                ? ResourceDecision.Action.KEEP_RESOURCE
                : ResourceDecision.Action.ALLOW_SYSTEM;
        return finishResource(
                context,
                evaluation,
                action,
                keep ? DecisionReason.RESOURCE_KEPT : DecisionReason.RESOURCE_FOLLOWS_SYSTEM);
    }

    public RefreezeDecision decideRefreeze(RefreezeContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(context);
        if (!evaluation.active()) {
            return new RefreezeDecision(
                    RefreezeDecision.Action.ALLOW_SYSTEM,
                    RefreezeDecision.Action.ALLOW_SYSTEM,
                    0L,
                    evaluation.trace());
        }

        AppResidencyPolicy appPolicy = evaluation.policy();
        if (appPolicy.freezePolicy().fullExemption()) {
            return finishRefreeze(
                    context,
                    evaluation,
                    RefreezeDecision.Action.DISABLE_REFREEZE,
                    0L,
                    DecisionReason.FULL_EXEMPTION);
        }

        RefreezePolicy policy = appPolicy.refreezePolicy();
        RefreezeMode mode = policy.mode();
        switch (mode) {
            case SYSTEM:
                return finishRefreeze(
                        context,
                        evaluation,
                        RefreezeDecision.Action.ALLOW_SYSTEM,
                        0L,
                        DecisionReason.REFREEZE_FOLLOWS_SYSTEM);
            case AFTER_WAKE:
                return finishRefreeze(
                        context,
                        evaluation,
                        RefreezeDecision.Action.SCHEDULE_REFREEZE,
                        policy.delayFor(context.wakeSource()),
                        DecisionReason.REFREEZE_SCHEDULED);
            case DISABLED:
                return finishRefreeze(
                        context,
                        evaluation,
                        RefreezeDecision.Action.DISABLE_REFREEZE,
                        0L,
                        DecisionReason.REFREEZE_DISABLED);
            default:
                throw new AssertionError(mode);
        }
    }

    private Evaluation evaluate(ResidencyContext context) {
        return evaluate(context, "", true);
    }

    private Evaluation evaluate(
            ResidencyContext context, String featureKey, boolean defaultEnabled) {
        return evaluate(context, featureKey, defaultEnabled, null);
    }

    private Evaluation evaluate(
            ResidencyContext context,
            String featureKey,
            boolean defaultEnabled,
            UidPolicyScope uidPolicyScope) {
        ResidencyIdentityResolution notEvaluated =
                ResidencyIdentityResolution.notEvaluated(context.target());
        if (!snapshot.available()) {
            return Evaluation.stop(trace(
                    context,
                    notEvaluated,
                    DecisionDisposition.FAIL_OPEN,
                    DecisionReason.CONFIG_UNAVAILABLE));
        }
        if (!snapshot.masterEnabled()) {
            return Evaluation.stop(trace(
                    context,
                    notEvaluated,
                    DecisionDisposition.FAIL_OPEN,
                    DecisionReason.MASTER_DISABLED));
        }
        if (!snapshot.residencyEnabled()) {
            return Evaluation.stop(trace(
                    context,
                    notEvaluated,
                    DecisionDisposition.FAIL_OPEN,
                    DecisionReason.RESIDENCY_DISABLED));
        }
        if (featureKey != null
                && !featureKey.isEmpty()
                && !snapshot.featureEnabled(featureKey, defaultEnabled)) {
            return Evaluation.stop(trace(
                    context,
                    notEvaluated,
                    DecisionDisposition.PASS_THROUGH,
                    DecisionReason.FEATURE_DISABLED));
        }
        if (snapshot.policyRevision() != identityIndex.policyRevision()) {
            return Evaluation.stop(trace(
                    context,
                    notEvaluated,
                    DecisionDisposition.FAIL_OPEN,
                    DecisionReason.STALE_IDENTITY_INDEX));
        }

        ResidencyIdentityResolution identity = uidPolicyScope != null
                ? identityIndex.resolveUidScoped(context.target(), uidPolicyScope)
                : identityIndex.resolve(context.target());
        switch (identity.status()) {
            case UNKNOWN:
                return Evaluation.stop(trace(
                        context,
                        identity,
                        DecisionDisposition.FAIL_OPEN,
                        DecisionReason.IDENTITY_UNKNOWN));
            case UID_MISMATCH:
                return Evaluation.stop(trace(
                        context,
                        identity,
                        DecisionDisposition.FAIL_OPEN,
                        DecisionReason.IDENTITY_UID_MISMATCH));
            case SHARED_UID_CONFLICT:
                return Evaluation.stop(trace(
                        context,
                        identity,
                        DecisionDisposition.FAIL_OPEN,
                        DecisionReason.SHARED_UID_CONFLICT));
            case SYSTEM_UID:
                return Evaluation.stop(trace(
                        context,
                        identity,
                        DecisionDisposition.FAIL_OPEN,
                        DecisionReason.SYSTEM_UID));
            case UNMANAGED:
                return Evaluation.stop(trace(
                        context,
                        identity,
                        DecisionDisposition.PASS_THROUGH,
                        DecisionReason.UNMANAGED_APPLICATION));
            case EXACT_PACKAGE:
            case UID_SINGLE_RULE:
            case SHARED_UID_SAME_POLICY:
                break;
            case NOT_EVALUATED:
            default:
                return Evaluation.stop(trace(
                        context,
                        identity,
                        DecisionDisposition.FAIL_OPEN,
                        DecisionReason.IDENTITY_UNKNOWN));
        }

        AppResidencyPolicy policy = identity.policy().orElse(null);
        if (policy == null) {
            return Evaluation.stop(trace(
                    context,
                    identity,
                    DecisionDisposition.FAIL_OPEN,
                    DecisionReason.IDENTITY_UNKNOWN));
        }
        if (!policy.enabled()) {
            return Evaluation.stop(trace(
                    context,
                    identity,
                    DecisionDisposition.PASS_THROUGH,
                    DecisionReason.RULE_DISABLED));
        }
        if (policy.mode() == ResidencyMode.SYSTEM) {
            return Evaluation.stop(trace(
                    context,
                    identity,
                    DecisionDisposition.PASS_THROUGH,
                    DecisionReason.SYSTEM_MODE));
        }
        return Evaluation.active(policy, identity);
    }

    private KillDecision finishKill(
            KillContext context,
            Evaluation evaluation,
            KillDecision.Action policyAction,
            DecisionReason reason) {
        KillDecision.Action baseline = KillDecision.Action.ALLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        KillDecision.Action effective = effectiveAction(baseline, policyAction, disposition);
        return new KillDecision(
                effective,
                policyAction,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private FreezeDecision finishFreeze(
            FreezeContext context,
            Evaluation evaluation,
            FreezeDecision.Action policyAction,
            long rToMDelayMs,
            long mToFDelayMs,
            DecisionReason reason) {
        FreezeDecision.Action baseline = FreezeDecision.Action.ALLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        FreezeDecision.Action effective = effectiveAction(baseline, policyAction, disposition);
        return new FreezeDecision(
                effective,
                policyAction,
                rToMDelayMs,
                mToFDelayMs,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private FreezeTimingDecision finishFreezeTiming(
            FreezeTimingContext context,
            Evaluation evaluation,
            FreezeTimingDecision.Action policyAction,
            long delayMs,
            DecisionReason reason) {
        FreezeTimingDecision.Action baseline = FreezeTimingDecision.Action.FOLLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        FreezeTimingDecision.Action effective =
                effectiveAction(baseline, policyAction, disposition);
        return new FreezeTimingDecision(
                effective,
                policyAction,
                delayMs,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private FreezeExemptionDecision finishFreezeExemption(
            FreezeExemptionContext context,
            Evaluation evaluation,
            FreezeExemptionDecision.Action policyAction,
            DecisionReason reason) {
        FreezeExemptionDecision.Action baseline =
                FreezeExemptionDecision.Action.FOLLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        FreezeExemptionDecision.Action effective =
                effectiveAction(baseline, policyAction, disposition);
        return new FreezeExemptionDecision(
                effective,
                policyAction,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private WakeDecision finishWake(
            WakeContext context,
            Evaluation evaluation,
            WakeDecision.Action policyAction,
            long cooldownMs,
            DecisionReason reason) {
        WakeDecision.Action baseline = WakeDecision.Action.ALLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        WakeDecision.Action effective = effectiveAction(baseline, policyAction, disposition);
        return new WakeDecision(
                effective,
                policyAction,
                cooldownMs,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private ResourceDecision finishResource(
            ResourceContext context,
            Evaluation evaluation,
            ResourceDecision.Action policyAction,
            DecisionReason reason) {
        ResourceDecision.Action baseline = ResourceDecision.Action.ALLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        ResourceDecision.Action effective = effectiveAction(baseline, policyAction, disposition);
        return new ResourceDecision(
                effective,
                policyAction,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private RefreezeDecision finishRefreeze(
            RefreezeContext context,
            Evaluation evaluation,
            RefreezeDecision.Action policyAction,
            long delayMs,
            DecisionReason reason) {
        RefreezeDecision.Action baseline = RefreezeDecision.Action.ALLOW_SYSTEM;
        DecisionDisposition disposition = disposition(policyAction != baseline);
        RefreezeDecision.Action effective = effectiveAction(baseline, policyAction, disposition);
        return new RefreezeDecision(
                effective,
                policyAction,
                delayMs,
                trace(context, evaluation.identity(), disposition, reason));
    }

    private DecisionDisposition disposition(boolean changesBehavior) {
        if (!changesBehavior) return DecisionDisposition.PASS_THROUGH;
        return snapshot.dryRun() ? DecisionDisposition.DRY_RUN : DecisionDisposition.ENFORCED;
    }

    private static <T> T effectiveAction(
            T baseline,
            T policyAction,
            DecisionDisposition disposition) {
        return disposition == DecisionDisposition.ENFORCED ? policyAction : baseline;
    }

    private DecisionTrace trace(
            ResidencyContext context,
            ResidencyIdentityResolution identity,
            DecisionDisposition disposition,
            DecisionReason reason) {
        return new DecisionTrace(
                context,
                snapshot.configRevision(),
                snapshot.policyRevision(),
                identityIndex.policyRevision(),
                identityGeneration,
                identity,
                disposition,
                reason);
    }

    private static boolean protects(KillPolicy policy, KillContext.Path path) {
        switch (path) {
            case OGUARD_KILL: return policy.protectOguardKill();
            case OGUARD_FORCE_STOP: return policy.protectOguardForceStop();
            case ATHENA_PERIODIC: return policy.protectAthenaPeriodic();
            case FAST_CLEAR: return policy.protectFastClear();
            case TOURNAMENT: return policy.protectTournament();
            case MEMORY_GUARD: return policy.protectMemoryGuard();
            case NIRVANA: return policy.protectNirvana();
            case OSENSE_PER_PROCESS: return policy.protectOsensePerProcess();
            default: throw new AssertionError(path);
        }
    }

    private static long cooldownFor(AppResidencyPolicy policy, WakeSource source) {
        WakeChannelPolicy channel;
        if (source == WakeSource.PACKET) {
            channel = policy.wakePolicy().packet();
        } else if (source == WakeSource.ALARM) {
            channel = policy.wakePolicy().alarm();
        } else {
            return 0L;
        }
        return channel.cooldownMs();
    }

    public RuntimeConfigSnapshot snapshot() { return snapshot; }
    public ResidencyIdentityIndex identityIndex() { return identityIndex; }

    private static final class Evaluation {
        private final AppResidencyPolicy policy;
        private final ResidencyIdentityResolution identity;
        private final DecisionTrace trace;

        private Evaluation(
                AppResidencyPolicy policy,
                ResidencyIdentityResolution identity,
                DecisionTrace trace) {
            this.policy = policy;
            this.identity = identity;
            this.trace = trace;
        }

        static Evaluation active(
                AppResidencyPolicy policy,
                ResidencyIdentityResolution identity) {
            return new Evaluation(policy, identity, null);
        }

        static Evaluation stop(DecisionTrace trace) {
            return new Evaluation(null, trace.identity(), trace);
        }

        boolean active() { return policy != null; }
        AppResidencyPolicy policy() { return policy; }
        ResidencyIdentityResolution identity() { return identity; }
        DecisionTrace trace() { return trace; }
    }
}
