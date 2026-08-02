package com.frostwork.bgenhance;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FeatureRegistry {
    private FeatureRegistry() {}

    private static final Map<String, String> FINAL_INSTALLATION_STATUSES =
            new ConcurrentHashMap<>();
    private static final Set<String> CLAIMED_TARGETS =
            ConcurrentHashMap.newKeySet();

    public static void install(FeatureHook hook, ClassLoader classLoader) {
        String hookTargetId = hook.hookTargetId();
        if (!CLAIMED_TARGETS.add(hookTargetId)) return;
        String status;
        Throwable installError = null;
        try {
            status = hook.install(classLoader);
            if (status == null) status = "UNKNOWN";
        } catch (Throwable t) {
            status = "FAILED_FAIL_OPEN:" + t.getClass().getSimpleName();
            installError = t;
        }

        FINAL_INSTALLATION_STATUSES.put(hookTargetId, status);
        reportInstallation(hookTargetId, status, installError);
    }

    /** Returns a target's final status, or empty while unknown or still installing. */
    public static Optional<String> installationStatus(String hookTargetId) {
        if (hookTargetId == null) return Optional.empty();
        return Optional.ofNullable(FINAL_INSTALLATION_STATUSES.get(hookTargetId));
    }

    /** Returns true only when the target reported a final status beginning with INSTALLED. */
    public static boolean isInstalled(String hookTargetId) {
        return installationStatus(hookTargetId)
                .map(status -> status.startsWith("INSTALLED"))
                .orElse(false);
    }

    static void resetForTests() {
        CLAIMED_TARGETS.clear();
        FINAL_INSTALLATION_STATUSES.clear();
    }

    private static void reportInstallation(
            String hookTargetId, String status, Throwable installError) {
        try {
            if (installError == null) {
                ModuleLog.i("hookTarget=" + hookTargetId + " status=" + status);
            } else {
                ModuleLog.e("hook target install failed: " + hookTargetId, installError);
            }
        } catch (Throwable ignored) {
            // Final installation state must not depend on logging availability.
        }
        try {
            HookDiagnostics.reportFeature(hookTargetId, status);
        } catch (Throwable ignored) {
            // Final installation state must not depend on diagnostics availability.
        }
    }
}
