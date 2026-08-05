package com.frostwork.bgenhance.hooks.osense;

import java.util.Objects;

/** Immutable process identity extracted from an OSense kill candidate. */
public final class OsenseProcessIdentity {
    private final int uid;
    private final int pid;
    private final String processName;
    private final String packageName;

    OsenseProcessIdentity(int uid, int pid, String processName, String packageName) {
        this.uid = uid;
        this.pid = pid;
        this.processName = Objects.requireNonNull(processName, "processName");
        this.packageName = Objects.requireNonNull(packageName, "packageName");
    }

    public int uid() {
        return uid;
    }

    public int pid() {
        return pid;
    }

    public String processName() {
        return processName;
    }

    public String packageName() {
        return packageName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OsenseProcessIdentity)) return false;
        OsenseProcessIdentity that = (OsenseProcessIdentity) other;
        return uid == that.uid
                && pid == that.pid
                && processName.equals(that.processName)
                && packageName.equals(that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, pid, processName, packageName);
    }
}
