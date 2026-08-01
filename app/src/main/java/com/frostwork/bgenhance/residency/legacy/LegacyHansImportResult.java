package com.frostwork.bgenhance.residency.legacy;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fully validated import preview; applying it to module settings is a separate transaction. */
public final class LegacyHansImportResult {
    private final int sourceVersion;
    private final boolean sourceEnabled;
    private final long sourceRevision;
    private final List<AppResidencyPolicy> policies;

    LegacyHansImportResult(
            int sourceVersion,
            boolean sourceEnabled,
            long sourceRevision,
            List<AppResidencyPolicy> policies) {
        this.sourceVersion = sourceVersion;
        this.sourceEnabled = sourceEnabled;
        this.sourceRevision = sourceRevision;
        this.policies = Collections.unmodifiableList(new ArrayList<>(policies));
    }

    public int sourceVersion() { return sourceVersion; }
    public boolean sourceEnabled() { return sourceEnabled; }
    public long sourceRevision() { return sourceRevision; }
    public List<AppResidencyPolicy> policies() { return policies; }
}
