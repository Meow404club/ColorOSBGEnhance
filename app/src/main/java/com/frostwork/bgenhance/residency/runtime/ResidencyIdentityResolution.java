package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable identity resolution, including all candidates relevant to diagnostics. */
public final class ResidencyIdentityResolution {
    private final IdentityResolutionStatus status;
    private final ResidencyTarget target;
    private final String matchedPackageName;
    private final Set<String> candidatePackages;
    private final AppResidencyPolicy policy;

    ResidencyIdentityResolution(
            IdentityResolutionStatus status,
            ResidencyTarget target,
            String matchedPackageName,
            Set<String> candidatePackages,
            AppResidencyPolicy policy) {
        this.status = Objects.requireNonNull(status, "status");
        this.target = Objects.requireNonNull(target, "target");
        this.matchedPackageName = matchedPackageName;
        LinkedHashSet<String> copy = new LinkedHashSet<>(
                Objects.requireNonNull(candidatePackages, "candidatePackages"));
        if (copy.contains(null)) throw new NullPointerException("candidatePackages contains null");
        this.candidatePackages = Collections.unmodifiableSet(copy);
        this.policy = policy;
    }

    public static ResidencyIdentityResolution notEvaluated(ResidencyTarget target) {
        return new ResidencyIdentityResolution(
                IdentityResolutionStatus.NOT_EVALUATED,
                target,
                null,
                Collections.emptySet(),
                null);
    }

    public IdentityResolutionStatus status() { return status; }
    public ResidencyTarget target() { return target; }
    public Optional<String> matchedPackageName() { return Optional.ofNullable(matchedPackageName); }
    public Set<String> candidatePackages() { return candidatePackages; }
    public Optional<AppResidencyPolicy> policy() { return Optional.ofNullable(policy); }
    public boolean hasManagedPolicy() { return policy != null; }
}
