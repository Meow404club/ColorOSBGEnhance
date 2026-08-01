package com.frostwork.bgenhance;

import android.util.Log;
import io.github.libxposed.api.XposedModule;

public final class ModuleLog {
    private ModuleLog() {}

    private static volatile XposedModule sModule;

    public static void initialize(XposedModule module) {
        sModule = module;
    }

    public static void i(String message) {
        log(Log.INFO, message, null);
    }

    public static void d(String message) {
        if (!HookConfig.getBoolean(ModuleSettings.KEY_VERBOSE_LOG)) return;
        log(Log.DEBUG, message, null);
    }

    public static void w(String message) {
        log(Log.WARN, message, null);
    }

    public static void e(String message, Throwable error) {
        log(Log.ERROR, message, error);
    }

    private static void log(int priority, String message, Throwable error) {
        if (error == null) Log.println(priority, ModuleSettings.TAG, message);
        else Log.println(priority, ModuleSettings.TAG,
                message + "\n" + Log.getStackTraceString(error));
        XposedModule module = sModule;
        if (module == null) return;
        try {
            module.log(priority, ModuleSettings.TAG, message, error);
        } catch (Throwable ignored) {}
    }
}
