package com.frostwork.bgenhance.hooks;

import android.os.Bundle;
import com.frostwork.bgenhance.Decision;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ModuleSettings;
import java.lang.reflect.Method;

public final class NirvanaHook implements FeatureHook {
    private static final String CLASS_NAME = "com.android.server.am.OplusOsenseKillAction";

    @Override public String id() { return "osense_group_clean"; }
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
            String pkg = bundle.getString("packageName", bundle.getString("pkg", ""));
            int uid = bundle.getInt("uid", -1);
            HookEvent event = new HookEvent(mapping.feature, mapping.code, uid, pkg, "", scene);
            if (Decision.shouldBlock(mapping.prefKey, event)) return false;
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
            return new Mapping(FeatureIds.NIRVANA, ModuleSettings.KEY_NIRVANA, 1026);
        if ("CamLowFree".equals(scene))
            return new Mapping(FeatureIds.CAMERA_LOW_FREE, ModuleSettings.KEY_CAMERA_LOW_FREE, 1028);
        if ("GameLowFree".equals(scene))
            return new Mapping(FeatureIds.GAME_LOW_FREE, ModuleSettings.KEY_GAME_LOW_FREE, 1029);
        return null;
    }

    private static String status(int count) {
        return count < 0 ? "UNSUPPORTED_CLASS"
                : count == 0 ? "UNSUPPORTED_SIGNATURE" : "INSTALLED:" + count;
    }

    private static final class Mapping {
        final String feature; final String prefKey; final int code;
        Mapping(String feature, String prefKey, int code) {
            this.feature = feature; this.prefKey = prefKey; this.code = code;
        }
    }
}
