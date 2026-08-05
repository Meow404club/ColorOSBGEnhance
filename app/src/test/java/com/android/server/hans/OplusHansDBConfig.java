package com.android.server.hans;

import java.util.HashMap;
import java.util.Map;

/** Firmware-shaped timing target with decoys that exact matching must reject. */
public class OplusHansDBConfig {
    private static final OplusHansDBConfig INSTANCE = new OplusHansDBConfig();
    private final Map<Integer, OplusHansPackage> packages = new HashMap<>();

    public static OplusHansDBConfig getInstance() {
        return INSTANCE;
    }

    public static Object getInstance(Object ignored) {
        return null;
    }

    public OplusHansPackage getHansPackage(int uid) {
        return packages.get(uid);
    }

    public static OplusHansPackage getHansPackage(Integer uid) {
        return null;
    }

    public Object getHansPackage(long uid) {
        return null;
    }

    public static void clearPackagesForTest() {
        INSTANCE.packages.clear();
    }

    public static void putPackageForTest(int uid, OplusHansPackage hansPackage) {
        if (hansPackage == null) {
            INSTANCE.packages.remove(uid);
        } else {
            INSTANCE.packages.put(uid, hansPackage);
        }
    }

    public long getRtoMCheckTime(int uid, String packageName, int appType, long backgroundTime) {
        return 11L;
    }

    public long getMtoFCheckTime(
            int uid,
            String packageName,
            int appType,
            long backgroundTime,
            String reason) {
        return 22L;
    }

    public long getRtoMCheckTime() {
        return -1L;
    }

    public long getMtoFCheckTime() {
        return -1L;
    }

    public static long getRtoMCheckTime(
            Integer uid, String packageName, int appType, long backgroundTime) {
        return -2L;
    }

    public static long getMtoFCheckTime(
            Integer uid,
            String packageName,
            int appType,
            long backgroundTime,
            String reason) {
        return -2L;
    }

    public int getRtoMCheckTime(
            int uid, CharSequence packageName, int appType, long backgroundTime) {
        return -3;
    }

    public int getMtoFCheckTime(
            int uid,
            CharSequence packageName,
            int appType,
            long backgroundTime,
            String reason) {
        return -3;
    }
}
