package com.frostwork.bgenhance.residency.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/** Builds a complete identity index from only the packages managed by one snapshot. */
public final class ResidencyIdentityIndexBuilder {
    private final PackageUidSource packageUidSource;

    public ResidencyIdentityIndexBuilder(PackageUidSource packageUidSource) {
        this.packageUidSource = Objects.requireNonNull(packageUidSource, "packageUidSource");
    }

    public ResidencyIdentityIndex build(RuntimeConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, Integer> resolvedPackageUids = new LinkedHashMap<>();
        for (String packageName : snapshot.policiesByPackage().keySet()) {
            OptionalInt uid = Objects.requireNonNull(
                    packageUidSource.findUid(packageName), "packageUidSource result");
            if (uid.isPresent()) resolvedPackageUids.put(packageName, uid.getAsInt());
        }
        return ResidencyIdentityIndex.build(snapshot, resolvedPackageUids);
    }
}
