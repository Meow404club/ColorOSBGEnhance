package com.android.server.am;

import com.android.server.hans.OplusHansPackage;

/** Firmware-shaped exemption target with overloads that must remain unsupported. */
public class OplusHansManager {
    private static final OplusHansManager INSTANCE = new OplusHansManager();
    private boolean ready = true;
    private boolean unfreezeResult = true;
    private int unfreezeCalls;
    private int lastUnfreezeUid = -1;
    private String lastUnfreezeReason;
    private String lastUnfreezeComponent;

    public static OplusHansManager getInstance() {
        return INSTANCE;
    }

    public static Object getInstance(Object ignored) {
        return null;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isReady(int ignored) {
        return false;
    }

    public static boolean isReady(Boolean ignored) {
        return false;
    }

    public boolean hansUnFreeze(int uid, String reason, String component) {
        unfreezeCalls++;
        lastUnfreezeUid = uid;
        lastUnfreezeReason = reason;
        lastUnfreezeComponent = component;
        return unfreezeResult;
    }

    public static boolean hansUnFreeze(
            Integer uid, String reason, String component) {
        return false;
    }

    public int hansUnFreeze(int uid, CharSequence reason, String component) {
        return -1;
    }

    public static void resetActiveRecoveryFixture() {
        INSTANCE.ready = true;
        INSTANCE.unfreezeResult = true;
        INSTANCE.unfreezeCalls = 0;
        INSTANCE.lastUnfreezeUid = -1;
        INSTANCE.lastUnfreezeReason = null;
        INSTANCE.lastUnfreezeComponent = null;
    }

    public static void setReadyForTest(boolean ready) {
        INSTANCE.ready = ready;
    }

    public static void setUnfreezeResultForTest(boolean result) {
        INSTANCE.unfreezeResult = result;
    }

    public static int unfreezeCallsForTest() { return INSTANCE.unfreezeCalls; }
    public static int lastUnfreezeUidForTest() { return INSTANCE.lastUnfreezeUid; }
    public static String lastUnfreezeReasonForTest() {
        return INSTANCE.lastUnfreezeReason;
    }
    public static String lastUnfreezeComponentForTest() {
        return INSTANCE.lastUnfreezeComponent;
    }

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
