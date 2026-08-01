package com.frostwork.bgenhance;

public final class HookEvent {
    public final String feature;
    public final int code;
    public final int uid;
    public final String packageName;
    public final String processName;
    public final String scene;

    public HookEvent(String feature, int code, int uid,
                     String packageName, String processName, String scene) {
        this.feature = feature;
        this.code = code;
        this.uid = uid;
        this.packageName = packageName == null ? "" : packageName;
        this.processName = processName == null ? "" : processName;
        this.scene = scene == null ? "" : scene;
    }

    @Override
    public String toString() {
        return "feature=" + feature
                + " code=" + code
                + " uid=" + uid
                + " pkg=" + packageName
                + " proc=" + processName
                + " scene=" + scene;
    }
}
