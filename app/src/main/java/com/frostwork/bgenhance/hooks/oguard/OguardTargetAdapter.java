package com.frostwork.bgenhance.hooks.oguard;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;

/** Normalizes the package and UID carried by an OGuard terminal method. */
public final class OguardTargetAdapter {
    private OguardTargetAdapter() {}

    public static ResidencyTarget from(String packageName, int uid) {
        if (uid < 0) {
            return new ResidencyTarget(
                    null, ResidencyTarget.UNKNOWN_UID, ResidencyTarget.UNKNOWN_PID);
        }
        String safePackage = packageName == null || packageName.trim().isEmpty()
                ? null
                : packageName;
        return new ResidencyTarget(
                safePackage, uid, ResidencyTarget.UNKNOWN_PID);
    }
}
