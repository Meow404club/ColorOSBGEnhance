package com.frostwork.bgenhance.residency.runtime;

import android.content.pm.PackageManager;
import android.os.Process;
import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
        return findUid(packageName, 0);
    }

    @Override
    public OptionalInt findUid(String packageName, int userId) {
        Objects.requireNonNull(packageName, "packageName");
        if (!AppInstanceKey.isRepresentableUserId(userId)) {
            throw new IllegalArgumentException(
                    "userId cannot form a non-negative UID: " + userId);
        }
        if (userId == 0) return findCallingUserUid(packageName);
        return findUidAsUser(packageName, userId);
    }

    /** Public lookup for the calling user, rejected when the UID belongs to another user. */
    private OptionalInt findCallingUserUid(String packageName) {
        try {
            return acceptUser(packageManager.getPackageUid(packageName, 0), 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return OptionalInt.empty();
        }
    }

    /**
     * Hidden PackageManager.getPackageUidAsUser(String, int, int). A missing package or
     * unknown user is not installed; the UID is never synthesized from an app id.
     */
    private OptionalInt findUidAsUser(String packageName, int userId) {
        Method method = packageUidAsUser();
        if (method == null) return OptionalInt.empty();
        try {
            Object value = method.invoke(packageManager, packageName, 0, userId);
            if (!(value instanceof Integer)) return OptionalInt.empty();
            return acceptUser(((Integer) value).intValue(), userId);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof PackageManager.NameNotFoundException
                    || cause instanceof IllegalArgumentException) {
                return OptionalInt.empty();
            }
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("getPackageUidAsUser failed", cause);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("getPackageUidAsUser inaccessible", error);
        }
    }

    private static OptionalInt acceptUser(int uid, int userId) {
        if (uid < 0 || uid / AppInstanceKey.PER_USER_RANGE != userId) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(uid);
    }

    private static Method packageUidAsUser() {
        try {
            Method method = PackageManager.class.getMethod(
                    "getPackageUidAsUser", String.class, int.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
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
