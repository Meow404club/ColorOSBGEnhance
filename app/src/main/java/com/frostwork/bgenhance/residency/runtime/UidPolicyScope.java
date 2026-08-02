package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeSource;
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
    LCD_ON_EXEMPTION,
    RESOURCE_NETWORK,
    RESOURCE_SERVICE,
    RESOURCE_BROADCAST,
    RESOURCE_JOB,
    RESOURCE_SENSOR,
    RESOURCE_BINDER,
    RESOURCE_ALARM,
    RESOURCE_WAKE_LOCK,
    RESOURCE_GPS,
    RESOURCE_AUDIO,
    RESOURCE_BLUETOOTH_SCAN,
    WAKE_PACKET,
    WAKE_ALARM,
    WAKE_ASYNC_BINDER,
    WAKE_SYNC_BINDER,
    WAKE_TRANSACTION_BINDER,
    WAKE_SIGNAL,
    WAKE_ACTIVITY_INPUT,
    WAKE_SERVICE,
    WAKE_BROADCAST,
    WAKE_PROVIDER,
    WAKE_JOB_SYNC,
    WAKE_LOCK,
    WAKE_AUDIO_MEDIA,
    WAKE_CONNECTIVITY,
    WAKE_SYSTEM_SCENE,
    WAKE_INTERNAL_OPERATION,
    WAKE_OTHER,
    REFREEZE_PACKET,
    REFREEZE_ALARM,
    REFREEZE_ASYNC_BINDER,
    REFREEZE_SYNC_BINDER,
    REFREEZE_TRANSACTION_BINDER,
    REFREEZE_SIGNAL,
    REFREEZE_ACTIVITY_INPUT,
    REFREEZE_SERVICE,
    REFREEZE_BROADCAST,
    REFREEZE_PROVIDER,
    REFREEZE_JOB_SYNC,
    REFREEZE_WAKE_LOCK,
    REFREEZE_AUDIO_MEDIA,
    REFREEZE_CONNECTIVITY,
    REFREEZE_SYSTEM_SCENE,
    REFREEZE_INTERNAL_OPERATION,
    REFREEZE_OTHER;

    public static UidPolicyScope forFreezeSource(FreezeSource source) {
        switch (Objects.requireNonNull(source, "source")) {
            case NORMAL: return NORMAL_FREEZE;
            case FAST: return FAST_FREEZE;
            case SUPER: return SUPER_FREEZE;
            case PRELOAD: return PRELOAD_FREEZE;
            default: throw new AssertionError(source);
        }
    }

    public static UidPolicyScope forWakeSource(WakeSource source) {
        switch (Objects.requireNonNull(source, "source")) {
            case PACKET: return WAKE_PACKET;
            case ALARM: return WAKE_ALARM;
            case ASYNC_BINDER: return WAKE_ASYNC_BINDER;
            case SYNC_BINDER: return WAKE_SYNC_BINDER;
            case TRANSACTION_BINDER: return WAKE_TRANSACTION_BINDER;
            case SIGNAL: return WAKE_SIGNAL;
            case ACTIVITY_INPUT: return WAKE_ACTIVITY_INPUT;
            case SERVICE: return WAKE_SERVICE;
            case BROADCAST: return WAKE_BROADCAST;
            case PROVIDER: return WAKE_PROVIDER;
            case JOB_SYNC: return WAKE_JOB_SYNC;
            case WAKE_LOCK: return WAKE_LOCK;
            case AUDIO_MEDIA: return WAKE_AUDIO_MEDIA;
            case CONNECTIVITY: return WAKE_CONNECTIVITY;
            case SYSTEM_SCENE: return WAKE_SYSTEM_SCENE;
            case INTERNAL_OPERATION: return WAKE_INTERNAL_OPERATION;
            case OTHER: return WAKE_OTHER;
            default: throw new AssertionError(source);
        }
    }

    public static UidPolicyScope forResource(ResourceType resource) {
        switch (Objects.requireNonNull(resource, "resource")) {
            case SERVICE: return RESOURCE_SERVICE;
            case BROADCAST: return RESOURCE_BROADCAST;
            case JOB: return RESOURCE_JOB;
            case SENSOR: return RESOURCE_SENSOR;
            case BINDER: return RESOURCE_BINDER;
            case ALARM: return RESOURCE_ALARM;
            case WAKE_LOCK: return RESOURCE_WAKE_LOCK;
            case GPS: return RESOURCE_GPS;
            case AUDIO: return RESOURCE_AUDIO;
            case BLUETOOTH_SCAN: return RESOURCE_BLUETOOTH_SCAN;
            default: throw new AssertionError(resource);
        }
    }

    public static UidPolicyScope forRefreezeSource(WakeSource source) {
        switch (Objects.requireNonNull(source, "source")) {
            case PACKET: return REFREEZE_PACKET;
            case ALARM: return REFREEZE_ALARM;
            case ASYNC_BINDER: return REFREEZE_ASYNC_BINDER;
            case SYNC_BINDER: return REFREEZE_SYNC_BINDER;
            case TRANSACTION_BINDER: return REFREEZE_TRANSACTION_BINDER;
            case SIGNAL: return REFREEZE_SIGNAL;
            case ACTIVITY_INPUT: return REFREEZE_ACTIVITY_INPUT;
            case SERVICE: return REFREEZE_SERVICE;
            case BROADCAST: return REFREEZE_BROADCAST;
            case PROVIDER: return REFREEZE_PROVIDER;
            case JOB_SYNC: return REFREEZE_JOB_SYNC;
            case WAKE_LOCK: return REFREEZE_WAKE_LOCK;
            case AUDIO_MEDIA: return REFREEZE_AUDIO_MEDIA;
            case CONNECTIVITY: return REFREEZE_CONNECTIVITY;
            case SYSTEM_SCENE: return REFREEZE_SYSTEM_SCENE;
            case INTERNAL_OPERATION: return REFREEZE_INTERNAL_OPERATION;
            case OTHER: return REFREEZE_OTHER;
            default: throw new AssertionError(source);
        }
    }

    WakeSource wakeSourceOrNull() {
        switch (this) {
            case WAKE_PACKET: return WakeSource.PACKET;
            case WAKE_ALARM: return WakeSource.ALARM;
            case WAKE_ASYNC_BINDER: return WakeSource.ASYNC_BINDER;
            case WAKE_SYNC_BINDER: return WakeSource.SYNC_BINDER;
            case WAKE_TRANSACTION_BINDER: return WakeSource.TRANSACTION_BINDER;
            case WAKE_SIGNAL: return WakeSource.SIGNAL;
            case WAKE_ACTIVITY_INPUT: return WakeSource.ACTIVITY_INPUT;
            case WAKE_SERVICE: return WakeSource.SERVICE;
            case WAKE_BROADCAST: return WakeSource.BROADCAST;
            case WAKE_PROVIDER: return WakeSource.PROVIDER;
            case WAKE_JOB_SYNC: return WakeSource.JOB_SYNC;
            case WAKE_LOCK: return WakeSource.WAKE_LOCK;
            case WAKE_AUDIO_MEDIA: return WakeSource.AUDIO_MEDIA;
            case WAKE_CONNECTIVITY: return WakeSource.CONNECTIVITY;
            case WAKE_SYSTEM_SCENE: return WakeSource.SYSTEM_SCENE;
            case WAKE_INTERNAL_OPERATION: return WakeSource.INTERNAL_OPERATION;
            case WAKE_OTHER: return WakeSource.OTHER;
            default: return null;
        }
    }

    ResourceType resourceTypeOrNull() {
        switch (this) {
            case RESOURCE_SERVICE: return ResourceType.SERVICE;
            case RESOURCE_BROADCAST: return ResourceType.BROADCAST;
            case RESOURCE_JOB: return ResourceType.JOB;
            case RESOURCE_SENSOR: return ResourceType.SENSOR;
            case RESOURCE_BINDER: return ResourceType.BINDER;
            case RESOURCE_ALARM: return ResourceType.ALARM;
            case RESOURCE_WAKE_LOCK: return ResourceType.WAKE_LOCK;
            case RESOURCE_GPS: return ResourceType.GPS;
            case RESOURCE_AUDIO: return ResourceType.AUDIO;
            case RESOURCE_BLUETOOTH_SCAN: return ResourceType.BLUETOOTH_SCAN;
            default: return null;
        }
    }

    WakeSource refreezeSourceOrNull() {
        switch (this) {
            case REFREEZE_PACKET: return WakeSource.PACKET;
            case REFREEZE_ALARM: return WakeSource.ALARM;
            case REFREEZE_ASYNC_BINDER: return WakeSource.ASYNC_BINDER;
            case REFREEZE_SYNC_BINDER: return WakeSource.SYNC_BINDER;
            case REFREEZE_TRANSACTION_BINDER: return WakeSource.TRANSACTION_BINDER;
            case REFREEZE_SIGNAL: return WakeSource.SIGNAL;
            case REFREEZE_ACTIVITY_INPUT: return WakeSource.ACTIVITY_INPUT;
            case REFREEZE_SERVICE: return WakeSource.SERVICE;
            case REFREEZE_BROADCAST: return WakeSource.BROADCAST;
            case REFREEZE_PROVIDER: return WakeSource.PROVIDER;
            case REFREEZE_JOB_SYNC: return WakeSource.JOB_SYNC;
            case REFREEZE_WAKE_LOCK: return WakeSource.WAKE_LOCK;
            case REFREEZE_AUDIO_MEDIA: return WakeSource.AUDIO_MEDIA;
            case REFREEZE_CONNECTIVITY: return WakeSource.CONNECTIVITY;
            case REFREEZE_SYSTEM_SCENE: return WakeSource.SYSTEM_SCENE;
            case REFREEZE_INTERNAL_OPERATION: return WakeSource.INTERNAL_OPERATION;
            case REFREEZE_OTHER: return WakeSource.OTHER;
            default: return null;
        }
    }
}
