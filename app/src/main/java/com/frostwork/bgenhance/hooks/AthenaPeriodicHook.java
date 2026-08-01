package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;

public final class AthenaPeriodicHook implements FeatureHook {
    @Override public String id() { return FeatureIds.ATHENA_PERIODIC; }
    @Override public String prefKey() { return ModuleSettings.KEY_PERIODIC; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching("b4.e1", loader,
                this::isTarget, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        HookEvent event = new HookEvent(id(), 322, -1, "", "", "PeriodicDeepClearAction");
                        if (Decision.shouldBlock(prefKey(), event)) param.setResult(null);
                    }
                });
        return count < 0 ? "UNSUPPORTED_CLASS" : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }

    private boolean isTarget(Method method) {
        return "k".equals(method.getName())
                && method.getParameterTypes().length == 0
                && method.getReturnType() == void.class;
    }
}
