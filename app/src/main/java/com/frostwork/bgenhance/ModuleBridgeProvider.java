package com.frostwork.bgenhance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;

/**
 * Restricted, write-only diagnostics bridge used by hooked processes.
 * Configuration is carried by modern RemotePreferences and is never exposed by this provider.
 * Only the module process, system UID and the scoped Athena package may call it.
 */
public final class ModuleBridgeProvider extends ContentProvider {
    private static final String ATHENA_PACKAGE = "com.oplus.athena";

    private volatile DiagnosticsProviderController controller;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        ResidencyDiagnosticsBackend backend = ModuleApplication.initializeDiagnostics(context);
        int athenaUid = resolveAthenaUid(context.getPackageManager());
        controller = new DiagnosticsProviderController(
                Process.myUid(),
                Process.SYSTEM_UID,
                athenaUid,
                ModuleApplication.diagnosticsBootId(),
                backend::admissionEpoch,
                batch -> backend.enqueueDurable(batch).thenApply(result ->
                        result.persisted
                                ? new DiagnosticsProviderController.BatchResult(
                                        result.itemCount, 0)
                                : new DiagnosticsProviderController.BatchResult(
                                        0, 0, result.errorCode)),
                new DiagnosticsWireParser());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        DiagnosticsProviderController current = controller;
        if (current == null) {
            int callerUid = Binder.getCallingUid();
            if (callerUid != Process.myUid() && callerUid != Process.SYSTEM_UID) {
                throw new SecurityException("diagnostics caller rejected");
            }
            return unavailableResponse("bridge_not_ready");
        }
        try {
            return current.handle(Binder.getCallingUid(), method, extras);
        } catch (SecurityException denied) {
            throw denied;
        } catch (Throwable t) {
            return unavailableResponse("provider_error");
        }
    }

    private static int resolveAthenaUid(PackageManager packageManager) {
        if (packageManager == null) return -1;
        try {
            return packageManager.getPackageUid(
                    ATHENA_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Bundle unavailableResponse(String errorCode) {
        Bundle out = new Bundle();
        out.putBoolean(BridgeContract.EXTRA_OK, false);
        out.putInt(BridgeContract.EXTRA_BRIDGE_VERSION, BridgeContract.BRIDGE_VERSION);
        out.putLong(
                BridgeContract.EXTRA_DIAGNOSTICS_EPOCH,
                ModuleApplication.diagnosticsEpoch());
        out.putString(BridgeContract.EXTRA_BOOT_ID, ModuleApplication.diagnosticsBootId());
        out.putString(BridgeContract.EXTRA_ERROR, errorCode);
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
