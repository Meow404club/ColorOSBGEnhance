package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Package and UID identity index built for one immutable policy revision. */
public final class ResidencyIdentityIndex {
    private static final int PER_USER_RANGE = 100_000;
    private static final int FIRST_APPLICATION_UID = 10_000;

    private final long policyRevision;
    private final Map<String, AppResidencyPolicy> policiesByPackage;
    private final Map<String, Integer> packageUids;
    private final Map<Integer, Set<String>> uidPackages;

    private ResidencyIdentityIndex(
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByPackage,
            Map<String, Integer> packageUids,
            Map<Integer, Set<String>> uidPackages) {
        this.policyRevision = policyRevision;
        this.policiesByPackage = policiesByPackage;
        this.packageUids = packageUids;
        this.uidPackages = uidPackages;
    }

    public static ResidencyIdentityIndex build(
            RuntimeConfigSnapshot snapshot,
            Map<String, Integer> packageUids) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(packageUids, "packageUids");

        Map<String, Integer> packageUidCopy = new TreeMap<>();
        Map<Integer, Set<String>> mutableUidPackages = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : packageUids.entrySet()) {
            String packageName = Objects.requireNonNull(entry.getKey(), "packageName");
            Integer uid = Objects.requireNonNull(entry.getValue(), "uid");
            if (packageName.isEmpty()) throw new IllegalArgumentException("empty packageName");
            if (uid < 0) throw new IllegalArgumentException("uid < 0 for " + packageName);
            packageUidCopy.put(packageName, uid);
            mutableUidPackages
                    .computeIfAbsent(uid, ignored -> new TreeSet<>())
                    .add(packageName);
        }

        Map<Integer, Set<String>> immutableUidPackages = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : mutableUidPackages.entrySet()) {
            immutableUidPackages.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }

        return new ResidencyIdentityIndex(
                snapshot.policyRevision(),
                snapshot.policiesByPackage(),
                Collections.unmodifiableMap(new LinkedHashMap<>(packageUidCopy)),
                Collections.unmodifiableMap(immutableUidPackages));
    }

    public ResidencyIdentityResolution resolve(ResidencyTarget target) {
        Objects.requireNonNull(target, "target");
        String packageName = target.packageName();
        Integer indexedUid = packageName == null ? null : packageUids.get(packageName);

        if (target.hasUid() && indexedUid != null && target.uid() != indexedUid) {
            return resolution(
                    IdentityResolutionStatus.UID_MISMATCH,
                    target,
                    null,
                    Collections.singleton(packageName),
                    null);
        }

        int effectiveUid = target.hasUid()
                ? target.uid()
                : indexedUid == null ? ResidencyTarget.UNKNOWN_UID : indexedUid;
        if (effectiveUid != ResidencyTarget.UNKNOWN_UID && isSystemUid(effectiveUid)) {
            return resolution(
                    IdentityResolutionStatus.SYSTEM_UID,
                    target,
                    null,
                    packageName == null
                            ? packagesForUid(effectiveUid)
                            : Collections.singleton(packageName),
                    null);
        }

        if (packageName != null) {
            AppResidencyPolicy policy = policiesByPackage.get(packageName);
            if (policy == null) {
                return resolution(
                        IdentityResolutionStatus.UNMANAGED,
                        target,
                        null,
                        Collections.singleton(packageName),
                        null);
            }
            return resolution(
                    IdentityResolutionStatus.EXACT_PACKAGE,
                    target,
                    packageName,
                    Collections.singleton(packageName),
                    policy);
        }

        if (!target.hasUid()) {
            return resolution(
                    IdentityResolutionStatus.UNKNOWN,
                    target,
                    null,
                    Collections.emptySet(),
                    null);
        }

        Set<String> uidCandidates = uidPackages.get(target.uid());
        if (uidCandidates == null || uidCandidates.isEmpty()) {
            return resolution(
                    IdentityResolutionStatus.UNKNOWN,
                    target,
                    null,
                    Collections.emptySet(),
                    null);
        }

        List<AppResidencyPolicy> managedPolicies = new ArrayList<>();
        for (String candidate : uidCandidates) {
            AppResidencyPolicy policy = policiesByPackage.get(candidate);
            if (policy != null) managedPolicies.add(policy);
        }
        if (managedPolicies.isEmpty()) {
            return resolution(
                    IdentityResolutionStatus.UNMANAGED,
                    target,
                    null,
                    uidCandidates,
                    null);
        }
        if (managedPolicies.size() == 1) {
            AppResidencyPolicy policy = managedPolicies.get(0);
            return resolution(
                    IdentityResolutionStatus.UID_SINGLE_RULE,
                    target,
                    policy.packageName(),
                    uidCandidates,
                    policy);
        }

        AppResidencyPolicy representative = managedPolicies.get(0);
        for (int i = 1; i < managedPolicies.size(); i++) {
            if (!sameEffectivePolicy(representative, managedPolicies.get(i))) {
                return resolution(
                        IdentityResolutionStatus.SHARED_UID_CONFLICT,
                        target,
                        null,
                        uidCandidates,
                        null);
            }
        }
        return resolution(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                target,
                representative.packageName(),
                uidCandidates,
                representative);
    }

    private Set<String> packagesForUid(int uid) {
        Set<String> packages = uidPackages.get(uid);
        return packages == null ? Collections.emptySet() : packages;
    }

    private static ResidencyIdentityResolution resolution(
            IdentityResolutionStatus status,
            ResidencyTarget target,
            String matchedPackageName,
            Set<String> candidates,
            AppResidencyPolicy policy) {
        return new ResidencyIdentityResolution(
                status, target, matchedPackageName, candidates, policy);
    }

    private static boolean sameEffectivePolicy(
            AppResidencyPolicy first,
            AppResidencyPolicy second) {
        boolean firstSystemManaged = first.mode() == ResidencyMode.SYSTEM;
        boolean secondSystemManaged = second.mode() == ResidencyMode.SYSTEM;
        if (first.enabled() != second.enabled()
                || firstSystemManaged != secondSystemManaged) {
            return false;
        }
        if (!first.enabled() || firstSystemManaged) return true;
        return first.killPolicy().equals(second.killPolicy())
                && first.freezePolicy().equals(second.freezePolicy())
                && first.wakePolicy().equals(second.wakePolicy())
                && first.resourcePolicy().equals(second.resourcePolicy())
                && first.refreezePolicy().equals(second.refreezePolicy());
    }

    public static boolean isSystemUid(int uid) {
        if (uid < 0) return false;
        return uid % PER_USER_RANGE < FIRST_APPLICATION_UID;
    }

    public long policyRevision() { return policyRevision; }
    public Map<String, Integer> packageUids() { return packageUids; }
    public Map<Integer, Set<String>> uidPackages() { return uidPackages; }
}
