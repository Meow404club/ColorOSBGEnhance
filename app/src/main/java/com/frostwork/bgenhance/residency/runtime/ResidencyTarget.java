package com.frostwork.bgenhance.residency.runtime;

import java.util.Objects;

/** Identity hints extracted from a hook invocation. */
public final class ResidencyTarget {
    public static final int UNKNOWN_UID = -1;
    public static final int UNKNOWN_PID = -1;

    private final String packageName;
    private final int uid;
    private final int pid;

    public ResidencyTarget(String packageName, int uid, int pid) {
        if (uid < UNKNOWN_UID) throw new IllegalArgumentException("uid < -1");
        if (pid < UNKNOWN_PID) throw new IllegalArgumentException("pid < -1");
        this.packageName = packageName == null || packageName.isEmpty() ? null : packageName;
        this.uid = uid;
        this.pid = pid;
    }

    public static ResidencyTarget forPackage(String packageName) {
        return new ResidencyTarget(
                Objects.requireNonNull(packageName, "packageName"), UNKNOWN_UID, UNKNOWN_PID);
    }

    public static ResidencyTarget forUid(int uid) {
        return new ResidencyTarget(null, uid, UNKNOWN_PID);
    }

    public String packageName() { return packageName; }
    public int uid() { return uid; }
    public int pid() { return pid; }
    public boolean hasPackageName() { return packageName != null; }
    public boolean hasUid() { return uid != UNKNOWN_UID; }
    public boolean hasPid() { return pid != UNKNOWN_PID; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ResidencyTarget)) return false;
        ResidencyTarget that = (ResidencyTarget) other;
        return uid == that.uid && pid == that.pid && Objects.equals(packageName, that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, uid, pid);
    }
}
