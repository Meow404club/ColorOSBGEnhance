package com.android.server.alarm;

import android.app.ActivityManagerInternal;

/** Firmware-shaped owner for the pre-resolved noteAlarmFinish path. */
public final class AlarmManagerService {
    private final ActivityManagerInternal mActivityManagerInternal;
    private final Injector mInjector;

    public AlarmManagerService(ActivityManagerInternal activityManagerInternal) {
        this(activityManagerInternal, true);
    }

    public AlarmManagerService(
            ActivityManagerInternal activityManagerInternal,
            boolean nativeDriverPresent) {
        this.mActivityManagerInternal = activityManagerInternal;
        this.mInjector = new Injector(nativeDriverPresent);
    }

    static final class Injector {
        private final boolean nativeDriverPresent;

        private Injector(boolean nativeDriverPresent) {
            this.nativeDriverPresent = nativeDriverPresent;
        }

        boolean isAlarmDriverPresent() {
            return nativeDriverPresent;
        }
    }
}
