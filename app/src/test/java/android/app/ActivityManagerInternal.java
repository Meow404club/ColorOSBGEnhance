package android.app;

import android.os.WorkSource;

/** Minimal hidden-framework fixture for the Alarm completion access path. */
public class ActivityManagerInternal {
    private int finishCount;
    private PendingIntent operation;
    private WorkSource workSource;
    private int uid;
    private String statsTag;

    public void noteAlarmFinish(
            PendingIntent operation,
            WorkSource workSource,
            int uid,
            String statsTag) {
        finishCount++;
        this.operation = operation;
        this.workSource = workSource;
        this.uid = uid;
        this.statsTag = statsTag;
    }

    public int finishCount() { return finishCount; }
    public PendingIntent operation() { return operation; }
    public WorkSource workSource() { return workSource; }
    public int uid() { return uid; }
    public String statsTag() { return statsTag; }
}
