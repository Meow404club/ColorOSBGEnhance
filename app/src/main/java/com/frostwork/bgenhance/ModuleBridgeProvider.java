package com.frostwork.bgenhance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;

/**
 * Restricted IPC bridge. Configuration snapshots and diagnostics are both fail-open and run off Hook threads.
 * Only the module process, system UID and the scoped Athena package may call it.
 */
public final class ModuleBridgeProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) ModuleSettings.ensureDefaults(context);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceAllowedCaller();
        Context context = getContext();
        Bundle out = new Bundle();
        if (context == null) {
            out.putBoolean(BridgeContract.EXTRA_OK, false);
            out.putString(BridgeContract.EXTRA_ERROR, "provider context unavailable");
            return out;
        }
        try {
            if (BridgeContract.METHOD_PING.equals(method)) {
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                out.putInt(BridgeContract.EXTRA_BRIDGE_VERSION, BridgeContract.BRIDGE_VERSION);
                out.putInt(BridgeContract.EXTRA_CONFIG_REVISION,
                        ModuleSettings.getConfigRevision(context));
                return out;
            }
            if (BridgeContract.METHOD_GET_CONFIG.equals(method)) {
                SharedPreferences sp = ModuleSettings.prefs(context);
                for (java.util.Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
                    out.putBoolean(entry.getKey(),
                            sp.getBoolean(entry.getKey(), entry.getValue()));
                }
                out.putString(BridgeContract.EXTRA_ACTIVE_PRESET,
                        sp.getString(ModuleSettings.KEY_ACTIVE_PRESET,
                                ModuleSettings.PRESET_BALANCED));
                out.putInt(BridgeContract.EXTRA_CONFIG_REVISION,
                        ModuleSettings.getConfigRevision(context));
                out.putInt(BridgeContract.EXTRA_BRIDGE_VERSION, BridgeContract.BRIDGE_VERSION);
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                return out;
            }
            if (BridgeContract.METHOD_REPORT_PROCESS.equals(method)) {
                requireExtras(extras);
                DiagnosticsStore.reportProcess(context,
                        extras.getString(BridgeContract.EXTRA_PROCESS_ID, "unknown"),
                        extras.getString(BridgeContract.EXTRA_PACKAGE, ""),
                        extras.getString(BridgeContract.EXTRA_PROCESS, ""),
                        extras.getString(BridgeContract.EXTRA_STAGE, "LOADED"),
                        extras.getString(BridgeContract.EXTRA_ERROR, ""));
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                return out;
            }
            if (BridgeContract.METHOD_REPORT_CONFIG.equals(method)) {
                requireExtras(extras);
                DiagnosticsStore.reportConfigLoaded(context,
                        extras.getString(BridgeContract.EXTRA_PROCESS_ID, "unknown"),
                        extras.getString(BridgeContract.EXTRA_PACKAGE, ""),
                        extras.getString(BridgeContract.EXTRA_PROCESS, ""),
                        extras.getInt(BridgeContract.EXTRA_CONFIG_REVISION, -1),
                        extras.getString(BridgeContract.EXTRA_CONFIG_SOURCE, ""),
                        extras.getString(BridgeContract.EXTRA_CONFIG_PATH, ""),
                        extras.getLong(BridgeContract.EXTRA_RELOAD_LATENCY, 0L),
                        extras.getString(BridgeContract.EXTRA_ERROR, ""));
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                return out;
            }
            if (BridgeContract.METHOD_REPORT_FEATURE.equals(method)) {
                requireExtras(extras);
                DiagnosticsStore.setSupportStatus(context,
                        extras.getString(BridgeContract.EXTRA_FEATURE, ""),
                        extras.getString(BridgeContract.EXTRA_STATUS, "UNKNOWN"));
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                return out;
            }
            if (BridgeContract.METHOD_REPORT_SNAPSHOT.equals(method)) {
                requireExtras(extras);
                DiagnosticsStore.mergeSnapshot(context, extras);
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                return out;
            }
            if (BridgeContract.METHOD_RECORD_EVENT.equals(method)) {
                requireExtras(extras);
                HookEvent event = new HookEvent(
                        extras.getString(BridgeContract.EXTRA_FEATURE, ""),
                        extras.getInt(BridgeContract.EXTRA_CODE, 0),
                        extras.getInt(BridgeContract.EXTRA_UID, -1),
                        extras.getString(BridgeContract.EXTRA_PACKAGE, ""),
                        extras.getString(BridgeContract.EXTRA_PROCESS, ""),
                        extras.getString(BridgeContract.EXTRA_SCENE, ""));
                DiagnosticsStore.recordEvent(context, event,
                        extras.getString(BridgeContract.EXTRA_RESULT, "passed"));
                out.putBoolean(BridgeContract.EXTRA_OK, true);
                return out;
            }
            return super.call(method, arg, extras);
        } catch (Throwable t) {
            out.putBoolean(BridgeContract.EXTRA_OK, false);
            out.putString(BridgeContract.EXTRA_ERROR,
                    t.getClass().getSimpleName() + ":" + safe(t.getMessage()));
            return out;
        }
    }

    private void enforceAllowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return;
        Context context = getContext();
        if (context != null) {
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null) {
                for (String packageName : packages) {
                    if ("com.oplus.athena".equals(packageName)) return;
                }
            }
        }
        throw new SecurityException("caller uid " + uid + " is not in LSPosed scope");
    }

    private static void requireExtras(Bundle extras) {
        if (extras == null) throw new IllegalArgumentException("missing extras");
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "" : value;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
