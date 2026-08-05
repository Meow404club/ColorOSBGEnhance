package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime.Capability;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime.CapabilityMatrix;
import java.lang.reflect.Method;
import java.util.Objects;

/** Invokes the verified HANS recovery path from a caller-owned background thread. */
public final class HansActiveRecoveryAdapter {
    public static final String RECOVERY_REASON = "force";
    public static final String RECOVERY_COMPONENT = "BgEnhancePolicy";

    private final Method managerInstance;
    private final Method managerReady;
    private final Method managerUnfreeze;
    private final Method configInstance;
    private final Method configPackage;

    private HansActiveRecoveryAdapter(
            Method managerInstance,
            Method managerReady,
            Method managerUnfreeze,
            Method configInstance,
            Method configPackage) {
        this.managerInstance = managerInstance;
        this.managerReady = managerReady;
        this.managerUnfreeze = managerUnfreeze;
        this.configInstance = configInstance;
        this.configPackage = configPackage;
    }

    public static HansActiveRecoveryAdapter create(CapabilityMatrix matrix) {
        if (matrix == null || !matrix.allAvailable()) return null;
        Method managerInstance = validated(matrix, Capability.MANAGER_INSTANCE);
        Method managerReady = validated(matrix, Capability.MANAGER_READY);
        Method managerUnfreeze = validated(matrix, Capability.MANAGER_UNFREEZE);
        Method configInstance = validated(matrix, Capability.CONFIG_INSTANCE);
        Method configPackage = validated(matrix, Capability.CONFIG_PACKAGE);
        if (managerInstance == null
                || managerReady == null
                || managerUnfreeze == null
                || configInstance == null
                || configPackage == null) {
            return null;
        }
        return new HansActiveRecoveryAdapter(
                managerInstance,
                managerReady,
                managerUnfreeze,
                configInstance,
                configPackage);
    }

    public Outcome recover(int uid) {
        if (uid < 0) return Outcome.INVALID_TARGET;
        try {
            Object manager = managerInstance.invoke(null);
            if (manager == null) return Outcome.ACCESS_FAILED;
            Object ready = managerReady.invoke(manager);
            if (!(ready instanceof Boolean)) return Outcome.ACCESS_FAILED;
            if (!((Boolean) ready).booleanValue()) return Outcome.NOT_READY;

            Object config = configInstance.invoke(null);
            if (config == null) return Outcome.ACCESS_FAILED;
            Object packageObject = configPackage.invoke(config, uid);
            if (packageObject == null) return Outcome.TARGET_UNAVAILABLE;
            HansObjectAdapter.Resolution resolution =
                    HansObjectAdapter.resolve(packageObject);
            if (!resolution.isResolved()
                    || resolution.targetOrNull().uid() != uid) {
                return Outcome.ACCESS_FAILED;
            }
            if (!resolution.targetOrNull().frozen()) return Outcome.NOT_FROZEN;

            Object result = managerUnfreeze.invoke(
                    manager, uid, RECOVERY_REASON, RECOVERY_COMPONENT);
            if (!(result instanceof Boolean)) return Outcome.ACCESS_FAILED;
            return ((Boolean) result).booleanValue()
                    ? Outcome.RECOVERED
                    : Outcome.UNFREEZE_FAILED;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return Outcome.ACCESS_FAILED;
        }
    }

    private static Method validated(CapabilityMatrix matrix, Capability capability) {
        Method method = matrix.methodOrNull(Objects.requireNonNull(capability, "capability"));
        return HansActiveRecoveryRuntime.matches(capability, method) ? method : null;
    }

    public enum Outcome {
        RECOVERED(false),
        NOT_FROZEN(false),
        TARGET_UNAVAILABLE(false),
        INVALID_TARGET(false),
        NOT_READY(true),
        UNFREEZE_FAILED(true),
        ACCESS_FAILED(true);

        private final boolean retryable;

        Outcome(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() { return retryable; }
        public boolean completed() {
            return this == RECOVERED || this == NOT_FROZEN;
        }
    }
}
