package com.frostwork.bgenhance.residency.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;

/** Authoritative presets for each independently editable residency policy section. */
public final class ResidencySectionPresets {
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long TWO_MINUTES_MS = 120_000L;
    private static final long FIVE_MINUTES_MS = 300_000L;
    private static final long FIFTEEN_MINUTES_MS = 900_000L;

    private static final KillPolicy KILL_FOLLOW_SYSTEM = KillPolicy.none();
    private static final KillPolicy KILL_FULL_PROTECTION = KillPolicy.all();

    private static final FreezePolicy FREEZE_FOLLOW_SYSTEM = FreezePolicy.followSystem();
    private static final FreezePolicy FREEZE_POWER_SAVING = new FreezePolicy(
            true, true, true, true, true, ONE_MINUTE_MS, ONE_MINUTE_MS,
            false, false, false);
    private static final FreezePolicy FREEZE_SCREEN_ON = new FreezePolicy(
            true, true, true, true, false, 0L, 0L, false, false, true);
    private static final FreezePolicy FREEZE_NEVER = new FreezePolicy(
            false, false, false, false, false, 0L, 0L, true, false, false);

    private static final WakePolicy WAKE_NORMAL = WakePolicy.allowAll();
    private static final WakePolicy WAKE_REDUCED_FREQUENCY = new WakePolicy(
            new WakeChannelPolicy(WakeMode.THROTTLE, FIVE_MINUTES_MS),
            new WakeChannelPolicy(WakeMode.THROTTLE, FIFTEEN_MINUTES_MS),
            Collections.emptySet());
    private static final WakePolicy WAKE_STRICT_POWER_SAVING = new WakePolicy(
            new WakeChannelPolicy(WakeMode.BLOCK, 0L),
            new WakeChannelPolicy(WakeMode.BLOCK, 0L),
            configurableBlockedWakeSources());

    private static final ResourcePolicy RESOURCE_FOLLOW_SYSTEM = ResourcePolicy.followSystem();
    private static final ResourcePolicy RESOURCE_MESSAGE_SYNC = new ResourcePolicy(
            true, EnumSet.of(ResourceType.SERVICE, ResourceType.BROADCAST, ResourceType.BINDER));
    private static final ResourcePolicy RESOURCE_AUDIO_PLAYBACK = new ResourcePolicy(
            true, EnumSet.of(ResourceType.SERVICE, ResourceType.BINDER,
                    ResourceType.WAKE_LOCK, ResourceType.AUDIO));
    private static final ResourcePolicy RESOURCE_NAVIGATION = new ResourcePolicy(
            true, EnumSet.of(ResourceType.SERVICE, ResourceType.SENSOR,
                    ResourceType.WAKE_LOCK, ResourceType.GPS));

