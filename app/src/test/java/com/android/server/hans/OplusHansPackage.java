package com.android.server.hans;

/** Minimal firmware-shaped fixture for HANS package reflection tests. */
public class OplusHansPackage {
    private final int uid;
    private final String pkgName;
    private final boolean frozen;
    private final boolean isSuperFRZMode;
    private final Object mLock;

    public OplusHansPackage(int uid, String packageName, boolean superFreezeMode) {
        this(uid, packageName, false, superFreezeMode, new Object());
    }

    public OplusHansPackage(
            int uid,
            String packageName,
            boolean frozen,
            boolean superFreezeMode,
            Object lock) {
        this.uid = uid;
        this.pkgName = packageName;
        this.frozen = frozen;
        this.isSuperFRZMode = superFreezeMode;
        this.mLock = lock;
    }

    public int getUid() {
        return uid;
    }

    public String getPkgName() {
        return pkgName;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public boolean getSuperFRZMode() {
        return isSuperFRZMode;
    }

    public Object getLock() {
        return mLock;
    }
}
