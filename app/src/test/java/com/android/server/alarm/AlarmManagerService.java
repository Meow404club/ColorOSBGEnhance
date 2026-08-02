package com.android.server.alarm;

import android.app.ActivityManagerInternal;

/** Firmware-shaped owner for the pre-resolved noteAlarmFinish path. */
public final class AlarmManagerService {
    private final ActivityManagerInternal mActivityManagerInternal;

    public AlarmManagerService(ActivityManagerInternal activityManagerInternal) {
        this.mActivityManagerInternal = activityManagerInternal;
    }
}
