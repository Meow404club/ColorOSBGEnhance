package com.android.server.am;

import com.android.server.hans.OplusHansPackage;

/** Firmware-shaped exemption target with overloads that must remain unsupported. */
public class OplusHansManager {
    public boolean hansFreeze(int uid, String reason) {
        return hansFreeze(uid, reason, false);
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

    public void unfreezeForKernel(
            int type,
            int callerPid,
            int callerUid,
            int targetPid,
            int targetUid,
            String rpcName,
            int code) {}

    public void unfreezeForKernel(
            int type, int callerPid, int targetUid, String rpcName, int code) {}

    public static void unfreezeForKernel(
            Integer type,
            int callerPid,
            int callerUid,
            int targetPid,
            int targetUid,
            String rpcName,
            int code) {}

    public boolean unfreezeForKernel(
            int type,
            int callerPid,
            int callerUid,
            int targetPid,
            int targetUid,
            CharSequence rpcName,
            int code) {
        return false;
    }

    public void unfreezeForKernel(
            int type,
            int callerPid,
            int callerUid,
            int targetPid,
            long targetUid,
            String rpcName,
            int code) {}

    public boolean checkAlarmIfRestricted(
            int uid, String packageName, String action) {
        return false;
    }

    public static boolean checkAlarmIfRestricted(
            Integer uid, String packageName, String action) {
        return true;
    }

    public int checkAlarmIfRestricted(
            int uid, CharSequence packageName, String action) {
        return -1;
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

    public boolean tryProxyWakeLock(OplusHansPackage hansPackage) {
        return true;
    }

    public static boolean tryProxyWakeLock(Object hansPackage) {
        return false;
    }

    public int tryProxyWakeLock(OplusHansPackage hansPackage, int ignored) {
        return -1;
    }

    public void nwPowerSetFirewall(
            OplusHansPackage hansPackage, boolean allow, int allowType, int scene) {}

    public static void nwPowerSetFirewall(
            Object hansPackage, boolean allow, int allowType, int scene) {}

    public boolean nwPowerSetFirewall(
            OplusHansPackage hansPackage, Boolean allow, int allowType, int scene) {
        return false;
    }

    public boolean restrictRStateNonKeyProcsNet(
            String importanceReason, int uid, String packageName) {
        return false;
    }

    public static boolean restrictRStateNonKeyProcsNet(
            CharSequence importanceReason, int uid, String packageName) {
        return true;
    }

    public int restrictRStateNonKeyProcsNet(
            String importanceReason, Integer uid, String packageName) {
        return -1;
    }
}
