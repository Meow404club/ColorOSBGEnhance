package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

public final class TournamentHook implements FeatureHook {
    @Override public String id() { return FeatureIds.TOURNAMENT; }
    @Override public String prefKey() { return ModuleSettings.KEY_TOURNAMENT; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching("b4.x", loader,
                this::isTarget, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int code = HookUtils.intArg(param.args, 4, -1);
                        if (code != 78) return;
                        Object proc = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        String pkg = fieldString(proc, "pkgName");
                        String process = fieldString(proc, "procName");
                        int uid = fieldInt(proc, "uid", -1);
                        HookEvent event = new HookEvent(id(), code, uid, pkg, process, "tournamentmode");
                        if (Decision.shouldBlock(prefKey(), event)) param.setResult(null);
                    }
                });
        return count < 0 ? "UNSUPPORTED_CLASS" : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }

    private boolean isTarget(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "C0".equals(method.getName()) && p.length == 6
                && p[3] == int.class && p[4] == int.class && p[5] == String.class
                && method.getReturnType() == void.class;
    }

    private String fieldString(Object object, String field) {
        if (object == null) return "";
        try { Object value = XposedHelpers.getObjectField(object, field); return value == null ? "" : String.valueOf(value); }
        catch (Throwable ignored) { return ""; }
    }

    private int fieldInt(Object object, String field, int defValue) {
        if (object == null) return defValue;
        try { return XposedHelpers.getIntField(object, field); }
        catch (Throwable ignored) { return defValue; }
    }
}
