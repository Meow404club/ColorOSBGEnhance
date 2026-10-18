package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import java.util.OptionalInt;
import java.util.Set;

/** Resolves installed package names to Android UIDs without hiding runtime failures. */
public interface PackageUidSource {
    /** Whether UID membership queries have a system-wide, unfiltered package view. */
    boolean hasCompleteUidEnumeration();

    /** UID of the package for the calling user. Compatible with existing callers. */
    OptionalInt findUid(String packageName);

    /**
     * UID of one package in one Android user. The default keeps user 0 on
     * {@link #findUid(String)} and reports every other user as not installed.
     */
    default OptionalInt findUid(String packageName, int userId) {
        if (packageName == null) throw new NullPointerException("packageName");
        if (!AppInstanceKey.isRepresentableUserId(userId)) {
            throw new IllegalArgumentException(
                    "userId cannot form a non-negative UID: " + userId);
        }
        if (userId == 0) return findUid(packageName);
        return OptionalInt.empty();
    }

    /** Returns every installed package sharing this full UID. */
    Set<String> findPackagesForUid(int uid);
}
