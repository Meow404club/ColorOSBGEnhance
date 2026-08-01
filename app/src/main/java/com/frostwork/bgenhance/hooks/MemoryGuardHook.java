package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import java.lang.reflect.Method;

public final class MemoryGuardHook implements FeatureHook {
    @Override public String id() { return "memory_guard_dispatch"; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching("b4.v0", loader, this::isTarget, chain -> {
            int code = HookUtils.intArg(chain, 0, -1);
            String feature;
            String pref;
            if (code == 4012) {
                feature = FeatureIds.MEMORY_FORCE;
                pref = ModuleSettings.KEY_MEMORY_FORCE;
            } else if (code == 4013) {
                feature = FeatureIds.MEMORY_OVER;
                pref = ModuleSettings.KEY_MEMORY_OVER;
            } else {
                return chain.proceed();
            }
            String caller = HookUtils.stringArg(chain, 6);
            HookEvent event = new HookEvent(feature, code, -1, caller, "", caller);
            if (Decision.shouldBlock(pref, event)) return null;
            return chain.proceed();
        });
        return status(count);
    }

    private boolean isTarget(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "e1".equals(method.getName()) && p.length == 7
                && p[0] == int.class && p[1] == boolean.class && p[2] == boolean.class
                && p[3] == long.class && p[4] == int.class
                && java.util.List.class.isAssignableFrom(p[5]) && p[6] == String.class
                && method.getReturnType() == void.class;
    }

    private static String status(int count) {
        return count < 0 ? "UNSUPPORTED_CLASS"
                : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }
}
