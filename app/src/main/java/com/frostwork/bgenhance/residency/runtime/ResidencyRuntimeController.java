package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates config and package identity snapshots without doing platform work on hook threads. */
public final class ResidencyRuntimeController {
    private final Executor identityExecutor;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<ResidencyRuntimeState> state =
            new AtomicReference<>(new ResidencyRuntimeState(
                    RuntimeConfigSnapshot.unavailable(0L),
                    ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    0L,
                    "not_initialized"));
    private PackageUidSource packageUidSource;

    public ResidencyRuntimeController(Executor identityExecutor) {
        this.identityExecutor = Objects.requireNonNull(identityExecutor, "identityExecutor");
    }

    public synchronized void updateConfig(RuntimeConfigSnapshot snapshot) {
        RuntimeConfigSnapshot next = Objects.requireNonNull(snapshot, "snapshot");
        long token = generation.incrementAndGet();
        ResidencyRuntimeState current = state.get();
        if (!next.available()) {
            publish(next, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    token, "config_unavailable");
            return;
        }

        ResidencyIdentityIndex currentIndex = current.identityIndex();
        if (!currentIndex.isInvalidated()
                && currentIndex.policyRevision() == next.policyRevision()
                && current.snapshot().policiesByPackage().equals(next.policiesByPackage())) {
            publish(next, currentIndex, ResidencyRuntimeState.Status.READY, token, "");
            return;
        }
        rebuild(next, token);
    }

    public synchronized void setPackageUidSource(PackageUidSource source) {
        packageUidSource = Objects.requireNonNull(source, "source");
        RuntimeConfigSnapshot snapshot = state.get().snapshot();
        long token = generation.incrementAndGet();
        if (!snapshot.available()) {
            publish(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    token, "config_unavailable");
            return;
        }
        rebuild(snapshot, token);
    }

    public synchronized void invalidateIdentities() {
        RuntimeConfigSnapshot snapshot = state.get().snapshot();
        long token = generation.incrementAndGet();
        if (!snapshot.available()) {
            publish(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.CONFIG_UNAVAILABLE,
                    token, "config_unavailable");
            return;
        }
        rebuild(snapshot, token);
    }

    private void rebuild(RuntimeConfigSnapshot snapshot, long token) {
        if (snapshot.policiesByPackage().isEmpty()) {
            ResidencyIdentityIndex empty = ResidencyIdentityIndex.build(
                    snapshot, Collections.emptyMap());
            publish(snapshot, empty, ResidencyRuntimeState.Status.READY, token, "");
            return;
        }
        PackageUidSource source = packageUidSource;
        if (source == null) {
            publish(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.IDENTITY_SOURCE_UNAVAILABLE,
                    token, "package_uid_source_unavailable");
            return;
        }
        publish(snapshot, ResidencyIdentityIndex.invalidated(),
                ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING,
                token, "");
        try {
            identityExecutor.execute(() -> buildIdentities(snapshot, source, token));
        } catch (Throwable error) {
            publishIfCurrent(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.IDENTITY_REBUILD_FAILED,
                    token, errorText(error));
        }
    }

    private void buildIdentities(
            RuntimeConfigSnapshot snapshot, PackageUidSource source, long token) {
        try {
            ResidencyIdentityIndex index =
                    new ResidencyIdentityIndexBuilder(source).build(snapshot);
            publishIfCurrent(snapshot, index,
                    ResidencyRuntimeState.Status.READY, token, "");
        } catch (Throwable error) {
            publishIfCurrent(snapshot, ResidencyIdentityIndex.invalidated(),
                    ResidencyRuntimeState.Status.IDENTITY_REBUILD_FAILED,
                    token, errorText(error));
        }
    }

    private synchronized void publishIfCurrent(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            ResidencyRuntimeState.Status status,
            long token,
            String error) {
        if (generation.get() != token) return;
        publish(snapshot, index, status, token, error);
    }

    private void publish(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex index,
            ResidencyRuntimeState.Status status,
            long token,
            String error) {
        state.set(new ResidencyRuntimeState(snapshot, index, status, token, error));
    }

    public ResidencyRuntimeState state() { return state.get(); }

    public KillDecision decideKill(KillContext context) {
        return state.get().decisionEngine().decideKill(context);
    }

    public FreezeDecision decideFreeze(FreezeContext context) {
        return state.get().decisionEngine().decideFreeze(context);
    }

    public WakeDecision decideWake(WakeContext context) {
        return state.get().decisionEngine().decideWake(context);
    }

    public ResourceDecision decideResource(ResourceContext context) {
        return state.get().decisionEngine().decideResource(context);
    }

    public RefreezeDecision decideRefreeze(RefreezeContext context) {
        return state.get().decisionEngine().decideRefreeze(context);
    }

    private static String errorText(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + message);
    }
}
