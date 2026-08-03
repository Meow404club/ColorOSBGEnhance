package com.frostwork.bgenhance;

import android.net.Uri;

/** Constants shared by the module UI provider and code injected into target processes. */
public final class BridgeContract {
    private BridgeContract() {}

    public static final String MODULE_PACKAGE = "com.frostwork.bgenhance";
    public static final String AUTHORITY = "com.frostwork.bgenhance.bridge";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final int BRIDGE_VERSION = 3;

    public static final String METHOD_PING = "ping";
    public static final String METHOD_REPORT_BATCH = "report_diagnostics_batch";
    @Deprecated
    public static final String METHOD_REPORT_PROCESS = "report_process";
    @Deprecated
    public static final String METHOD_REPORT_CONFIG = "report_config";
    @Deprecated
    public static final String METHOD_REPORT_FEATURE = "report_feature";
    @Deprecated
    public static final String METHOD_RECORD_EVENT = "record_event";
    @Deprecated
    public static final String METHOD_REPORT_SNAPSHOT = "report_snapshot";

    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_BRIDGE_VERSION = "bridge_version";
    public static final String EXTRA_CONFIG_REVISION = "config_revision";
    public static final String EXTRA_ACTIVE_PRESET = "active_preset";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_PROCESS = "process";
    public static final String EXTRA_PROCESS_ID = "process_id";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_FEATURE = "feature";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_SCENE = "scene";
    public static final String EXTRA_TIMESTAMP = "timestamp";
    public static final String EXTRA_CONFIG_SOURCE = "config_source";
    public static final String EXTRA_CONFIG_PATH = "config_path";
    public static final String EXTRA_RELOAD_LATENCY = "reload_latency_ms";
    public static final String EXTRA_FEATURES = "features";
    public static final String EXTRA_ACCEPTED_ITEMS = "accepted_items";
    public static final String EXTRA_DROPPED_ITEMS = "dropped_items";
    public static final String EXTRA_DIAGNOSTICS_EPOCH = "diagnostics_epoch";
    public static final String EXTRA_BOOT_ID = "diagnostics_boot_id";

    public static final String PROCESS_SYSTEM_SERVER = "system_server";
    public static final String PROCESS_ATHENA = "athena";

    public static String processId(String packageName, String processName) {
        if ("android".equals(packageName)
                || "system_server".equals(processName)
                || (processName != null && processName.contains("system_server"))) {
            return PROCESS_SYSTEM_SERVER;
        }
        if ("com.oplus.athena".equals(packageName)) return PROCESS_ATHENA;
        String value = processName == null || processName.isEmpty() ? packageName : processName;
        return value == null || value.isEmpty() ? "unknown" : sanitize(value);
    }

    public static String sanitize(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
