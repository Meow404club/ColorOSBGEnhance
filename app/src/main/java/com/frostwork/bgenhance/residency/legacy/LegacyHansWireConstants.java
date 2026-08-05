package com.frostwork.bgenhance.residency.legacy;

/** Wire values used by Oplus-Hans-Policy schema versions 1 through 5. */
public final class LegacyHansWireConstants {
    public static final int MIN_VERSION = 1;
    public static final int MAX_VERSION = 5;

    public static final int WAKE_ALLOW = 0;
    public static final int WAKE_THROTTLE = 1;
    public static final int WAKE_BLOCK = 2;

    public static final int FREEZE_NORMAL = 1;
    public static final int FREEZE_FAST = 1 << 1;
    public static final int FREEZE_SUPER = 1 << 2;
    public static final int FREEZE_PRELOAD = 1 << 3;
    public static final int ALL_FREEZE_SOURCES = (1 << 4) - 1;

    public static final int PROXY_SERVICE = 1;
    public static final int PROXY_BROADCAST = 1 << 1;
    public static final int PROXY_JOB = 1 << 2;
    public static final int PROXY_SENSOR = 1 << 3;
    public static final int PROXY_BINDER = 1 << 4;
    public static final int PROXY_ALARM = 1 << 5;
    public static final int PROXY_WAKE_LOCK = 1 << 6;
    public static final int PROXY_GPS = 1 << 7;
    public static final int PROXY_AUDIO = 1 << 8;
    public static final int PROXY_BLUETOOTH_SCAN = 1 << 9;
    public static final int ALL_PROXY_FLAGS = (1 << 10) - 1;

    public static final int WAKE_ASYNC_BINDER = 1;
    public static final int WAKE_SYNC_BINDER = 1 << 1;
    public static final int WAKE_TRANSACTION_BINDER = 1 << 2;
    public static final int WAKE_SIGNAL = 1 << 3;
    public static final int WAKE_ACTIVITY_INPUT = 1 << 4;
    public static final int WAKE_SERVICE = 1 << 5;
    public static final int WAKE_BROADCAST = 1 << 6;
    public static final int WAKE_PROVIDER = 1 << 7;
    public static final int WAKE_JOB_SYNC = 1 << 8;
    public static final int WAKE_WAKE_LOCK = 1 << 9;
    public static final int WAKE_AUDIO_MEDIA = 1 << 10;
    public static final int WAKE_CONNECTIVITY = 1 << 11;
    public static final int WAKE_SYSTEM_SCENE = 1 << 12;
    public static final int WAKE_OTHER = 1 << 13;
    public static final int ALL_WAKE_SOURCES = (1 << 14) - 1;

    public static final long DEFAULT_R_TO_M_MS = 60_000L;
    public static final long DEFAULT_M_TO_F_MS = 60_000L;
    public static final long DEFAULT_PACKET_COOLDOWN_MS = 60_000L;
    public static final long DEFAULT_ALARM_COOLDOWN_MS = 900_000L;

    private LegacyHansWireConstants() {}
}
