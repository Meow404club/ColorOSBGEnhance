package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.Objects;

/** One known vendor process-termination path. */
public final class KillContext extends ResidencyContext {
    public enum Path {
        OGUARD_KILL,
        OGUARD_FORCE_STOP,
        ATHENA_PERIODIC,
        FAST_CLEAR,
        TOURNAMENT,
        MEMORY_GUARD,
        NIRVANA,
        OSENSE_PER_PROCESS
    }

    private final Path path;

    public KillContext(ResidencyTarget target, Path path) {
        this(target, path, "", "");
    }

    public KillContext(
            ResidencyTarget target,
            Path path,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() { return path; }
}
