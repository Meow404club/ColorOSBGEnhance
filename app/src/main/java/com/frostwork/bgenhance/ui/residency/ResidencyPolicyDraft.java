package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import java.util.Objects;

/** UI-only policy draft kept separate from the immutable runtime snapshot. */
public final class ResidencyPolicyDraft {
    private final boolean enabled;
    private final ResidencyMode mode;
    private final KillPolicy customKillPolicy;
    private final FreezePolicy customFreezePolicy;
    private final WakePolicy customWakePolicy;
    private final ResourcePolicy customResourcePolicy;
    private final RefreezePolicy customRefreezePolicy;

    public ResidencyPolicyDraft(
            boolean enabled,
            ResidencyMode mode,
            KillPolicy customKillPolicy,
            FreezePolicy customFreezePolicy,
            WakePolicy customWakePolicy,
            ResourcePolicy customResourcePolicy,
            RefreezePolicy customRefreezePolicy) {
        this.enabled = enabled;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.customKillPolicy = Objects.requireNonNull(customKillPolicy, "customKillPolicy");
        this.customFreezePolicy = Objects.requireNonNull(
                customFreezePolicy, "customFreezePolicy");
        this.customWakePolicy = Objects.requireNonNull(customWakePolicy, "customWakePolicy");
        this.customResourcePolicy = Objects.requireNonNull(
                customResourcePolicy, "customResourcePolicy");
        this.customRefreezePolicy = Objects.requireNonNull(
                customRefreezePolicy, "customRefreezePolicy");
    }

    public static ResidencyPolicyDraft from(AppResidencyPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return new ResidencyPolicyDraft(
                policy.enabled(),
                policy.mode(),
                policy.killPolicy(),
                policy.freezePolicy(),
                policy.wakePolicy(),
                policy.resourcePolicy(),
                policy.refreezePolicy());
    }

    public AppResidencyPolicy toPolicy(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        if (mode == ResidencyMode.CUSTOM) {
            return new AppResidencyPolicy(
                    packageName,
                    enabled,
                    mode,
                    customKillPolicy,
                    customFreezePolicy,
                    customWakePolicy,
                    customResourcePolicy,
                    customRefreezePolicy);
        }
        AppResidencyPolicy preset = ResidencyPresets.create(packageName, mode);
        return new AppResidencyPolicy(
                packageName,
                enabled,
                mode,
                preset.killPolicy(),
                preset.freezePolicy(),
                preset.wakePolicy(),
                preset.resourcePolicy(),
                preset.refreezePolicy());
    }

    public boolean enabled() { return enabled; }
    public ResidencyMode mode() { return mode; }
    public KillPolicy customKillPolicy() { return customKillPolicy; }
    public FreezePolicy customFreezePolicy() { return customFreezePolicy; }
    public WakePolicy customWakePolicy() { return customWakePolicy; }
    public ResourcePolicy customResourcePolicy() { return customResourcePolicy; }
    public RefreezePolicy customRefreezePolicy() { return customRefreezePolicy; }
}
