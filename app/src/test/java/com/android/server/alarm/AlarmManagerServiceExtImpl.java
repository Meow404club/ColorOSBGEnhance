package com.android.server.alarm;

/** Firmware-shaped Alarm extension with decoy overloads. */
public class AlarmManagerServiceExtImpl {
    private final AlarmManagerService mAlarmMS;

    public AlarmManagerServiceExtImpl(AlarmManagerService alarmManagerService) {
        this.mAlarmMS = alarmManagerService;
    }

    public boolean filterAlarmForHans(Alarm alarm) {
        return false;
    }

    public boolean interceptDeliverAlarmsLockedInLoop(Alarm alarm) {
        return false;
    }

    public static boolean filterAlarmForHans(Object alarm) {
        return true;
    }

    public int interceptDeliverAlarmsLockedInLoop(Object alarm) {
        return -1;
    }
}
