package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable configuration consumed by residency hooks. */
public final class RuntimeConfigSnapshot {
    private final boolean available;
    private final long configRevision;
    private final boolean masterEnabled;
    private final boolean residencyEnabled;
    private final boolean dryRun;
    private final Map<String, Boolean> featureFlags;
    private final long policyRevision;
    private final Map<AppInstanceKey, AppResidencyPolicy> policiesByInstance;

    private RuntimeConfigSnapshot(
            boolean available,
            long configRevision,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            Map<String, Boolean> featureFlags,
            long policyRevision,
            Map<AppInstanceKey, AppResidencyPolicy> policiesByInstance) {
        if (configRevision < 0L) throw new IllegalArgumentException("configRevision < 0");
        if (policyRevision < 0L) throw new IllegalArgumentException("policyRevision < 0");
        this.available = available;
        this.configRevision = configRevision;
        this.masterEnabled = masterEnabled;
        this.residencyEnabled = residencyEnabled;
        this.dryRun = dryRun;
        this.featureFlags = immutableFeatureMap(featureFlags);
        this.policyRevision = policyRevision;
        this.policiesByInstance = policiesByInstance;
    }

    public static RuntimeConfigSnapshot available(
            long configRevision,
            boolean masterEnabled,
            boolean dryRun,
            ResidencyPolicyDocument document) {
        return available(
                configRevision,
                masterEnabled,
                dryRun,
                Collections.emptyMap(),
                document);
    }

    public static RuntimeConfigSnapshot available(
            long configRevision,
            boolean masterEnabled,
            boolean dryRun,
            Map<String, Boolean> featureFlags,
            ResidencyPolicyDocument document) {
        Objects.requireNonNull(document, "document");
        return new RuntimeConfigSnapshot(
                true,
                configRevision,
                masterEnabled,
                document.enabled(),
                dryRun,
                featureFlags,
                document.policyRevision(),
                indexPolicies(document.rules()));
    }

    public static RuntimeConfigSnapshot available(
            long configRevision,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByEncodedInstance) {
        return available(
                configRevision,
                masterEnabled,
                residencyEnabled,
                dryRun,
                Collections.emptyMap(),
                policyRevision,
                policiesByEncodedInstance);
    }

    /**
     * Accepts one rule per {@link AppInstanceKey#encode()} value. User 0 uses the package
     * name itself. Every other user uses packageName, a NUL, and the decimal user id. A
     * package-name key does not stand in for a clone rule.
     */
    public static RuntimeConfigSnapshot available(
            long configRevision,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            Map<String, Boolean> featureFlags,
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByEncodedInstance) {
        return new RuntimeConfigSnapshot(
                true,
                configRevision,
                masterEnabled,
                residencyEnabled,
                dryRun,
                featureFlags,
                policyRevision,
                indexEncodedPolicies(policiesByEncodedInstance));
    }

    public static RuntimeConfigSnapshot unavailable(long configRevision) {
        return new RuntimeConfigSnapshot(
                false,
                configRevision,
                false,
                false,
                false,
                Collections.emptyMap(),
                0L,
                indexPolicies(Collections.emptyList()));
    }

    private static Map<String, Boolean> immutableFeatureMap(
            Map<String, Boolean> source) {
        Objects.requireNonNull(source, "featureFlags");
        Map<String, Boolean> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "feature flag key"),
                    Objects.requireNonNull(entry.getValue(), "feature flag value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<AppInstanceKey, AppResidencyPolicy> indexEncodedPolicies(
            Map<String, AppResidencyPolicy> source) {
        Objects.requireNonNull(source, "policiesByEncodedInstance");
        List<AppResidencyPolicy> policies = new ArrayList<>();
        for (Map.Entry<String, AppResidencyPolicy> entry : source.entrySet()) {
            String encoded = Objects.requireNonNull(entry.getKey(), "policy instance");
            AppResidencyPolicy policy = Objects.requireNonNull(entry.getValue(), "policy");
            if (!encoded.equals(policy.instanceKey().encode())) {
                throw new IllegalArgumentException(
                        "policy key does not match instance identity: " + encoded);
            }
            policies.add(policy);
        }
        return indexPolicies(policies);
    }

    private static Map<AppInstanceKey, AppResidencyPolicy> indexPolicies(
            Collection<AppResidencyPolicy> source) {
        Objects.requireNonNull(source, "policies");
        Map<AppInstanceKey, AppResidencyPolicy> copy = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : source) {
            AppResidencyPolicy value = Objects.requireNonNull(policy, "policy");
            AppInstanceKey key = value.instanceKey();
            AppResidencyPolicy previous = copy.put(key, value);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate residency policy for " + key.encode());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    public boolean available() { return available; }
    public long configRevision() { return configRevision; }
    public boolean masterEnabled() { return masterEnabled; }
    public boolean residencyEnabled() { return residencyEnabled; }
    public boolean dryRun() { return dryRun; }
    public Map<String, Boolean> featureFlags() { return featureFlags; }
    public boolean featureEnabled(String key, boolean defaultValue) {
        Boolean configured = featureFlags.get(key);
        return configured != null ? configured : defaultValue;
    }
    public long policyRevision() { return policyRevision; }

    /** Every configured package/user rule. Clone-only rules stay visible. */
    public Map<AppInstanceKey, AppResidencyPolicy> policiesByInstance() {
        return policiesByInstance;
    }
}
