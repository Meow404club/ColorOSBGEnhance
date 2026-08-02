package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Classifies packages that must not be changed from the ordinary application UI. */
public final class ResidencyAppSafety {
    private static final int FIRST_APPLICATION_UID = 10_000;
    private static final int PER_USER_RANGE = 100_000;
    private static final Set<String> CRITICAL_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "android",
                    "com.android.packageinstaller",
                    "com.android.permissioncontroller",
                    "com.android.phone",
                    "com.android.providers.settings",
                    "com.android.settings",
                    "com.android.systemui",
                    "com.google.android.packageinstaller",
                    "com.google.android.permissioncontroller",
                    "com.oplus.athena",
                    "com.oplus.permissioncontroller",
                    "com.oplus.safecenter",
                    "com.oplus.securitypermission",
                    "com.oplus.systemui")));

    private ResidencyAppSafety() {}

    public enum Restriction {
        NONE,
        NOT_INSTALLED,
        MODULE,
        CORE_SYSTEM,
        PERSISTENT,
        CRITICAL_PACKAGE,
        SHARED_UID,
        IDENTITY_UNAVAILABLE
    }

    public static final class Result {
        private final Restriction restriction;

        private Result(Restriction restriction) {
            this.restriction = Objects.requireNonNull(restriction, "restriction");
        }

        public static Result allowed() {
            return new Result(Restriction.NONE);
        }

        public static Result restricted(Restriction restriction) {
            if (restriction == Restriction.NONE) {
                throw new IllegalArgumentException("NONE is not a restriction");
            }
            return new Result(restriction);
        }

        public Restriction restriction() {
            return restriction;
        }

        public boolean isRestricted() {
            return restriction != Restriction.NONE;
        }
    }

    public static Result inspect(Context context, String packageName) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(packageName, "packageName");
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return inspect(context, info);
        } catch (PackageManager.NameNotFoundException exception) {
            return Result.restricted(Restriction.NOT_INSTALLED);
        } catch (RuntimeException exception) {
            return Result.restricted(Restriction.IDENTITY_UNAVAILABLE);
        }
    }

    static Result inspect(Context context, ApplicationInfo info) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(info, "info");
        String packageName = Objects.requireNonNull(info.packageName, "info.packageName");
        try {
            Result direct = classify(
                    packageName, info.uid, info.flags, context.getPackageName());
            if (direct.isRestricted()) return direct;

            String[] uidPackages = context.getPackageManager().getPackagesForUid(info.uid);
            if (uidPackages == null || uidPackages.length == 0) {
                return Result.restricted(Restriction.IDENTITY_UNAVAILABLE);
            }
            Set<String> uniquePackages = new HashSet<>(Arrays.asList(uidPackages));
            if (!uniquePackages.contains(packageName)) {
                return Result.restricted(Restriction.IDENTITY_UNAVAILABLE);
            }
            if (uniquePackages.size() > 1) {
                return Result.restricted(Restriction.SHARED_UID);
            }
            return Result.allowed();
        } catch (RuntimeException exception) {
            return Result.restricted(Restriction.IDENTITY_UNAVAILABLE);
        }
    }

    static Result classify(String packageName, int uid, int flags, String modulePackageName) {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(modulePackageName, "modulePackageName");
        if (packageName.equals(modulePackageName)) {
            return Result.restricted(Restriction.MODULE);
        }
        int appId = uid < 0 ? uid : uid % PER_USER_RANGE;
        if (appId < FIRST_APPLICATION_UID) {
            return Result.restricted(Restriction.CORE_SYSTEM);
        }
        if ((flags & ApplicationInfo.FLAG_PERSISTENT) != 0) {
            return Result.restricted(Restriction.PERSISTENT);
        }
        if (CRITICAL_PACKAGES.contains(packageName)) {
            return Result.restricted(Restriction.CRITICAL_PACKAGE);
        }
        return Result.allowed();
    }
}
