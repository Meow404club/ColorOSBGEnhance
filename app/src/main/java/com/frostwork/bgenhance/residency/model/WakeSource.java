package com.frostwork.bgenhance.residency.model;

/** Normalized sources that can wake a frozen application. */
public enum WakeSource {
    PACKET,
    ALARM,
    ASYNC_BINDER,
    SYNC_BINDER,
    TRANSACTION_BINDER,
    SIGNAL,
    ACTIVITY_INPUT,
    SERVICE,
    BROADCAST,
    PROVIDER,
    JOB_SYNC,
    WAKE_LOCK,
    AUDIO_MEDIA,
    CONNECTIVITY,
    SYSTEM_SCENE,
    INTERNAL_OPERATION,
    OTHER
}
