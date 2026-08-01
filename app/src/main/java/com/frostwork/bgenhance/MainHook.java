package com.frostwork.bgenhance;

import android.app.AndroidAppHelper;
import android.content.Context;
import com.frostwork.bgenhance.hooks.AthenaPeriodicHook;
import com.frostwork.bgenhance.hooks.FastClearHook;
import com.frostwork.bgenhance.hooks.HansFreezeHook;
import com.frostwork.bgenhance.hooks.MemoryGuardHook;
import com.frostwork.bgenhance.hooks.NirvanaHook;
import com.frostwork.bgenhance.hooks.OguardForceStopHook;
import com.frostwork.bgenhance.hooks.OguardKillHook;
import com.frostwork.bgenhance.hooks.TournamentHook;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return;
        boolean systemServer = isSystemServer(lp);
        boolean athena = "com.oplus.athena".equals(lp.packageName);
        if (!systemServer && !athena) return;

        HookConfig.setProcess(lp.packageName, lp.processName);
        try {
            Context app = null;
            try { app = AndroidAppHelper.currentApplication(); } catch (Throwable ignored) {}
            if (app != null) HookConfig.attach(app);
            else HookConfig.context();
            HookDiagnostics.reportProcess("ENTRY_LOADED", null);

            if (systemServer) {
                ModuleLog.i("loaded system_server package=" + lp.packageName + " process=" + lp.processName);
                FeatureRegistry.install(new OguardKillHook(), lp.classLoader);
                FeatureRegistry.install(new OguardForceStopHook(), lp.classLoader);
                FeatureRegistry.install(new NirvanaHook(), lp.classLoader);
                FeatureRegistry.install(new HansFreezeHook(), lp.classLoader);
            }

            if (athena) {
                ModuleLog.i("loaded Athena process=" + lp.processName);
                FeatureRegistry.install(new AthenaPeriodicHook(), lp.classLoader);
                FeatureRegistry.install(new FastClearHook(), lp.classLoader);
                FeatureRegistry.install(new MemoryGuardHook(), lp.classLoader);
                FeatureRegistry.install(new TournamentHook(), lp.classLoader);
            }
            HookDiagnostics.reportProcess("FEATURES_INSTALLED", null);
        } catch (Throwable t) {
            HookDiagnostics.reportProcess("BOOTSTRAP_FAILED", t);
            ModuleLog.e("bootstrap failed package=" + lp.packageName, t);
        }
    }

    private boolean isSystemServer(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return false;
        String process = lp.processName;
        return process == null
                || "android".equals(process)
                || "system_server".equals(process)
                || process.contains("system_server");
    }
}
