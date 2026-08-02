package e4;

import com.oplus.app.athena.ProcDetailInfo;

/** Minimal process wrapper with the verified Athena getter ABI. */
public class a0 {
    private final ProcDetailInfo process;

    public a0(ProcDetailInfo process) {
        this.process = process;
    }

    public String h() { return process.pkgName; }
    public String k() { return process.procName; }
    public int i() { return process.pid; }
    public int p() { return process.uid; }
    public int q() { return process.userId; }
    public ProcDetailInfo j() { return process; }
}
