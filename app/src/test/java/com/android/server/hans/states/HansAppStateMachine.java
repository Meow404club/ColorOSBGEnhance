package com.android.server.hans.states;

import android.os.Message;
import com.android.server.hans.OplusHansPackage;

/** Firmware-shaped fixture for reason-aware refreeze reflection tests. */
public class HansAppStateMachine {
    private OplusHansPackage mHansPkg;
    private String mUnfreezeReason;

    public HansAppStateMachine(OplusHansPackage hansPackage, String reason) {
        this.mHansPkg = hansPackage;
        this.mUnfreezeReason = reason;
    }

    public void setReason(String reason) {
        mUnfreezeReason = reason;
    }

    public class MiddleState {
        public void enter() {}
        public void exit() {}
        public boolean processMessage(Message message) { return false; }

        public static void enter(Integer ignored) {}
        public int exit(String ignored) { return 0; }
        public static boolean processMessage(Object ignored) { return false; }
    }

    public class FrozenState {
        private void transToMiddle() {}

        public void transToMiddle(int ignored) {}
        private static void transToMiddle(String ignored) {}
    }
}
