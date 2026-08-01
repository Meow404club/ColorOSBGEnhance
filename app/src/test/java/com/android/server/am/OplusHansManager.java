package com.android.server.am;

/** Firmware-shaped exemption target with overloads that must remain unsupported. */
public class OplusHansManager {
    public boolean hansFreeze(int uid, String reason) {
        return true;
    }

    public boolean hansFreeze(int uid, String reason, boolean ignoreImportance) {
        return true;
    }

    private void enterSuperFreeze(int uid) {}

    public boolean isHansCoreApp(int uid, String packageName) {
        return false;
    }

    public boolean isLcdOnNonRestrictPkg(int uid, String packageName) {
        return false;
    }

    public static boolean isHansCoreApp(Integer uid, String packageName) {
        return true;
    }

    public static boolean isLcdOnNonRestrictPkg(Integer uid, String packageName) {
        return true;
    }

    public int isHansCoreApp(int uid, CharSequence packageName) {
        return -1;
    }

    public int isLcdOnNonRestrictPkg(int uid, CharSequence packageName) {
        return -1;
    }
}
