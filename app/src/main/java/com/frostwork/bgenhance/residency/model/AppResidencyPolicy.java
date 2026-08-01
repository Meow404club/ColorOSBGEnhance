package com.frostwork.bgenhance.residency.model;

import java.util.Objects;

/** Complete persisted policy for one application package. */
public final class AppResidencyPolicy {
    private final String packageName;
    private final boolean enabled;
    private final ResidencyMode mode;
    private final KillPolicy killPolicy;
    private final FreezePolicy freezePolicy;
    private final WakePolicy wakePolicy;
    private final ResourcePolicy resourcePolicy;
    private final RefreezePolicy refreezePolicy;

    public AppResidencyPolicy(
            String packageName,
            boolean enabled,
            ResidencyMode mode,
            KillPolicy killPolicy,
            FreezePolicy freezePolicy,
            WakePolicy wakePolicy,
            ResourcePolicy resourcePolicy,
            RefreezePolicy refreezePolicy) {
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.enabled = enabled;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.killPolicy = Objects.requireNonNull(killPolicy, "killPolicy");
        this.freezePolicy = Objects.requireNonNull(freezePolicy, "freezePolicy");
        this.wakePolicy = Objects.requireNonNull(wakePolicy, "wakePolicy");
        this.resourcePolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
        this.refreezePolicy = Objects.requireNonNull(refreezePolicy, "refreezePolicy");
    }

    public String packageName() { return packageName; }
    public boolean enabled() { return enabled; }
    public ResidencyMode mode() { return mode; }
    public KillPolicy killPolicy() { return killPolicy; }
    public FreezePolicy freezePolicy() { return freezePolicy; }
    public WakePolicy wakePolicy() { return wakePolicy; }
    public ResourcePolicy resourcePolicy() { return resourcePolicy; }
    public RefreezePolicy refreezePolicy() { return refreezePolicy; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AppResidencyPolicy)) return false;
        AppResidencyPolicy that = (AppResidencyPolicy) other;
        return enabled == that.enabled
                && packageName.equals(that.packageName)
                && mode == that.mode
                && killPolicy.equals(that.killPolicy)
                && freezePolicy.equals(that.freezePolicy)
                && wakePolicy.equals(that.wakePolicy)
                && resourcePolicy.equals(that.resourcePolicy)
                && refreezePolicy.equals(that.refreezePolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, enabled, mode, killPolicy, freezePolicy, wakePolicy,
                resourcePolicy, refreezePolicy);
    }
}
