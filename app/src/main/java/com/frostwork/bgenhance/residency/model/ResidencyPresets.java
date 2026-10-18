package com.frostwork.bgenhance.residency.model;

import java.util.Collections;

/** Expands a UI mode into a complete policy that remains stable after it is persisted. */
public final class ResidencyPresets {
    private static final long FIVE_MINUTES_MS = 5L * 60L * 1_000L;
    private static final long FIFTEEN_MINUTES_MS = 15L * 60L * 1_000L;
    private static final long ONE_MINUTE_MS = 60L * 1_000L;
    private static final long TWO_MINUTES_MS = 2L * 60L * 1_000L;

    private ResidencyPresets() {}

    public static AppResidencyPolicy create(String packageName, ResidencyMode mode) {
        return create(packageName, AppResidencyPolicy.PRIMARY_USER_ID, mode);
    }

    public static AppResidencyPolicy create(String packageName, int userId, ResidencyMode mode) {
        if (mode == null) throw new NullPointerException("mode");
        switch (mode) {
            case SYSTEM:
                return policy(packageName, userId, mode, KillPolicy.none(), FreezePolicy.followSystem(),
                        WakePolicy.allowAll(), ResourcePolicy.followSystem(),
                        RefreezePolicy.followSystem());
            case BALANCED:
                return policy(packageName, userId, mode, KillPolicy.all(), FreezePolicy.followSystem(),
                        WakePolicy.allowAll(), ResourcePolicy.followSystem(),
                        RefreezePolicy.followSystem());
            case LOW_POWER:
                WakePolicy lowPowerWake = new WakePolicy(
                        new WakeChannelPolicy(WakeMode.THROTTLE, FIVE_MINUTES_MS),
                        new WakeChannelPolicy(WakeMode.THROTTLE, FIFTEEN_MINUTES_MS),
                        Collections.emptySet());
                FreezePolicy lowPowerFreeze = new FreezePolicy(
                        true, true, true, true, true, ONE_MINUTE_MS, ONE_MINUTE_MS,
                        false, false, false);
                RefreezePolicy lowPowerRefreeze = new RefreezePolicy(
                        RefreezeMode.AFTER_WAKE,
                        ONE_MINUTE_MS,
                        ONE_MINUTE_MS,
                        TWO_MINUTES_MS,
                        ONE_MINUTE_MS,
                        ONE_MINUTE_MS,
                        ONE_MINUTE_MS,
                        ONE_MINUTE_MS);
                return policy(packageName, userId, mode, KillPolicy.all(), lowPowerFreeze,
                        lowPowerWake, ResourcePolicy.followSystem(), lowPowerRefreeze);
            case ACTIVE:
                FreezePolicy activeFreeze = new FreezePolicy(
                        false, false, false, false, false, 0L, 0L, true, true, true);
                return policy(packageName, userId, mode, KillPolicy.all(), activeFreeze,
                        WakePolicy.allowAll(), ResourcePolicy.followSystem(),
                        new RefreezePolicy(RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
            case CUSTOM:
                return policy(packageName, userId, mode, KillPolicy.all(), FreezePolicy.followSystem(),
                        WakePolicy.allowAll(), ResourcePolicy.followSystem(),
                        RefreezePolicy.followSystem());
            default:
                throw new AssertionError(mode);
        }
    }

    private static AppResidencyPolicy policy(
            String packageName,
            int userId,
            ResidencyMode mode,
            KillPolicy kill,
            FreezePolicy freeze,
            WakePolicy wake,
            ResourcePolicy resources,
            RefreezePolicy refreeze) {
        return new AppResidencyPolicy(
                packageName, userId, true, mode, kill, freeze, wake, resources, refreeze);
    }
}
