package com.android.server.hans;

/** Firmware-shaped timing target with decoys that exact matching must reject. */
public class OplusHansDBConfig {
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
