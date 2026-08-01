package com.frostwork.bgenhance;

public final class Decision {
    private Decision() {}

    public static boolean shouldBlock(String prefKey, HookEvent event) {
        if (!HookConfig.isReady()) {
            ModuleLog.w("config unavailable; fail-open for " + event.feature);
            HookDiagnostics.recordEvent(event, "config_unavailable");
            return false;
        }
        boolean master = HookConfig.getBoolean(ModuleSettings.KEY_MASTER_ENABLE);
        boolean selected = HookConfig.getBoolean(prefKey);
        boolean dryRun = HookConfig.getBoolean(ModuleSettings.KEY_DRY_RUN);
        String result;
        boolean block = false;
        if (!master || !selected) {
            result = "passed";
        } else if (dryRun) {
            result = "observed";
        } else {
            result = "blocked";
            block = true;
        }
        HookDiagnostics.recordEvent(event, result);
        ModuleLog.d(event + " result=" + result + " configRevision=" + HookConfig.revision());
        return block;
    }
}
