package com.frostwork.bgenhance.residency.runtime;

import java.util.OptionalInt;

/** Resolves installed package names to Android UIDs without hiding runtime failures. */
public interface PackageUidSource {
    OptionalInt findUid(String packageName);
}