    private static final RefreezePolicy REFREEZE_FOLLOW_SYSTEM = RefreezePolicy.followSystem();
    private static final RefreezePolicy REFREEZE_AFTER_WAKE = new RefreezePolicy(
            RefreezeMode.AFTER_WAKE,
            ONE_MINUTE_MS,
            ONE_MINUTE_MS,
            TWO_MINUTES_MS,
            ONE_MINUTE_MS,
            ONE_MINUTE_MS,
            ONE_MINUTE_MS,
            ONE_MINUTE_MS);
    private static final RefreezePolicy REFREEZE_DISABLED = new RefreezePolicy(
            RefreezeMode.DISABLED, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    private ResidencySectionPresets() {}

    public enum KillPreset {
        FOLLOW_SYSTEM,
        FULL_PROTECTION,
        CUSTOM
    }

    public enum FreezePreset {
        FOLLOW_SYSTEM,
        POWER_SAVING,
        KEEP_WHILE_SCREEN_ON,
        NEVER_FREEZE,
        CUSTOM
    }

    public enum WakePreset {
        NORMAL,
        REDUCED_FREQUENCY,
        STRICT_POWER_SAVING,
        CUSTOM
    }

    public enum ResourcePreset {
        FOLLOW_SYSTEM,
        MESSAGE_SYNC,
        AUDIO_PLAYBACK,
        NAVIGATION,
        CUSTOM
    }

    public enum RefreezePreset {
        FOLLOW_SYSTEM,
        AFTER_WAKE,
        DISABLED,
        CUSTOM
    }

    public static KillPolicy killPolicy(KillPreset preset) {
        switch (Objects.requireNonNull(preset, "preset")) {
            case FOLLOW_SYSTEM: return KILL_FOLLOW_SYSTEM;
            case FULL_PROTECTION: return KILL_FULL_PROTECTION;
            case CUSTOM:
            default: throw customHasNoFixedPolicy(preset);
        }
    }

    public static KillPreset matchKillPolicy(KillPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (KILL_FOLLOW_SYSTEM.equals(policy)) return KillPreset.FOLLOW_SYSTEM;
        if (KILL_FULL_PROTECTION.equals(policy)) return KillPreset.FULL_PROTECTION;
        return KillPreset.CUSTOM;
    }

    public static FreezePolicy freezePolicy(FreezePreset preset) {
        switch (Objects.requireNonNull(preset, "preset")) {
            case FOLLOW_SYSTEM: return FREEZE_FOLLOW_SYSTEM;
            case POWER_SAVING: return FREEZE_POWER_SAVING;
            case KEEP_WHILE_SCREEN_ON: return FREEZE_SCREEN_ON;
            case NEVER_FREEZE: return FREEZE_NEVER;
            case CUSTOM:
            default: throw customHasNoFixedPolicy(preset);
        }
    }

    public static FreezePreset matchFreezePolicy(FreezePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (FREEZE_FOLLOW_SYSTEM.equals(policy)) return FreezePreset.FOLLOW_SYSTEM;
        if (FREEZE_POWER_SAVING.equals(policy)) return FreezePreset.POWER_SAVING;
        if (FREEZE_SCREEN_ON.equals(policy)) return FreezePreset.KEEP_WHILE_SCREEN_ON;
        if (FREEZE_NEVER.equals(policy)) return FreezePreset.NEVER_FREEZE;
        return FreezePreset.CUSTOM;
    }

    public static WakePolicy wakePolicy(WakePreset preset) {
        switch (Objects.requireNonNull(preset, "preset")) {
            case NORMAL: return WAKE_NORMAL;
            case REDUCED_FREQUENCY: return WAKE_REDUCED_FREQUENCY;
            case STRICT_POWER_SAVING: return WAKE_STRICT_POWER_SAVING;
            case CUSTOM:
            default: throw customHasNoFixedPolicy(preset);
        }
    }

    public static WakePreset matchWakePolicy(WakePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (WAKE_NORMAL.equals(policy)) return WakePreset.NORMAL;
        if (WAKE_REDUCED_FREQUENCY.equals(policy)) return WakePreset.REDUCED_FREQUENCY;
        if (WAKE_STRICT_POWER_SAVING.equals(policy)) return WakePreset.STRICT_POWER_SAVING;
        return WakePreset.CUSTOM;
    }

    public static ResourcePolicy resourcePolicy(ResourcePreset preset) {
        switch (Objects.requireNonNull(preset, "preset")) {
            case FOLLOW_SYSTEM: return RESOURCE_FOLLOW_SYSTEM;
            case MESSAGE_SYNC: return RESOURCE_MESSAGE_SYNC;
            case AUDIO_PLAYBACK: return RESOURCE_AUDIO_PLAYBACK;
            case NAVIGATION: return RESOURCE_NAVIGATION;
            case CUSTOM:
            default: throw customHasNoFixedPolicy(preset);
        }
    }

    public static ResourcePreset matchResourcePolicy(ResourcePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (RESOURCE_FOLLOW_SYSTEM.equals(policy)) return ResourcePreset.FOLLOW_SYSTEM;
        if (RESOURCE_MESSAGE_SYNC.equals(policy)) return ResourcePreset.MESSAGE_SYNC;
        if (RESOURCE_AUDIO_PLAYBACK.equals(policy)) return ResourcePreset.AUDIO_PLAYBACK;
        if (RESOURCE_NAVIGATION.equals(policy)) return ResourcePreset.NAVIGATION;
        return ResourcePreset.CUSTOM;
    }

    public static RefreezePolicy refreezePolicy(RefreezePreset preset) {
        switch (Objects.requireNonNull(preset, "preset")) {
            case FOLLOW_SYSTEM: return REFREEZE_FOLLOW_SYSTEM;
            case AFTER_WAKE: return REFREEZE_AFTER_WAKE;
            case DISABLED: return REFREEZE_DISABLED;
            case CUSTOM:
            default: throw customHasNoFixedPolicy(preset);
        }
    }

    public static RefreezePreset matchRefreezePolicy(RefreezePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (REFREEZE_FOLLOW_SYSTEM.equals(policy)) return RefreezePreset.FOLLOW_SYSTEM;
        if (REFREEZE_AFTER_WAKE.equals(policy)) return RefreezePreset.AFTER_WAKE;
        if (REFREEZE_DISABLED.equals(policy)) return RefreezePreset.DISABLED;
        return RefreezePreset.CUSTOM;
    }

    private static EnumSet<WakeSource> configurableBlockedWakeSources() {
        EnumSet<WakeSource> sources = EnumSet.noneOf(WakeSource.class);
        for (WakeSource source : WakeSource.values()) {
            if (!source.isAlwaysAllowed()
                    && source != WakeSource.PACKET
                    && source != WakeSource.ALARM) {
                sources.add(source);
            }
        }
        return sources;
    }

    private static IllegalArgumentException customHasNoFixedPolicy(Enum<?> preset) {
        return new IllegalArgumentException(preset.getClass().getSimpleName()
                + ".CUSTOM has no fixed policy");
    }
}
