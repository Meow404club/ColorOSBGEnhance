package com.frostwork.bgenhance.residency.runtime;

/** Decides whether one package broadcast can affect the current managed identity index. */
public final class ResidencyPackageChangeFilter {
    private ResidencyPackageChangeFilter() {}

    public static boolean shouldInvalidate(
            String packageName, RuntimeConfigSnapshot snapshot) {
        if (snapshot == null) return true;
        return packageName == null
                || packageName.isEmpty()
                || snapshot.policiesByPackage().containsKey(packageName);
    }
}
