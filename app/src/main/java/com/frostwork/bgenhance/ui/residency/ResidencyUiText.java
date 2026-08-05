package com.frostwork.bgenhance.ui.residency;

import androidx.annotation.StringRes;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.ResidencyMode;

/** Central user-facing labels for policy modes and recoverable UI failures. */
public final class ResidencyUiText {
    private ResidencyUiText() {}

    @StringRes
    public static int modeName(ResidencyMode mode) {
        switch (mode) {
            case SYSTEM: return R.string.residency_mode_system;
            case BALANCED: return R.string.residency_mode_balanced;
            case LOW_POWER: return R.string.residency_mode_low_power;
            case ACTIVE: return R.string.residency_mode_active;
            case CUSTOM: return R.string.residency_mode_custom;
            default: throw new AssertionError(mode);
        }
    }

    @StringRes
    public static int restriction(ResidencyAppSafety.Restriction restriction) {
        switch (restriction) {
            case NOT_INSTALLED: return R.string.residency_restricted_not_installed;
            case MODULE: return R.string.residency_restricted_module;
            case CORE_SYSTEM: return R.string.residency_restricted_core;
            case PERSISTENT: return R.string.residency_restricted_persistent;
            case CRITICAL_PACKAGE: return R.string.residency_restricted_critical;
            case SHARED_UID: return R.string.residency_restricted_shared_uid;
            case IDENTITY_UNAVAILABLE: return R.string.residency_restricted_identity;
            case NONE: return R.string.residency_restricted_generic;
            default: throw new AssertionError(restriction);
        }
    }

    @StringRes
    public static int failure(ResidencyRuleStore.Failure failure) {
        if (failure == null) return R.string.residency_error_save;
        switch (failure) {
            case PACKAGE_UNAVAILABLE: return R.string.residency_error_package_unavailable;
            case PACKAGE_RESTRICTED: return R.string.residency_error_package_restricted;
            case RULE_LIMIT: return R.string.residency_error_rule_limit;
            case INVALID_POLICY: return R.string.residency_error_invalid;
            case SAVE_FAILED: return R.string.residency_error_save;
            case VERIFY_FAILED: return R.string.residency_error_verify;
            default: throw new AssertionError(failure);
        }
    }
}
