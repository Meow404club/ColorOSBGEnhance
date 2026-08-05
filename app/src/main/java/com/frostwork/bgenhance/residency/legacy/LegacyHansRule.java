package com.frostwork.bgenhance.residency.legacy;

final class LegacyHansRule {
    final String packageName;
    final boolean enabled;
    final boolean fullExemption;
    final boolean customTiming;
    final long rToMDelayMs;
    final long mToFDelayMs;
    final int blockedFreezeSources;
    final int bypassProxyFlags;
    final boolean keepNetwork;
    final int packetWakeMode;
    final long packetWakeCooldownMs;
    final long packetRefreezeMs;
    final int alarmWakeMode;
    final long alarmWakeCooldownMs;
    final long alarmRefreezeMs;
    final int blockedWakeSources;

    LegacyHansRule(
            String packageName,
            boolean enabled,
            boolean fullExemption,
            boolean customTiming,
            long rToMDelayMs,
            long mToFDelayMs,
            int blockedFreezeSources,
            int bypassProxyFlags,
            boolean keepNetwork,
            int packetWakeMode,
            long packetWakeCooldownMs,
            long packetRefreezeMs,
            int alarmWakeMode,
            long alarmWakeCooldownMs,
            long alarmRefreezeMs,
            int blockedWakeSources) {
        this.packageName = packageName;
        this.enabled = enabled;
        this.fullExemption = fullExemption;
        this.customTiming = customTiming;
        this.rToMDelayMs = rToMDelayMs;
        this.mToFDelayMs = mToFDelayMs;
        this.blockedFreezeSources = blockedFreezeSources;
        this.bypassProxyFlags = bypassProxyFlags;
        this.keepNetwork = keepNetwork;
        this.packetWakeMode = packetWakeMode;
        this.packetWakeCooldownMs = packetWakeCooldownMs;
        this.packetRefreezeMs = packetRefreezeMs;
        this.alarmWakeMode = alarmWakeMode;
        this.alarmWakeCooldownMs = alarmWakeCooldownMs;
        this.alarmRefreezeMs = alarmRefreezeMs;
        this.blockedWakeSources = blockedWakeSources;
    }
}
