package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;

public final class HansFreezeHook implements FeatureHook {
    private static final String CLASS_NAME = "com.android.server.am.OplusHansManager";

    @Override public String id() { return "hans_freeze_dispatch"; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        int hans = HookUtils.hookMatching(CLASS_NAME, loader,
                this::isHansFreeze, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = HookUtils.intArg(param.args, 0, -1);
                        String scene = HookUtils.stringArg(param.args, 1);
                        HookEvent event = new HookEvent(FeatureIds.HANS, 0, uid, "", "", scene);
                        if (Decision.shouldBlock(ModuleSettings.KEY_HANS, event)) param.setResult(false);
                    }
                });
        int superFreeze = HookUtils.hookMatching(CLASS_NAME, loader,
                this::isSuperFreeze, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = HookUtils.intArg(param.args, 0, -1);
                        HookEvent event = new HookEvent(FeatureIds.SUPER_FREEZE, 0, uid, "", "", "superFreeze");
                        if (Decision.shouldBlock(ModuleSettings.KEY_SUPER_FREEZE, event)) param.setResult(null);
                    }
                });
        String status;
        if (hans < 0 && superFreeze < 0) status = "UNSUPPORTED_CLASS";
        else if (hans <= 0 && superFreeze <= 0) status = "UNSUPPORTED_SIGNATURE";
        else status = "INSTALLED:hans=" + Math.max(hans, 0) + ",super=" + Math.max(superFreeze, 0)
                + ",android=DIAGNOSTIC_ONLY";
        return status;
    }

    private boolean isHansFreeze(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "hansFreeze".equals(method.getName()) && (p.length == 2 || p.length == 3)
                && p[0] == int.class && p[1] == String.class
                && (p.length == 2 || p[2] == boolean.class)
                && method.getReturnType() == boolean.class;
    }

    private boolean isSuperFreeze(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "enterSuperFreeze".equals(method.getName()) && p.length == 1
                && p[0] == int.class && method.getReturnType() == void.class;
    }
}
