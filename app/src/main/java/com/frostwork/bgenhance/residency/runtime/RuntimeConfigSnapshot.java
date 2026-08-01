package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable configuration consumed by residency hooks. */
public final class RuntimeConfigSnapshot {
    private final boolean available;
    private final long configRevision;
    private final boolean masterEnabled;
    private final boolean residencyEnabled;
    private final boolean dryRun;
    private final long policyRevision;
    private final Map<String, AppResidencyPolicy> policiesByPackage;

    private RuntimeConfigSnapshot(
            boolean available,
            long configRevision,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByPackage) {
        if (configRevision < 0L) throw new IllegalArgumentException("configRevision < 0");
        if (policyRevision < 0L) throw new IllegalArgumentException("policyRevision < 0");
        this.available = available;
        this.configRevision = configRevision;
        this.masterEnabled = masterEnabled;
        this.residencyEnabled = residencyEnabled;
        this.dryRun = dryRun;
        this.policyRevision = policyRevision;
        this.policiesByPackage = immutablePolicyMap(policiesByPackage);
    }

    public static RuntimeConfigSnapshot available(
            long configRevision,
            boolean masterEnabled,
            boolean dryRun,
            ResidencyPolicyDocument document) {
        Objects.requireNonNull(document, "document");
        Map<String, AppResidencyPolicy> policies = new LinkedHashMap<>();
        for (AppResidencyPolicy policy : document.rules()) {
            AppResidencyPolicy previous = policies.put(policy.packageName(), policy);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate residency policy for " + policy.packageName());
            }
        }
        return new RuntimeConfigSnapshot(
                true,
                configRevision,
                masterEnabled,
                document.enabled(),
                dryRun,
                document.policyRevision(),
                policies);
    }

    public static RuntimeConfigSnapshot available(
            long configRevision,
            boolean masterEnabled,
            boolean residencyEnabled,
            boolean dryRun,
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByPackage) {
        return new RuntimeConfigSnapshot(
                true,
                configRevision,
                masterEnabled,
                residencyEnabled,
                dryRun,
                policyRevision,
                policiesByPackage);
    }

    public static RuntimeConfigSnapshot unavailable(long configRevision) {
        return new RuntimeConfigSnapshot(
                false,
                configRevision,
                false,
                false,
                false,
                0L,
                Collections.emptyMap());
    }

    private static Map<String, AppResidencyPolicy> immutablePolicyMap(
            Map<String, AppResidencyPolicy> source) {
        Objects.requireNonNull(source, "policiesByPackage");
        Map<String, AppResidencyPolicy> copy = new LinkedHashMap<>();
        for (Map.Entry<String, AppResidencyPolicy> entry : source.entrySet()) {
            String packageName = Objects.requireNonNull(entry.getKey(), "policy packageName");
            AppResidencyPolicy policy = Objects.requireNonNull(entry.getValue(), "policy");
            if (!packageName.equals(policy.packageName())) {
                throw new IllegalArgumentException(
                        "policy key does not match packageName: " + packageName);
            }
            copy.put(packageName, policy);
        }
        return Collections.unmodifiableMap(copy);
    }

    public boolean available() { return available; }
    public long configRevision() { return configRevision; }
    public boolean masterEnabled() { return masterEnabled; }
    public boolean residencyEnabled() { return residencyEnabled; }
    public boolean dryRun() { return dryRun; }
    public long policyRevision() { return policyRevision; }
    public Map<String, AppResidencyPolicy> policiesByPackage() { return policiesByPackage; }
    public AppResidencyPolicy policyForPackage(String packageName) {
        return policiesByPackage.get(packageName);
    }
}
