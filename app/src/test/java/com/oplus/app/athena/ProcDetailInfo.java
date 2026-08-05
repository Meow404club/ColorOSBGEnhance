package com.oplus.app.athena;

/** Minimal ABI fixture for an Athena process candidate. */
public class ProcDetailInfo {
    public String pkgName;
    public String procName;
    public int uid;
    public int pid;
    public int userId;

    public ProcDetailInfo(String pkgName, String procName, int uid, int pid, int userId) {
        this.pkgName = pkgName;
        this.procName = procName;
        this.uid = uid;
        this.pid = pid;
        this.userId = userId;
    }
}
