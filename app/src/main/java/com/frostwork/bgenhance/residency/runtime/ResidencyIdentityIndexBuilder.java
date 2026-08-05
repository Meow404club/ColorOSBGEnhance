package com.frostwork.bgenhance.residency.runtime;

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
        Map<String, Integer> resolvedPackageUids = new LinkedHashMap<>();
        Map<Integer, Set<String>> verifiedUidPackages = new LinkedHashMap<>();
        Set<Integer> expandedUids = new HashSet<>();
        boolean completeUidEnumeration = packageUidSource.hasCompleteUidEnumeration();
        for (String packageName : snapshot.policiesByPackage().keySet()) {
            OptionalInt uid = Objects.requireNonNull(
                    packageUidSource.findUid(packageName), "packageUidSource result");
            if (!uid.isPresent()) continue;
            int resolvedUid = uid.getAsInt();
            putPackageUid(resolvedPackageUids, packageName, resolvedUid);
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
                putPackageUid(resolvedPackageUids, sharedPackage, resolvedUid);
            }
        }
        return ResidencyIdentityIndex.build(
                snapshot, resolvedPackageUids, verifiedUidPackages);
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
