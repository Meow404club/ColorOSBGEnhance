package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.FreezeSource;
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
    WAKE_OTHER;

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
}
