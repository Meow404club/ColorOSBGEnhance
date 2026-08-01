package com.frostwork.bgenhance.residency.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Versioned, package-name keyed residency policy document. */
public final class ResidencyPolicyDocument {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final int schemaVersion;
    private final long policyRevision;
    private final boolean enabled;
    private final ResidencyMode defaultNewRuleMode;
    private final List<AppResidencyPolicy> rules;

    public ResidencyPolicyDocument(
            int schemaVersion,
            long policyRevision,
            boolean enabled,
            ResidencyMode defaultNewRuleMode,
            List<AppResidencyPolicy> rules) {
        this.schemaVersion = schemaVersion;
        this.policyRevision = policyRevision;
        this.enabled = enabled;
        this.defaultNewRuleMode = Objects.requireNonNull(
                defaultNewRuleMode, "defaultNewRuleMode");
        List<AppResidencyPolicy> copy = rules == null
                ? Collections.emptyList() : new ArrayList<>(rules);
        if (copy.contains(null)) throw new NullPointerException("rules contains null");
        this.rules = Collections.unmodifiableList(copy);
    }

    public static ResidencyPolicyDocument empty() {
        return new ResidencyPolicyDocument(CURRENT_SCHEMA_VERSION, 0L, true,
                ResidencyMode.BALANCED, Collections.emptyList());
    }

    public int schemaVersion() { return schemaVersion; }
    public long policyRevision() { return policyRevision; }
    public boolean enabled() { return enabled; }
    public ResidencyMode defaultNewRuleMode() { return defaultNewRuleMode; }
    public List<AppResidencyPolicy> rules() { return rules; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ResidencyPolicyDocument)) return false;
        ResidencyPolicyDocument that = (ResidencyPolicyDocument) other;
        return schemaVersion == that.schemaVersion
                && policyRevision == that.policyRevision
                && enabled == that.enabled
                && defaultNewRuleMode == that.defaultNewRuleMode
                && rules.equals(that.rules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, policyRevision, enabled, defaultNewRuleMode, rules);
    }
}
