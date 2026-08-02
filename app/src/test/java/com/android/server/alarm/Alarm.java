package com.android.server.alarm;

import android.app.IAlarmListener;
import android.app.PendingIntent;
import android.os.WorkSource;

/** Field-compatible Alarm fixture for local JVM tests. */
public class Alarm {
    private final PendingIntent operation;
    private final IAlarmListener listener;
    private final String listenerTag;
    private final String statsTag;
    private final WorkSource workSource;
    private final int uid;
    private final int creatorUid;
    private final String packageName;
    private final String sourcePackage;

    public Alarm(
            PendingIntent operation,
            IAlarmListener listener,
            String listenerTag,
            String statsTag,
            WorkSource workSource,
            int uid,
            int creatorUid,
            String packageName,
            String sourcePackage) {
        this.operation = operation;
        this.listener = listener;
        this.listenerTag = listenerTag;
        this.statsTag = statsTag;
        this.workSource = workSource;
        this.uid = uid;
        this.creatorUid = creatorUid;
        this.packageName = packageName;
        this.sourcePackage = sourcePackage;
    }
}
