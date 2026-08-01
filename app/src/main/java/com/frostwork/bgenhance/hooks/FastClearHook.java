package com.frostwork.bgenhance.hooks;

import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;

public final class FastClearHook implements FeatureHook {
    @Override public String id() { return "fast_clear_dispatch"; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching("b4.v0", loader,
                this::isTarget, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int code = HookUtils.intArg(param.args, 0, -1);
                        Mapping mapping = map(code);
                        if (mapping == null) return;
                        String caller = HookUtils.stringArg(param.args, 6);
                        HookEvent event = new HookEvent(mapping.feature, code, -1, caller, "", caller);
                        if (Decision.shouldBlock(mapping.prefKey, event)) param.setResult(null);
                    }
                });
        String status = count < 0 ? "UNSUPPORTED_CLASS" : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
        return status;
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

    private static final class Mapping {
        final String feature;
        final String prefKey;
        Mapping(String feature, String prefKey) { this.feature = feature; this.prefKey = prefKey; }
    }
}
