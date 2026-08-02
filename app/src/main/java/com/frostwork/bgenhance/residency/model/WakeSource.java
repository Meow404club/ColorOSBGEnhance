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
    OTHER;

    /** Sources that policy must never suppress, even if imported settings request it. */
    public boolean isAlwaysAllowed() {
        switch (this) {
            case INTERNAL_OPERATION:
            case ACTIVITY_INPUT:
            case SYNC_BINDER:
            case PROVIDER:
            case SYSTEM_SCENE:
            case OTHER:
                return true;
            default:
                return false;
        }
    }
}
