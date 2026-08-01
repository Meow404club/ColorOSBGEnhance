package com.frostwork.bgenhance;

import androidx.annotation.NonNull;
import com.frostwork.bgenhance.hooks.AthenaPeriodicHook;
import com.frostwork.bgenhance.hooks.FastClearHook;
import com.frostwork.bgenhance.hooks.HansFreezeHook;
import com.frostwork.bgenhance.hooks.MemoryGuardHook;
import com.frostwork.bgenhance.hooks.NirvanaHook;
import com.frostwork.bgenhance.hooks.OguardForceStopHook;
import com.frostwork.bgenhance.hooks.OguardKillHook;
import com.frostwork.bgenhance.hooks.TournamentHook;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Modern libxposed API 102 entry point. */
public final class MainHook extends XposedModule {
    private volatile String processName = "";
    private volatile boolean systemServer;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        systemServer = param.isSystemServer();
        ModuleLog.initialize(this);
        HookUtils.initialize(this);
        String packageName = systemServer ? "android"
                : processName.startsWith("com.oplus.athena") ? "com.oplus.athena" : "";
        HookConfig.initialize(this, packageName, processName);
        HookDiagnostics.reportProcess("ENTRY_LOADED", null);
        ModuleLog.i("modern module loaded process=" + processName
                + " systemServer=" + systemServer
                + " api=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(
            @NonNull XposedModuleInterface.SystemServerStartingParam param) {
        if (!systemServer) return;
        HookConfig.setProcess("android", "system_server");
        try {
            ClassLoader loader = param.getClassLoader();
            ModuleLog.i("installing system_server hooks");
            FeatureRegistry.install(new OguardKillHook(), loader);
            FeatureRegistry.install(new OguardForceStopHook(), loader);
            FeatureRegistry.install(new NirvanaHook(), loader);
            FeatureRegistry.install(new HansFreezeHook(), loader);
            HookConfig.ensureDiagnosticsContext();
            HookDiagnostics.reportProcess("FEATURES_INSTALLED", null);
        } catch (Throwable t) {
            HookDiagnostics.reportProcess("BOOTSTRAP_FAILED", t);
            ModuleLog.e("system_server bootstrap failed", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!"com.oplus.athena".equals(param.getPackageName())) return;
        HookConfig.setProcess(param.getPackageName(), processName);
        try {
            ClassLoader loader = param.getClassLoader();
            ModuleLog.i("installing Athena hooks process=" + processName);
            FeatureRegistry.install(new AthenaPeriodicHook(), loader);
            FeatureRegistry.install(new FastClearHook(), loader);
            FeatureRegistry.install(new MemoryGuardHook(), loader);
            FeatureRegistry.install(new TournamentHook(), loader);
            HookConfig.ensureDiagnosticsContext();
            HookDiagnostics.reportProcess("FEATURES_INSTALLED", null);
        } catch (Throwable t) {
            HookDiagnostics.reportProcess("BOOTSTRAP_FAILED", t);
            ModuleLog.e("Athena bootstrap failed", t);
        }
    }
}
