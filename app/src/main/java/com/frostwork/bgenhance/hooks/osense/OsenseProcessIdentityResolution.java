package com.frostwork.bgenhance.hooks.osense;

import java.util.Objects;
import java.util.Optional;

/** Explicit resolved or fail-open result from OSense process identity extraction. */
public final class OsenseProcessIdentityResolution {
    private final OsenseProcessIdentity identity;
    private final OsenseProcessIdentityFailure failure;

    private OsenseProcessIdentityResolution(
            OsenseProcessIdentity identity, OsenseProcessIdentityFailure failure) {
        this.identity = identity;
        this.failure = failure;
    }

    static OsenseProcessIdentityResolution resolved(OsenseProcessIdentity identity) {
        return new OsenseProcessIdentityResolution(
                Objects.requireNonNull(identity, "identity"), null);
    }

    static OsenseProcessIdentityResolution unresolved(OsenseProcessIdentityFailure failure) {
        return new OsenseProcessIdentityResolution(
                null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean isResolved() {
        return identity != null;
    }

    public boolean shouldFailOpen() {
        return !isResolved();
    }

    public Optional<OsenseProcessIdentity> identity() {
        return Optional.ofNullable(identity);
    }

    public Optional<OsenseProcessIdentityFailure> failure() {
        return Optional.ofNullable(failure);
    }
}
