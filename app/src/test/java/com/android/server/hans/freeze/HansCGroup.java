package com.android.server.hans.freeze;

import com.android.server.hans.OplusHansPackage;

/** Firmware-shaped CGroup target containing both package and PID overload families. */
public class HansCGroup {
    public void FastFreezeEnter(int uid) {
        // Detection-only fixture: this cgroup v2 path is not covered by the object gate.
    }

    public boolean hansFreezeLocked(OplusHansPackage hansPackage, String from) {
        return true;
    }

    public boolean hansUnfreezeLocked(
            OplusHansPackage hansPackage, String reason, String componentInfo) {
        return true;
    }

    public boolean hansFreezeLocked(int pid, String processName, String from) {
        return true;
    }

    public boolean hansUnfreezeLocked(
            int pid, String processName, String reason, String componentInfo) {
        return true;
    }

    public static boolean hansFreezeLocked(Object target, String from) {
        return false;
    }

    public static boolean hansUnfreezeLocked(Object target, String reason, String componentInfo) {
        return false;
    }

    public int hansUnfreezeLocked(
            OplusHansPackage target, String reason, CharSequence componentInfo) {
        return -1;
    }

    public boolean hansUnfreezeLocked(
            Object target, String reason, CharSequence componentInfo) {
        return false;
    }

    public static void FastFreezeEnter(Integer uid) {}

    public boolean FastFreezeEnter(long uid) {
        return false;
    }
}
