package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** Builds a complete identity index from managed packages and their known UID siblings. */
public final class ResidencyIdentityIndexBuilder {
    private final PackageUidSource packageUidSource;

    public ResidencyIdentityIndexBuilder(PackageUidSource packageUidSource) {
        this.packageUidSource = Objects.requireNonNull(packageUidSource, "packageUidSource");
    }

    public ResidencyIdentityIndex build(RuntimeConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<AppInstanceKey, Integer> instanceUids = new LinkedHashMap<>();
        Map<String, Integer> packageAppIdSeeds = new LinkedHashMap<>();
        Map<Integer, Set<String>> verifiedUidPackages = new LinkedHashMap<>();
        Set<Integer> expandedUids = new HashSet<>();
        boolean completeUidEnumeration = packageUidSource.hasCompleteUidEnumeration();
        for (AppResidencyPolicy policy : snapshot.policiesByInstance().values()) {
            String packageName = policy.packageName();
            int userId = policy.userId();
            OptionalInt uid = Objects.requireNonNull(
                    packageUidSource.findUid(packageName, userId),
                    "packageUidSource result");
            if (!uid.isPresent()) continue;
            int resolvedUid = uid.getAsInt();
            if (resolvedUid < 0
                    || resolvedUid / AppInstanceKey.PER_USER_RANGE != userId) {
                continue;
            }
            Integer previousUid = instanceUids.put(policy.instanceKey(), resolvedUid);
            if (previousUid != null && previousUid.intValue() != resolvedUid) {
                throw new IllegalStateException(
                        "multiple UIDs for " + packageName + " user " + userId);
            }
            verifiedUidPackages
                    .computeIfAbsent(resolvedUid, ignored -> new LinkedHashSet<>())
                    .add(packageName);
            putPackageUid(packageAppIdSeeds, packageName, resolvedUid);
            if (!completeUidEnumeration) continue;
            Set<String> sharedPackages = verifiedUidPackages.get(resolvedUid);
            if (expandedUids.add(resolvedUid)) {
                Set<String> queriedPackages = Objects.requireNonNull(
                        packageUidSource.findPackagesForUid(resolvedUid),
                        "packageUidSource shared packages");
                if (queriedPackages.isEmpty() || !queriedPackages.contains(packageName)) {
                    throw new IllegalStateException(
                            "incomplete package enumeration for uid " + resolvedUid);
                }
                sharedPackages = new LinkedHashSet<>(queriedPackages);
                verifiedUidPackages.put(resolvedUid, sharedPackages);
            } else if (sharedPackages == null || !sharedPackages.contains(packageName)) {
                throw new IllegalStateException(
                        "inconsistent package enumeration for uid " + resolvedUid);
            }
            for (String sharedPackage : sharedPackages) {
                if (sharedPackage == null || sharedPackage.isEmpty()) {
                    throw new IllegalArgumentException("invalid shared UID package");
                }
                putPackageUid(packageAppIdSeeds, sharedPackage, resolvedUid);
            }
        }
        return ResidencyIdentityIndex.build(
                snapshot, packageAppIdSeeds, verifiedUidPackages);
    }

    private static void putPackageUid(
            Map<String, Integer> packageUids, String packageName, int uid) {
        if (uid < 0) throw new IllegalArgumentException("uid < 0 for " + packageName);
        Integer previous = packageUids.putIfAbsent(packageName, uid);
        if (previous != null
                && ResidencyIdentityIndex.appId(previous)
                != ResidencyIdentityIndex.appId(uid)) {
            throw new IllegalStateException("conflicting UID for " + packageName);
        }
    }
}
