package com.frostwork.bgenhance;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class FeatureRegistry {
    private FeatureRegistry() {}
    private static final Set<String> INSTALLED = Collections.synchronizedSet(new HashSet<>());

    public static void install(FeatureHook feature, ClassLoader classLoader) {
        String id = feature.id();
        if (!INSTALLED.add(id)) return;
        String status;
        try {
            status = feature.install(classLoader);
            if (status == null) status = "UNKNOWN";
            ModuleLog.i("feature=" + id + " status=" + status);
        } catch (Throwable t) {
            status = "FAILED_FAIL_OPEN:" + t.getClass().getSimpleName();
            ModuleLog.e("feature install failed: " + id, t);
        }
        HookDiagnostics.reportFeature(id, status);
    }
}
