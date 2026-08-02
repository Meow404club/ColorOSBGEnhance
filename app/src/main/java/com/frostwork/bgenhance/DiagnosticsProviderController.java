package com.frostwork.bgenhance;

import android.os.Bundle;
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
    private final int systemUid;
    private final int athenaUid;
    private final String currentBootId;
    private final LongSupplier currentEpoch;
    private final BatchSink sink;
    private final DiagnosticsWireParser parser;

    DiagnosticsProviderController(
            int moduleUid,
            int systemUid,
            int athenaUid,
            String currentBootId,
            LongSupplier currentEpoch,
            BatchSink sink,
            DiagnosticsWireParser parser) {
        this.moduleUid = moduleUid;
        this.systemUid = systemUid;
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
        String expectedProcessId = reportProcessIdForCaller(callerUid);
        if (expectedProcessId == null) {
            return rejectedBatch(epoch, "sender_identity_mismatch", 1);
        }
        if (!ready(epoch) || extras == null) {
            return rejectedBatch(
                    epoch, extras == null ? "missing_extras" : "bridge_not_ready", 1);
        }

        DiagnosticsWireParser.ParseResult parsed = parser.parseSnapshot(extras);
        if (!parsed.accepted()) {
            return rejectedBatch(epoch, parsed.errorCode, parsed.droppedItems);
        }
        DiagnosticsWriteBatch.BatchIdentity identity = parsed.batch.batchIdentity;
        if (identity == null || !expectedProcessId.equals(identity.processId)) {
            return rejectedBatch(epoch, "sender_identity_mismatch", parsed.acceptedItems);
        }
        if (!currentBootId.equals(identity.bootId)) {
            return rejectedBatch(epoch, "boot_mismatch", parsed.acceptedItems);
        }
        if (identity.epoch != epoch) {
            return rejectedBatch(epoch, "epoch_mismatch", parsed.acceptedItems);
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
        return uid >= 0 && (uid == moduleUid
                || uid == systemUid
                || (athenaUid >= 0 && uid == athenaUid));
    }

    private String reportProcessIdForCaller(int uid) {
        if (uid < 0 || uid == moduleUid) return null;
        boolean isSystem = systemUid >= 0 && uid == systemUid;
        boolean isAthena = athenaUid >= 0 && uid == athenaUid;
        if (isSystem == isAthena) return null;
        return isSystem
                ? BridgeContract.PROCESS_SYSTEM_SERVER
                : BridgeContract.PROCESS_ATHENA;
    }

    private void enforceAllowedCaller(int uid) {
        if (!isAllowedCaller(uid)) throw new SecurityException("diagnostics caller rejected");
    }

    private boolean ready(long epoch) {
        return sink != null && !currentBootId.isEmpty() && epoch >= 0L;
    }

    private Bundle rejectedBatch(long epoch, String errorCode, int droppedItems) {
        Bundle out = baseResponse(false, epoch);
        out.putString(BridgeContract.EXTRA_ERROR, errorCode);
        out.putInt(BridgeContract.EXTRA_ACCEPTED_ITEMS, 0);
        out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, Math.max(0, droppedItems));
        return out;
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
