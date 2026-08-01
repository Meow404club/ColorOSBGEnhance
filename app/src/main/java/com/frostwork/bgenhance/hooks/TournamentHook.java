package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import java.lang.reflect.Method;

public final class TournamentHook implements FeatureHook {
    @Override public String hookTargetId() { return HookTargetIds.TOURNAMENT; }
    @Override public String prefKey() { return ModuleSettings.KEY_TOURNAMENT; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching("b4.x", loader, this::isTarget, chain -> {
            int code = HookUtils.intArg(chain, 4, -1);
            if (code != 78) return chain.proceed();
            Object proc = HookUtils.objectArg(chain, 0);
            String pkg = HookUtils.fieldString(proc, "pkgName");
            String process = HookUtils.fieldString(proc, "procName");
            int uid = HookUtils.fieldInt(proc, "uid", -1);
            HookEvent event = new HookEvent(
                    FeatureIds.TOURNAMENT, code, uid, pkg, process, "tournamentmode");
            if (Decision.shouldBlock(prefKey(), event)) return null;
            return chain.proceed();
        });
        return status(count);
    }

    private boolean isTarget(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "C0".equals(method.getName()) && p.length == 6
                && p[3] == int.class && p[4] == int.class && p[5] == String.class
                && method.getReturnType() == void.class;
    }

    private static String status(int count) {
        return count < 0 ? "UNSUPPORTED_CLASS"
                : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }
}
