package com.frostwork.bgenhance.residency.runtime;

/** Decides whether one package broadcast can affect shared-UID identity completeness. */
public final class ResidencyPackageChangeFilter {
    private ResidencyPackageChangeFilter() {}

    public static boolean shouldInvalidate(
            String packageName, RuntimeConfigSnapshot snapshot) {
        if (snapshot == null) return true;
        return !snapshot.policiesByInstance().isEmpty();
    }
}
