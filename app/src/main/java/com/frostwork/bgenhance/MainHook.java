package com.frostwork.bgenhance;

import androidx.annotation.NonNull;
import com.frostwork.bgenhance.hooks.AthenaPeriodicHook;
import com.frostwork.bgenhance.hooks.FastClearHook;
import com.frostwork.bgenhance.hooks.HansExemptionHook;
import com.frostwork.bgenhance.hooks.HansFreezeHook;
import com.frostwork.bgenhance.hooks.HansTimingHook;
import com.frostwork.bgenhance.hooks.HansWakeHook;
import com.frostwork.bgenhance.hooks.MemoryGuardHook;
import com.frostwork.bgenhance.hooks.NirvanaHook;
import com.frostwork.bgenhance.hooks.OguardForceStopHook;
import com.frostwork.bgenhance.hooks.OguardKillHook;
import com.frostwork.bgenhance.hooks.OsenseProcessKillHook;
import com.frostwork.bgenhance.hooks.TournamentHook;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Modern libxposed API 102 entry point. */
public final class MainHook extends XposedModule {
    private static final String ATHENA_PACKAGE = "com.oplus.athena";

    private volatile String processName = "";
    private volatile boolean systemServer;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        systemServer = param.isSystemServer();
        if (!systemServer && !ATHENA_PACKAGE.equals(processName)) return;

        ModuleLog.initialize(this);
        HookUtils.initialize(this);
        String packageName = systemServer ? "android" : ATHENA_PACKAGE;
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
            for (FeatureHook hook : systemServerHooks()) {
                FeatureRegistry.install(hook, loader);
            }
            HookConfig.ensureDiagnosticsContext();
            HookDiagnostics.reportProcess("FEATURES_INSTALLED", null);
        } catch (Throwable t) {
            HookDiagnostics.reportProcess("BOOTSTRAP_FAILED", t);
            ModuleLog.e("system_server bootstrap failed", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (systemServer
                || !ATHENA_PACKAGE.equals(processName)
                || !ATHENA_PACKAGE.equals(param.getPackageName())) return;
        HookConfig.setProcess(param.getPackageName(), processName);
        try {
            ClassLoader loader = param.getClassLoader();
            ModuleLog.i("installing Athena hooks process=" + processName);
            for (FeatureHook hook : athenaHooks()) {
                FeatureRegistry.install(hook, loader);
            }
            HookConfig.ensureDiagnosticsContext();
            HookDiagnostics.reportProcess("FEATURES_INSTALLED", null);
        } catch (Throwable t) {
            HookDiagnostics.reportProcess("BOOTSTRAP_FAILED", t);
            ModuleLog.e("Athena bootstrap failed", t);
        }
    }

    static List<FeatureHook> systemServerHooks() {
        return Collections.unmodifiableList(Arrays.asList(
                new OguardKillHook(),
                new OguardForceStopHook(),
                new NirvanaHook(),
                new OsenseProcessKillHook(),
                new HansFreezeHook(),
                new HansWakeHook(),
                HansTimingHook.rToM(),
                HansTimingHook.mToF(),
                HansExemptionHook.core(),
                HansExemptionHook.lcdOn()));
    }

    static List<FeatureHook> athenaHooks() {
        return Collections.unmodifiableList(Arrays.asList(
                new AthenaPeriodicHook(),
                new FastClearHook(),
                new MemoryGuardHook(),
                new TournamentHook()));
    }
}
