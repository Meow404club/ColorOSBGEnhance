package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;

public final class OguardKillHook implements FeatureHook {
    private static final String CLASS_NAME = "com.android.server.hans.oguard.policy.AppPowerPolicy";

    @Override public String id() { return FeatureIds.OGUARD_KILL; }
    @Override public String prefKey() { return ModuleSettings.KEY_OGUARD_KILL; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching(CLASS_NAME, loader,
                method -> isTarget(method), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = HookUtils.intArg(param.args, 0, -1);
                        String pkg = HookUtils.stringArg(param.args, 1);
                        String reason = HookUtils.stringArg(param.args, 2);
                        HookEvent event = new HookEvent(id(), 502, uid, pkg, pkg, reason);
                        if (Decision.shouldBlock(prefKey(), event)) param.setResult(true);
                    }
                });
        return count < 0 ? "UNSUPPORTED_CLASS" : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }

    private boolean isTarget(Method method) {
        String name = method.getName();
        if (!("tryKillApp".equals(name) || "tryKillAbnormalAudioApp".equals(name))) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 3 && p[0] == int.class && p[1] == String.class && p[2] == String.class
                && method.getReturnType() == boolean.class;
    }
}
