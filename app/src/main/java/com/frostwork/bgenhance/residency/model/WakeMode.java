package com.frostwork.bgenhance.residency.model;

/** Policy applied when an event attempts to wake a frozen application. */
public enum WakeMode {
    ALLOW,
    THROTTLE,
    BLOCK
}
