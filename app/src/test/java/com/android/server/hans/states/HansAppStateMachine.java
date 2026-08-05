package com.android.server.hans.states;

import android.os.Message;
import com.android.server.hans.OplusHansPackage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Firmware-shaped fixture for reason-aware refreeze reflection tests. */
public class HansAppStateMachine {
    private OplusHansPackage mHansPkg;
    private String mUnfreezeReason;
    private boolean mMtoFinFastFreeze;
    private final List<Integer> sentMessages = new ArrayList<>();
    private final List<Object> sentMessageObjects = new ArrayList<>();

    public HansAppStateMachine(OplusHansPackage hansPackage, String reason) {
        this.mHansPkg = hansPackage;
        this.mUnfreezeReason = reason;
    }

    public void setReason(String reason) {
        mUnfreezeReason = reason;
    }

    public void sendMessage(int what) {
        sentMessages.add(what);
        sentMessageObjects.add(null);
    }

    public void sendMessage(int what, Object object) {
        sentMessages.add(what);
        sentMessageObjects.add(object);
    }

    public void setMtoFinFastFreeze(boolean pending) {
        mMtoFinFastFreeze = pending;
    }

    public boolean isMtoFinFastFreeze() {
        return mMtoFinFastFreeze;
    }

    public List<Integer> sentMessages() {
        return Collections.unmodifiableList(sentMessages);
    }

    public List<Object> sentMessageObjects() {
        return Collections.unmodifiableList(sentMessageObjects);
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
