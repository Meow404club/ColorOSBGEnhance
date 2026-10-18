package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import java.util.Objects;

/** Immutable application metadata rendered by the residency screens. */
public final class ResidencyAppItem {
    private final String label;
    private final String packageName;
    private final int userId;
    private final int uid;
    private final boolean installed;
    private final boolean systemApp;
    private final boolean launchable;
    private final ResidencyAppSafety.Result safety;
    private final AppResidencyPolicy policy;

    public ResidencyAppItem(
            String label,
            String packageName,
            boolean installed,
            boolean systemApp,
            boolean launchable,
            ResidencyAppSafety.Result safety,
            AppResidencyPolicy policy) {
        this(label, packageName, 0, -1, installed, systemApp, launchable, safety, policy);
    }

    public ResidencyAppItem(
            String label,
            String packageName,
            int userId,
            int uid,
            boolean installed,
            boolean systemApp,
            boolean launchable,
            ResidencyAppSafety.Result safety,
            AppResidencyPolicy policy) {
        this.label = Objects.requireNonNull(label, "label");
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        if (userId < 0) throw new IllegalArgumentException("userId");
        this.userId = userId;
        this.uid = uid;
        this.installed = installed;
        this.systemApp = systemApp;
        this.launchable = launchable;
        this.safety = Objects.requireNonNull(safety, "safety");
        this.policy = policy;
    }

    public String label() { return label; }
    public String packageName() { return packageName; }
    public int userId() { return userId; }
    public int uid() { return uid; }
    public boolean installed() { return installed; }
    public boolean systemApp() { return systemApp; }
    public boolean launchable() { return launchable; }
    public ResidencyAppSafety.Result safety() { return safety; }
    public AppResidencyPolicy policy() { return policy; }
    public boolean isManaged() { return policy != null; }
}
