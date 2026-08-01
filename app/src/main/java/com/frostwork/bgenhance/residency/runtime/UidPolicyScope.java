package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.FreezeSource;
import java.util.Objects;

/** Policy projection used to compare rules for one UID-wide platform action. */
public enum UidPolicyScope {
    FULL,
    NORMAL_FREEZE,
    FAST_FREEZE,
    SUPER_FREEZE,
    PRELOAD_FREEZE,
    R_TO_M_TIMING,
    M_TO_F_TIMING,
    CORE_EXEMPTION,
    LCD_ON_EXEMPTION;

    public static UidPolicyScope forFreezeSource(FreezeSource source) {
        switch (Objects.requireNonNull(source, "source")) {
            case NORMAL: return NORMAL_FREEZE;
            case FAST: return FAST_FREEZE;
            case SUPER: return SUPER_FREEZE;
            case PRELOAD: return PRELOAD_FREEZE;
            default: throw new AssertionError(source);
        }
    }
}
