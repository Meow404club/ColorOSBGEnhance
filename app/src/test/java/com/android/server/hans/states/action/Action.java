package com.android.server.hans.states.action;

import com.android.server.hans.OplusHansPackage;

/** Anonymous firmware-shaped Action fixture with unsupported overloads. */
public abstract class Action {
    protected final OplusHansPackage mHansPackage;

    protected Action(OplusHansPackage hansPackage) {
        this.mHansPackage = hansPackage;
    }

    public void proxyService() {}
    public static void proxyService(Integer ignored) {}
    public boolean proxyService(String ignored) { return false; }

    public void proxyBroadcast() {}
    public static void proxyBroadcast(Integer ignored) {}
    public boolean proxyBroadcast(String ignored) { return false; }

    public void proxyJob() {}
    public static void proxyJob(Integer ignored) {}
    public boolean proxyJob(String ignored) { return false; }

    public void proxySensor() {}
    public static void proxySensor(Integer ignored) {}
    public boolean proxySensor(String ignored) { return false; }

    public void proxyBinder() {}
    public static void proxyBinder(Integer ignored) {}
    public boolean proxyBinder(String ignored) { return false; }

    public void manageAlarm() {}
    public static void manageAlarm(Integer ignored) {}
    public boolean manageAlarm(String ignored) { return false; }

    public void proxyWakeLock() {}
    public static void proxyWakeLock(Integer ignored) {}
    public boolean proxyWakeLock(String ignored) { return false; }

    public void proxyGPS() {}
    public static void proxyGPS(Integer ignored) {}
    public boolean proxyGPS(String ignored) { return false; }

    public void proxyAudio() {}
    public static void proxyAudio(Integer ignored) {}
    public boolean proxyAudio(String ignored) { return false; }

    public void proxyBtScan() {}
    public static void proxyBtScan(Integer ignored) {}
    public boolean proxyBtScan(String ignored) { return false; }
}
