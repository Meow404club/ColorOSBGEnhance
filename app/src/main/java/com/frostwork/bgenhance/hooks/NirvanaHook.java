package com.frostwork.bgenhance.hooks;

import android.os.Bundle;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleLog;
import java.lang.reflect.Method;

public final class NirvanaHook implements FeatureHook {
    private static final String CLASS_NAME = "com.android.server.am.OplusOsenseKillAction";

    @Override public String hookTargetId() { return HookTargetIds.OSENSE_GROUP_CLEAN; }
    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching(CLASS_NAME, loader, this::isTarget, chain -> {
            Object value = HookUtils.objectArg(chain, 0);
            if (!(value instanceof Bundle)) return chain.proceed();
            Bundle bundle = (Bundle) value;
            String scene = bundle.getString("scene", "");
            Mapping mapping = map(scene);
            if (mapping == null) return chain.proceed();
            ModuleLog.d("osense scene request feature=" + mapping.featureId
                    + " code=" + mapping.code + " scene=" + scene);
            return chain.proceed();
        });
        return status(count);
    }

    private boolean isTarget(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return "executeNirvanaClear".equals(method.getName()) && p.length == 1
                && Bundle.class.isAssignableFrom(p[0]) && method.getReturnType() == boolean.class;
    }

    private Mapping map(String scene) {
        if ("NirvanaLowFree".equals(scene))
            return new Mapping(FeatureIds.NIRVANA, 1026);
        if ("CamLowFree".equals(scene))
            return new Mapping(FeatureIds.CAMERA_LOW_FREE, 1028);
        if ("GameLowFree".equals(scene))
            return new Mapping(FeatureIds.GAME_LOW_FREE, 1029);
        return null;
    }

    private static String status(int count) {
        return count < 0 ? "UNSUPPORTED_CLASS"
                : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }

    private static final class Mapping {
        final String featureId; final int code;
        Mapping(String featureId, int code) {
            this.featureId = featureId; this.code = code;
        }
    }
}
