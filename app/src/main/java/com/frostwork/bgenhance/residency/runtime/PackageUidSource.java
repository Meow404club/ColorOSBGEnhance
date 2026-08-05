package com.frostwork.bgenhance.residency.runtime;

import java.util.OptionalInt;
import java.util.Set;

/** Resolves installed package names to Android UIDs without hiding runtime failures. */
public interface PackageUidSource {
    /** Whether UID membership queries have a system-wide, unfiltered package view. */
    boolean hasCompleteUidEnumeration();

    OptionalInt findUid(String packageName);

    /** Returns every installed package sharing this full UID. */
    Set<String> findPackagesForUid(int uid);
}
