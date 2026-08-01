package com.android.server.am;

public final class OplusOsenseKillAction {
    public static class KillContext {
        public int contextType;
        public String scene;

        public KillContext(int contextType, String scene) {
            this.contextType = contextType;
            this.scene = scene;
        }
    }

    private void killOneProcessLocked(
            KillContext context, ProcessInfo processInfo, String reason, int subReasonCode) {}

    private void killOneProcessLocked(
            KillContext context, ProcessInfo processInfo, String reason) {}
}
