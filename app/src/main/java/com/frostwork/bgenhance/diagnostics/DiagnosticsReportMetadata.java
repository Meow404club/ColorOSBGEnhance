package com.frostwork.bgenhance.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;

/** Runtime metadata attached to a diagnostics report without querying Hook processes. */
public final class DiagnosticsReportMetadata {
    public static final String XPOSED_API_MODE = "modern";
    public static final int XPOSED_MIN_API = 101;
    public static final int XPOSED_TARGET_API = 102;

    public final String modulePackageName;
    public final String moduleVersionName;
    public final long moduleVersionCode;
    public final int androidSdk;
    public final boolean deviceDetailsIncluded;
    public final String deviceManufacturer;
    public final String deviceModel;
    public final String deviceProduct;
    public final int bridgeVersion;
    public final int wireVersion;
    public final boolean xposedServiceConnected;
    public final int xposedServiceApiVersion;
    public final int localConfigRevision;
    public final int syncedConfigRevision;
    public final boolean configSynced;
    public final String configSyncErrorCode;
    public final String configSyncErrorDetails;
    public final boolean bootCountAvailable;
    public final int bootCount;

    DiagnosticsReportMetadata(
            String modulePackageName,
            String moduleVersionName,
            long moduleVersionCode,
            int androidSdk,
            boolean deviceDetailsIncluded,
            String deviceManufacturer,
            String deviceModel,
            String deviceProduct,
            int bridgeVersion,
            int wireVersion,
            boolean xposedServiceConnected,
            int xposedServiceApiVersion,
            int localConfigRevision,
            int syncedConfigRevision,
            boolean configSynced,
            String configSyncErrorCode,
            String configSyncErrorDetails,
            boolean bootCountAvailable,
            int bootCount) {
        this.modulePackageName = DiagnosticsValues.packageName(modulePackageName);
        this.moduleVersionName = DiagnosticsValues.id(moduleVersionName);
        this.moduleVersionCode = DiagnosticsValues.nonNegative(moduleVersionCode);
        this.androidSdk = Math.max(0, androidSdk);
        this.deviceDetailsIncluded = deviceDetailsIncluded;
        this.deviceManufacturer = deviceDetailsIncluded
                ? DiagnosticsValues.text(deviceManufacturer) : "";
        this.deviceModel = deviceDetailsIncluded ? DiagnosticsValues.text(deviceModel) : "";
        this.deviceProduct = deviceDetailsIncluded
                ? DiagnosticsValues.text(deviceProduct) : "";
        this.bridgeVersion = Math.max(0, bridgeVersion);
        this.wireVersion = Math.max(0, wireVersion);
        this.xposedServiceConnected = xposedServiceConnected;
        this.xposedServiceApiVersion = Math.max(-1, xposedServiceApiVersion);
        this.localConfigRevision = Math.max(-1, localConfigRevision);
        this.syncedConfigRevision = Math.max(-1, syncedConfigRevision);
        this.configSynced = configSynced;
        this.configSyncErrorCode = DiagnosticsValues.id(configSyncErrorCode);
        this.configSyncErrorDetails = deviceDetailsIncluded
                ? DiagnosticsValues.error(configSyncErrorDetails) : "";
        this.bootCountAvailable = deviceDetailsIncluded && bootCountAvailable;
        this.bootCount = this.bootCountAvailable ? Math.max(0, bootCount) : -1;
    }

    public static DiagnosticsReportMetadata capture(
            Context context, boolean includeSensitiveDetails) {
        Context application = context == null ? null : context.getApplicationContext();
        if (application == null) application = context;
        String packageName = application == null ? "" : application.getPackageName();
        String versionName = "";
        long versionCode = 0L;
        if (application != null) {
            try {
                PackageInfo info = application.getPackageManager().getPackageInfo(packageName, 0);
                versionName = info.versionName == null ? "" : info.versionName;
                versionCode = info.getLongVersionCode();
            } catch (Throwable ignored) {}
        }

        int localRevision = -1;
        int bootCount = -1;
        if (application != null) {
            try {
                localRevision = ModuleSettings.getConfigRevision(application);
            } catch (Throwable ignored) {}
            try {
                bootCount = Settings.Global.getInt(
                        application.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            } catch (Throwable ignored) {}
        }

        String syncError = ModuleApplication.syncError();
        return new DiagnosticsReportMetadata(
                packageName,
                versionName,
                versionCode,
                Build.VERSION.SDK_INT,
                includeSensitiveDetails,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.PRODUCT,
                BridgeContract.BRIDGE_VERSION,
                DiagnosticsWireParser.WIRE_VERSION,
                ModuleApplication.isServiceConnected(),
                ModuleApplication.serviceApiVersion(),
                localRevision,
                ModuleApplication.syncedRevision(),
                application != null && ModuleApplication.isConfigSynced(application),
                stableErrorCode(syncError),
                syncError,
                bootCount >= 0,
                bootCount);
    }

    public static DiagnosticsReportMetadata empty() {
        return new DiagnosticsReportMetadata(
                "", "", 0L, 0, false, "", "", "",
                BridgeContract.BRIDGE_VERSION, DiagnosticsWireParser.WIRE_VERSION,
                false, -1, -1, -1, false, "", "", false, -1);
    }

    private static String stableErrorCode(String value) {
        if (value == null || value.isEmpty()) return "";
        int separator = value.indexOf(':');
        String prefix = separator < 0 ? value : value.substring(0, separator);
        StringBuilder out = new StringBuilder(Math.min(prefix.length(), 64));
        for (int index = 0; index < prefix.length() && out.length() < 64; index++) {
            char current = prefix.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '_' || current == '-') {
                out.append(current);
            } else {
                out.append('_');
            }
        }
        return DiagnosticsValues.id(out.toString());
    }
}
