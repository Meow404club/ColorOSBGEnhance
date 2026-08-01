package com.frostwork.bgenhance;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

public final class ModuleLog {
    private ModuleLog() {}

    public static void i(String message) {
        Log.i(ModuleSettings.TAG, message);
        try { XposedBridge.log(ModuleSettings.TAG + ": " + message); } catch (Throwable ignored) {}
    }

    public static void d(String message) {
        if (!HookConfig.getBoolean(ModuleSettings.KEY_VERBOSE_LOG)) return;
        Log.d(ModuleSettings.TAG, message);
        try { XposedBridge.log(ModuleSettings.TAG + " [D]: " + message); } catch (Throwable ignored) {}
    }

    public static void w(String message) {
        Log.w(ModuleSettings.TAG, message);
        try { XposedBridge.log(ModuleSettings.TAG + " [W]: " + message); } catch (Throwable ignored) {}
    }

    public static void e(String message, Throwable t) {
        Log.e(ModuleSettings.TAG, message, t);
        try { XposedBridge.log(ModuleSettings.TAG + " [E]: " + message + "\n" + Log.getStackTraceString(t)); }
        catch (Throwable ignored) {}
    }
}
