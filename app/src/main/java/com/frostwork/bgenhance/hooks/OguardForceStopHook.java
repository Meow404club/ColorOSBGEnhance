package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import java.lang.reflect.Method;

public final class OguardForceStopHook implements FeatureHook {
    private static final String CLASS_NAME = "com.android.server.hans.oguard.policy.AppPowerPolicy";

    @Override public String hookTargetId() { return HookTargetIds.OGUARD_FORCE_STOP; }
    @Override public String prefKey() { return ModuleSettings.KEY_OGUARD_FORCE_STOP; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching(CLASS_NAME, loader, this::isTarget, chain -> {
            int uid = HookUtils.intArg(chain, 0, -1);
            String pkg = HookUtils.stringArg(chain, 1);
            String reason = HookUtils.stringArg(chain, 2);
            HookEvent event = new HookEvent(
                    FeatureIds.OGUARD_FORCE_STOP, 502, uid, pkg, pkg, reason);
            if (Decision.shouldBlock(prefKey(), event)) return true;
            return chain.proceed();
        });
        return status(count);
    }

    private boolean isTarget(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "tryForceStopApp".equals(method.getName())
                && p.length == 3 && p[0] == int.class && p[1] == String.class && p[2] == String.class
                && method.getReturnType() == boolean.class;
    }

    private static String status(int count) {
        return count < 0 ? "UNSUPPORTED_CLASS"
                : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }
}
