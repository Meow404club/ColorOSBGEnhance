package com.frostwork.bgenhance.residency.legacy;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Converts Oplus-Hans-Policy v1-v5 documents into current custom app policies. */
public final class LegacyHansPolicyImporter {
    private LegacyHansPolicyImporter() {}

    public static LegacyHansImportResult importJson(String json)
            throws LegacyHansImportException {
        LegacyHansPolicyDocument source = LegacyHansPolicyCodec.decode(json);
        List<AppResidencyPolicy> policies = new ArrayList<>(source.rules.size());
        for (LegacyHansRule rule : source.rules) {
            policies.add(convert(rule));
        }
        return new LegacyHansImportResult(
                source.version, source.enabled, source.revision, policies);
    }

    private static AppResidencyPolicy convert(LegacyHansRule rule) {
        boolean fullExemption = rule.fullExemption;
        FreezePolicy freeze = new FreezePolicy(
                !fullExemption && !has(rule.blockedFreezeSources,
                        LegacyHansWireConstants.FREEZE_NORMAL),
                !fullExemption && !has(rule.blockedFreezeSources,
                        LegacyHansWireConstants.FREEZE_FAST),
                !fullExemption && !has(rule.blockedFreezeSources,
                        LegacyHansWireConstants.FREEZE_SUPER),
                !fullExemption && !has(rule.blockedFreezeSources,
                        LegacyHansWireConstants.FREEZE_PRELOAD),
                rule.customTiming,
                rule.rToMDelayMs,
                rule.mToFDelayMs,
                fullExemption,
                fullExemption,
                fullExemption);

        WakePolicy wake = fullExemption
                ? WakePolicy.allowAll()
                : new WakePolicy(
                        new WakeChannelPolicy(toWakeMode(rule.packetWakeMode),
                                rule.packetWakeCooldownMs),
                        new WakeChannelPolicy(toWakeMode(rule.alarmWakeMode),
                                rule.alarmWakeCooldownMs),
                        wakeSources(rule.blockedWakeSources));

        ResourcePolicy resources = new ResourcePolicy(
                rule.keepNetwork, resourceTypes(rule.bypassProxyFlags));
        RefreezeMode refreezeMode = rule.packetRefreezeMs > 0L || rule.alarmRefreezeMs > 0L
                ? RefreezeMode.AFTER_WAKE : RefreezeMode.SYSTEM;
        RefreezePolicy refreeze = new RefreezePolicy(
                refreezeMode, 0L, rule.packetRefreezeMs, rule.alarmRefreezeMs,
                0L, 0L, 0L, 0L);

        return new AppResidencyPolicy(
                rule.packageName,
                rule.enabled,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                freeze,
                wake,
                resources,
                refreeze);
    }

    private static WakeMode toWakeMode(int mode) {
        switch (mode) {
            case LegacyHansWireConstants.WAKE_ALLOW: return WakeMode.ALLOW;
            case LegacyHansWireConstants.WAKE_THROTTLE: return WakeMode.THROTTLE;
            case LegacyHansWireConstants.WAKE_BLOCK: return WakeMode.BLOCK;
            default: throw new AssertionError(mode);
        }
    }

    private static Set<ResourceType> resourceTypes(int flags) {
        if (flags == 0) return Collections.emptySet();
        EnumSet<ResourceType> resources = EnumSet.noneOf(ResourceType.class);
        add(resources, ResourceType.SERVICE, flags, LegacyHansWireConstants.PROXY_SERVICE);
        add(resources, ResourceType.BROADCAST, flags, LegacyHansWireConstants.PROXY_BROADCAST);
        add(resources, ResourceType.JOB, flags, LegacyHansWireConstants.PROXY_JOB);
        add(resources, ResourceType.SENSOR, flags, LegacyHansWireConstants.PROXY_SENSOR);
        add(resources, ResourceType.BINDER, flags, LegacyHansWireConstants.PROXY_BINDER);
        add(resources, ResourceType.ALARM, flags, LegacyHansWireConstants.PROXY_ALARM);
        add(resources, ResourceType.WAKE_LOCK, flags, LegacyHansWireConstants.PROXY_WAKE_LOCK);
        add(resources, ResourceType.GPS, flags, LegacyHansWireConstants.PROXY_GPS);
        add(resources, ResourceType.AUDIO, flags, LegacyHansWireConstants.PROXY_AUDIO);
        add(resources, ResourceType.BLUETOOTH_SCAN, flags,
                LegacyHansWireConstants.PROXY_BLUETOOTH_SCAN);
        return resources;
    }

    private static Set<WakeSource> wakeSources(int flags) {
        if (flags == 0) return Collections.emptySet();
        EnumSet<WakeSource> sources = EnumSet.noneOf(WakeSource.class);
        add(sources, WakeSource.ASYNC_BINDER, flags,
                LegacyHansWireConstants.WAKE_ASYNC_BINDER);
        add(sources, WakeSource.SYNC_BINDER, flags,
                LegacyHansWireConstants.WAKE_SYNC_BINDER);
        add(sources, WakeSource.TRANSACTION_BINDER, flags,
                LegacyHansWireConstants.WAKE_TRANSACTION_BINDER);
        add(sources, WakeSource.SIGNAL, flags, LegacyHansWireConstants.WAKE_SIGNAL);
        add(sources, WakeSource.ACTIVITY_INPUT, flags,
                LegacyHansWireConstants.WAKE_ACTIVITY_INPUT);
        add(sources, WakeSource.SERVICE, flags, LegacyHansWireConstants.WAKE_SERVICE);
        add(sources, WakeSource.BROADCAST, flags, LegacyHansWireConstants.WAKE_BROADCAST);
        add(sources, WakeSource.PROVIDER, flags, LegacyHansWireConstants.WAKE_PROVIDER);
        add(sources, WakeSource.JOB_SYNC, flags, LegacyHansWireConstants.WAKE_JOB_SYNC);
        add(sources, WakeSource.WAKE_LOCK, flags, LegacyHansWireConstants.WAKE_WAKE_LOCK);
        add(sources, WakeSource.AUDIO_MEDIA, flags,
                LegacyHansWireConstants.WAKE_AUDIO_MEDIA);
        add(sources, WakeSource.CONNECTIVITY, flags,
                LegacyHansWireConstants.WAKE_CONNECTIVITY);
        add(sources, WakeSource.SYSTEM_SCENE, flags,
                LegacyHansWireConstants.WAKE_SYSTEM_SCENE);
        add(sources, WakeSource.OTHER, flags, LegacyHansWireConstants.WAKE_OTHER);
        return sources;
    }

    private static <T extends Enum<T>> void add(Set<T> target, T value, int flags, int flag) {
        if (has(flags, flag)) target.add(value);
    }

    private static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
