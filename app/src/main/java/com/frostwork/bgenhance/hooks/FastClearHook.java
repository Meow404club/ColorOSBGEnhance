package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import java.lang.reflect.Method;

public final class FastClearHook implements FeatureHook {
    @Override public String hookTargetId() { return HookTargetIds.FAST_CLEAR_DISPATCH; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching("b4.v0", loader, this::isTarget, chain -> {
            int code = HookUtils.intArg(chain, 0, -1);
            Mapping mapping = map(code);
            if (mapping == null) return chain.proceed();
            String caller = HookUtils.stringArg(chain, 6);
            HookEvent event = new HookEvent(mapping.featureId, code, -1, caller, "", caller);
            if (Decision.shouldBlock(mapping.prefKey, event)) return null;
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

    private Mapping map(int code) {
        switch (code) {
            case 2000: case 2003: case 2006:
                return new Mapping(FeatureIds.FAST_CAMERA_START, ModuleSettings.KEY_FAST_CAMERA_START);
            case 2001: case 2002: case 2004: case 2005: case 2007: case 2008:
                return new Mapping(FeatureIds.FAST_CAMERA_OPERATION, ModuleSettings.KEY_FAST_CAMERA_OPERATION);
            case 2009:
                return new Mapping(FeatureIds.FAST_GAME_START, ModuleSettings.KEY_FAST_GAME_START);
            case 2010: case 2011:
                return new Mapping(FeatureIds.FAST_AI, ModuleSettings.KEY_FAST_AI);
            default:
                return null;
        }
    }

    private static String status(int count) {
        return count < 0 ? "UNSUPPORTED_CLASS"
                : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }

    private static final class Mapping {
        final String featureId;
        final String prefKey;
        Mapping(String featureId, String prefKey) {
            this.featureId = featureId;
            this.prefKey = prefKey;
        }
    }
}
