package com.android.server.hans.scene;

import android.util.SparseArray;
import java.util.ArrayList;
import java.util.List;

/** Firmware-shaped FAST scene fixture with invalid overload decoys. */
public class HansSceneManager {
    private String enterFastFreezer(
            int uid, String packageName, int reasonCode, String reason) {
        return packageName;
    }

    private ArrayList<Object> fastFreezeAppList(SparseArray<Object> candidates) {
        return new ArrayList<>();
    }

    static String access$enterFastFreezer(
            HansSceneManager manager,
            int uid,
            String packageName,
            int reasonCode,
            String reason) {
        return manager.enterFastFreezer(uid, packageName, reasonCode, reason);
    }

    static Bridge enterFastFreezer() {
        return (manager, uid, packageName, reasonCode, reason) ->
                manager.enterFastFreezer(uid, packageName, reasonCode, reason);
    }

    private static String enterFastFreezer(
            Integer uid, String packageName, int reasonCode, String reason) {
        return packageName;
    }

    static String access$enterFastFreezer(
            Object manager,
            int uid,
            String packageName,
            int reasonCode,
            String reason) {
        return packageName;
    }

    private CharSequence enterFastFreezer(
            int uid, String packageName, long reasonCode, String reason) {
        return packageName;
    }

    public String enterFastFreezer(
            int uid, CharSequence packageName, int reasonCode, String reason) {
        return packageName == null ? null : packageName.toString();
    }

    private static ArrayList<Object> fastFreezeAppList(Object candidates) {
        return new ArrayList<>();
    }

    private List<Object> fastFreezeAppList(List<Object> candidates) {
        return candidates;
    }

    public ArrayList<Object> fastFreezeAppList(long candidateCount) {
        return new ArrayList<>();
    }

    interface Bridge {
        String call(
                HansSceneManager manager,
                int uid,
                String packageName,
                int reasonCode,
                String reason);
    }
}
