package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import java.util.Objects;

/** One immutable decision runtime published atomically to every residency hook. */
public final class ResidencyRuntimeState {
    public enum Status {
        CONFIG_UNAVAILABLE,
        IDENTITY_SOURCE_UNAVAILABLE,
        IDENTITY_REBUILD_PENDING,
        IDENTITY_REBUILD_FAILED,
        READY
    }

    private final RuntimeConfigSnapshot snapshot;
    private final ResidencyIdentityIndex identityIndex;
    private final ResidencyDecisionEngine decisionEngine;
    private final Status status;
    private final long generation;
    private final String error;

    ResidencyRuntimeState(
            RuntimeConfigSnapshot snapshot,
            ResidencyIdentityIndex identityIndex,
            Status status,
            long generation,
            String error) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.identityIndex = Objects.requireNonNull(identityIndex, "identityIndex");
        this.decisionEngine = new ResidencyDecisionEngine(snapshot, identityIndex);
        this.status = Objects.requireNonNull(status, "status");
        this.generation = generation;
        this.error = error == null ? "" : error;
    }

    public RuntimeConfigSnapshot snapshot() { return snapshot; }
    public ResidencyIdentityIndex identityIndex() { return identityIndex; }
    public ResidencyDecisionEngine decisionEngine() { return decisionEngine; }
    public Status status() { return status; }
    public long generation() { return generation; }
    public String error() { return error; }
    public boolean ready() { return status == Status.READY; }
}
