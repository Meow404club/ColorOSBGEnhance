package com.frostwork.bgenhance.residency.model;

/** Resources that HANS can proxy or restrict while an application is frozen. */
public enum ResourceType {
    SERVICE,
    BROADCAST,
    JOB,
    SENSOR,
    BINDER,
    ALARM,
    WAKE_LOCK,
    GPS,
    AUDIO,
    BLUETOOTH_SCAN
}
