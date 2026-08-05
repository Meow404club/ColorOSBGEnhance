package com.frostwork.bgenhance.residency.runtime;

import android.content.pm.PackageManager;
import android.os.Process;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** Public PackageManager-backed UID source for a hook host process. */
public final class AndroidPackageUidSource implements PackageUidSource {
    private final PackageManager packageManager;

    public AndroidPackageUidSource(PackageManager packageManager) {
        this.packageManager = Objects.requireNonNull(packageManager, "packageManager");
    }

    @Override
    public boolean hasCompleteUidEnumeration() {
        return Process.myUid() == Process.SYSTEM_UID;
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

    @Override
    public Set<String> findPackagesForUid(int uid) {
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return Collections.emptySet();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String packageName : packages) {
            if (packageName != null && !packageName.isEmpty()) result.add(packageName);
        }
        return Collections.unmodifiableSet(result);
    }
}
