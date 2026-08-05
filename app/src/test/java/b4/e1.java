package b4;

import com.oplus.app.athena.ClearRecord;
import com.oplus.app.athena.KeepRecord;
import com.oplus.app.athena.ProcDetailInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Descriptor-only fixture for periodic deep-clear terminals. */
public class e1 {
    private void h(
            String key, int limit, ProcDetailInfo process, ClearRecord record) {}

    private void l(
            List processes, ClearRecord clearRecord, KeepRecord keepRecord, int count) {}

    private void j(
            List processes,
            Map processMap,
            ClearRecord clearRecord,
            KeepRecord keepRecord,
            int count) {
        h("first", count, null, clearRecord);
        h("second", count, null, clearRecord);
        h("third", count, null, clearRecord);
    }

    public void k() {
        l(List.of(), new ClearRecord(), new KeepRecord(), 0);
    }

    private static void h(
            String key, Integer limit, ProcDetailInfo process, ClearRecord record) {}

    private boolean h(
            String key,
            int limit,
            ProcDetailInfo process,
            ClearRecord record,
            boolean decoy) {
        return false;
    }

    private static void l(
            ArrayList processes,
            ClearRecord clearRecord,
            KeepRecord keepRecord,
            Integer count) {}

    private static void j(
            ArrayList processes,
            Map processMap,
            ClearRecord clearRecord,
            KeepRecord keepRecord,
            Integer count) {}

    private static void k(boolean decoy) {}
}
