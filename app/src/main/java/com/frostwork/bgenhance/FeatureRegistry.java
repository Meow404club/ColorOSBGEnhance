package com.frostwork.bgenhance;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class FeatureRegistry {
    private FeatureRegistry() {}
    private static final Set<String> INSTALLED_TARGETS =
            Collections.synchronizedSet(new HashSet<>());

    public static void install(FeatureHook hook, ClassLoader classLoader) {
        String hookTargetId = hook.hookTargetId();
        if (!INSTALLED_TARGETS.add(hookTargetId)) return;
        String status;
        try {
            status = hook.install(classLoader);
            if (status == null) status = "UNKNOWN";
            ModuleLog.i("hookTarget=" + hookTargetId + " status=" + status);
        } catch (Throwable t) {
            status = "FAILED_FAIL_OPEN:" + t.getClass().getSimpleName();
            ModuleLog.e("hook target install failed: " + hookTargetId, t);
        }
        HookDiagnostics.reportFeature(hookTargetId, status);
    }
}
