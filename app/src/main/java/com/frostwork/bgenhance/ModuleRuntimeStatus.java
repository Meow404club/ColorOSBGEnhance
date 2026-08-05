package com.frostwork.bgenhance;

/** Versioned process stages used to detect an APK update before hooked processes restart. */
public final class ModuleRuntimeStatus {
    private static final String FEATURES_INSTALLED_PREFIX = "FEATURES_INSTALLED_V";

    private ModuleRuntimeStatus() {}

    public static String featuresInstalled(int versionCode) {
        if (versionCode <= 0) throw new IllegalArgumentException("versionCode <= 0");
        return FEATURES_INSTALLED_PREFIX + versionCode;
    }

    public static boolean isFeaturesInstalled(String status) {
        return loadedVersionCode(status) > 0;
    }

    public static boolean isCurrentVersion(String status, int versionCode) {
        return versionCode > 0 && loadedVersionCode(status) == versionCode;
    }

    public static int loadedVersionCode(String status) {
        if (status == null || !status.startsWith(FEATURES_INSTALLED_PREFIX)) return -1;
        String suffix = status.substring(FEATURES_INSTALLED_PREFIX.length());
        if (suffix.isEmpty() || suffix.length() > 10) return -1;
        long value = 0L;
        for (int index = 0; index < suffix.length(); index++) {
            char current = suffix.charAt(index);
            if (current < '0' || current > '9') return -1;
            value = value * 10L + current - '0';
            if (value > Integer.MAX_VALUE) return -1;
        }
        return value > 0L ? (int) value : -1;
    }
}
