package com.frostwork.bgenhance;

import android.os.Bundle;
import android.os.Process;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWriteBatch;
import java.util.function.LongSupplier;

/** Pure Binder-thread boundary: fixed caller checks, bounded parsing, enqueue, and ACK only. */
final class DiagnosticsProviderController {
    interface BatchSink {
        BatchResult enqueue(DiagnosticsWriteBatch batch);
    }

    static final class BatchResult {
        final int accepted;
        final int dropped;

        BatchResult(int accepted, int dropped) {
            this.accepted = Math.max(0, accepted);
            this.dropped = Math.max(0, dropped);
        }
    }

    private final int moduleUid;
    private final int athenaUid;
    private final String currentBootId;
    private final LongSupplier currentEpoch;
    private final BatchSink sink;
    private final DiagnosticsWireParser parser;

    DiagnosticsProviderController(
            int moduleUid,
            int athenaUid,
            String currentBootId,
            LongSupplier currentEpoch,
            BatchSink sink,
            DiagnosticsWireParser parser) {
        this.moduleUid = moduleUid;
        this.athenaUid = athenaUid;
        this.currentBootId = currentBootId == null ? "" : currentBootId;
        this.currentEpoch = currentEpoch;
        this.sink = sink;
        this.parser = parser == null ? new DiagnosticsWireParser() : parser;
    }

    Bundle handle(int callerUid, String method, Bundle extras) {
        enforceAllowedCaller(callerUid);
        long epoch = currentEpoch == null ? -1L : currentEpoch.getAsLong();
        if (BridgeContract.METHOD_PING.equals(method)) {
            Bundle out = baseResponse(ready(epoch), epoch);
            if (!ready(epoch)) out.putString(BridgeContract.EXTRA_ERROR, "bridge_not_ready");
            return out;
        }
        if (!BridgeContract.METHOD_REPORT_BATCH.equals(method)) {
            Bundle out = baseResponse(false, epoch);
            out.putString(BridgeContract.EXTRA_ERROR, "unsupported_bridge_method");
            return out;
        }
        if (!ready(epoch) || extras == null) {
            Bundle out = baseResponse(false, epoch);
            out.putString(BridgeContract.EXTRA_ERROR,
                    extras == null ? "missing_extras" : "bridge_not_ready");
            out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, 1);
            return out;
        }

        DiagnosticsWireParser.ParseResult parsed = parser.parseSnapshot(extras);
        if (!parsed.accepted()) {
            Bundle out = baseResponse(false, epoch);
            out.putString(BridgeContract.EXTRA_ERROR, parsed.errorCode);
            out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, parsed.droppedItems);
            return out;
        }
        DiagnosticsWriteBatch.BatchIdentity identity = parsed.batch.batchIdentity;
        if (identity == null || !currentBootId.equals(identity.bootId)) {
            Bundle out = baseResponse(false, epoch);
            out.putString(BridgeContract.EXTRA_ERROR, "boot_mismatch");
            out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, parsed.acceptedItems);
            return out;
        }
        if (identity.epoch != epoch) {
            Bundle out = baseResponse(false, epoch);
            out.putString(BridgeContract.EXTRA_ERROR, "epoch_mismatch");
            out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, parsed.acceptedItems);
            return out;
        }

        BatchResult accepted = sink.enqueue(parsed.batch);
        boolean complete = accepted.accepted == parsed.acceptedItems && accepted.dropped == 0;
        Bundle out = baseResponse(complete, epoch);
        out.putInt(BridgeContract.EXTRA_ACCEPTED_ITEMS, accepted.accepted);
        out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, accepted.dropped);
        if (!complete) out.putString(BridgeContract.EXTRA_ERROR, "backend_busy");
        return out;
    }

    boolean isAllowedCaller(int uid) {
        return uid == moduleUid
                || uid == Process.SYSTEM_UID
                || (athenaUid >= 0 && uid == athenaUid);
    }

    private void enforceAllowedCaller(int uid) {
        if (!isAllowedCaller(uid)) throw new SecurityException("diagnostics caller rejected");
    }

    private boolean ready(long epoch) {
        return sink != null && !currentBootId.isEmpty() && epoch >= 0L;
    }

    private Bundle baseResponse(boolean ok, long epoch) {
        Bundle out = new Bundle();
        out.putBoolean(BridgeContract.EXTRA_OK, ok);
        out.putInt(BridgeContract.EXTRA_BRIDGE_VERSION, BridgeContract.BRIDGE_VERSION);
        out.putLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH, epoch);
        out.putString(BridgeContract.EXTRA_BOOT_ID, currentBootId);
        return out;
    }
}
