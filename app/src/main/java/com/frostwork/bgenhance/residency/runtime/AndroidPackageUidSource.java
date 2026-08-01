package com.frostwork.bgenhance.residency.runtime;

import android.content.pm.PackageManager;
import java.util.Objects;
import java.util.OptionalInt;

/** Public PackageManager-backed UID source for a hook host process. */
public final class AndroidPackageUidSource implements PackageUidSource {
    private final PackageManager packageManager;

    public AndroidPackageUidSource(PackageManager packageManager) {
        this.packageManager = Objects.requireNonNull(packageManager, "packageManager");
    }

    @Override
    public OptionalInt findUid(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        try {
            return OptionalInt.of(packageManager.getPackageUid(packageName, 0));
        } catch (PackageManager.NameNotFoundException ignored) {
            return OptionalInt.empty();
        }
    }
}
