package com.frostwork.bgenhance;

import android.os.Bundle;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWireParser;
import com.frostwork.bgenhance.diagnostics.DiagnosticsWriteBatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Binder boundary with bounded parsing and bounded waiting for the asynchronous SQLite commit. */
final class DiagnosticsProviderController {
    private static final long DEFAULT_ACK_TIMEOUT_MILLIS = 4_000L;
    private static final int MAX_PERSISTENCE_WAITERS = 2;

    interface BatchSink {
        CompletableFuture<BatchResult> persist(DiagnosticsWriteBatch batch);
    }

    static final class BatchResult {
        final int accepted;
        final int dropped;
        final String errorCode;

        BatchResult(int accepted, int dropped) {
            this(accepted, dropped, "");
        }

        BatchResult(int accepted, int dropped, String errorCode) {
            this.accepted = Math.max(0, accepted);
            this.dropped = Math.max(0, dropped);
            this.errorCode = errorCode == null ? "" : errorCode;
        }
    }

    private final int moduleUid;
    private final int systemUid;
    private final int athenaUid;
    private final String currentBootId;
    private final LongSupplier currentEpoch;
    private final LongSupplier currentEpochCutoffElapsedNanos;
    private final BatchSink sink;
    private final DiagnosticsWireParser parser;
    private final long ackTimeoutMillis;
    private final Semaphore persistenceWaiters =
            new Semaphore(MAX_PERSISTENCE_WAITERS, true);

    DiagnosticsProviderController(
            int moduleUid,
            int systemUid,
            int athenaUid,
            String currentBootId,
            LongSupplier currentEpoch,
            BatchSink sink,
            DiagnosticsWireParser parser) {
        this(moduleUid, systemUid, athenaUid, currentBootId,
                currentEpoch, () -> 0L, sink, parser, DEFAULT_ACK_TIMEOUT_MILLIS);
    }

    DiagnosticsProviderController(
            int moduleUid,
            int systemUid,
            int athenaUid,
            String currentBootId,
            LongSupplier currentEpoch,
            LongSupplier currentEpochCutoffElapsedNanos,
            BatchSink sink,
            DiagnosticsWireParser parser) {
        this(moduleUid, systemUid, athenaUid, currentBootId,
                currentEpoch, currentEpochCutoffElapsedNanos,
                sink, parser, DEFAULT_ACK_TIMEOUT_MILLIS);
    }

    DiagnosticsProviderController(
            int moduleUid,
            int systemUid,
            int athenaUid,
            String currentBootId,
            LongSupplier currentEpoch,
            BatchSink sink,
            DiagnosticsWireParser parser,
            long ackTimeoutMillis) {
        this(moduleUid, systemUid, athenaUid, currentBootId,
                currentEpoch, () -> 0L, sink, parser, ackTimeoutMillis);
    }

    DiagnosticsProviderController(
            int moduleUid,
            int systemUid,
            int athenaUid,
            String currentBootId,
            LongSupplier currentEpoch,
            LongSupplier currentEpochCutoffElapsedNanos,
            BatchSink sink,
            DiagnosticsWireParser parser,
            long ackTimeoutMillis) {
        this.moduleUid = moduleUid;
        this.systemUid = systemUid;
        this.athenaUid = athenaUid;
        this.currentBootId = currentBootId == null ? "" : currentBootId;
        this.currentEpoch = currentEpoch;
        this.currentEpochCutoffElapsedNanos = currentEpochCutoffElapsedNanos;
        this.sink = sink;
        this.parser = parser == null ? new DiagnosticsWireParser() : parser;
        this.ackTimeoutMillis = Math.max(1L, ackTimeoutMillis);
    }

    Bundle handle(int callerUid, String method, Bundle extras) {
        enforceAllowedCaller(callerUid);
        long epoch = currentEpoch == null ? -1L : currentEpoch.getAsLong();
        long epochCutoffElapsedNanos = currentEpochCutoffElapsedNanos == null
                ? -1L : currentEpochCutoffElapsedNanos.getAsLong();
        if (BridgeContract.METHOD_PING.equals(method)) {
            Bundle out = baseResponse(ready(epoch, epochCutoffElapsedNanos),
                    epoch, epochCutoffElapsedNanos);
            if (!ready(epoch, epochCutoffElapsedNanos)) {
                out.putString(BridgeContract.EXTRA_ERROR, "bridge_not_ready");
            }
            return out;
        }
        if (!BridgeContract.METHOD_REPORT_BATCH.equals(method)) {
            Bundle out = baseResponse(false, epoch, epochCutoffElapsedNanos);
            out.putString(BridgeContract.EXTRA_ERROR, "unsupported_bridge_method");
            return out;
        }
        if (!canReportHookBatch(callerUid)) {
            return rejectedBatch(
                    epoch, epochCutoffElapsedNanos, "sender_identity_mismatch", 1);
        }
        if (!ready(epoch, epochCutoffElapsedNanos) || extras == null) {
            return rejectedBatch(
                    epoch,
                    epochCutoffElapsedNanos,
                    extras == null ? "missing_extras" : "bridge_not_ready",
                    1);
        }

        DiagnosticsWireParser.ParseResult parsed = parser.parseSnapshot(extras);
        if (!parsed.accepted()) {
            return rejectedBatch(
                    epoch, epochCutoffElapsedNanos, parsed.errorCode, parsed.droppedItems);
        }
        DiagnosticsWriteBatch.BatchIdentity identity = parsed.batch.batchIdentity;
        if (identity == null || !processIdMatchesCaller(callerUid, identity.processId)) {
            return rejectedBatch(epoch, epochCutoffElapsedNanos,
                    "sender_identity_mismatch", parsed.acceptedItems);
        }
        if (!currentBootId.equals(identity.bootId)) {
            return rejectedBatch(
                    epoch, epochCutoffElapsedNanos, "boot_mismatch", parsed.acceptedItems);
        }
        if (identity.epoch != epoch) {
            return rejectedBatch(
                    epoch, epochCutoffElapsedNanos, "epoch_mismatch", parsed.acceptedItems);
        }

        BatchResult accepted;
        if (!persistenceWaiters.tryAcquire()) {
            return rejectedBatch(
                    epoch, epochCutoffElapsedNanos, "backend_busy", parsed.acceptedItems);
        }
        try {
            accepted = awaitPersistence(sink.persist(parsed.batch));
        } catch (Throwable error) {
            accepted = new BatchResult(0, 0, "backend_failure");
        } finally {
            persistenceWaiters.release();
        }
        long responseEpoch = currentEpoch == null ? -1L : currentEpoch.getAsLong();
        long responseCutoffElapsedNanos = currentEpochCutoffElapsedNanos == null
                ? -1L : currentEpochCutoffElapsedNanos.getAsLong();
        boolean currentIdentity = responseEpoch == identity.epoch;
        boolean complete = currentIdentity
                && accepted.accepted == parsed.acceptedItems
                && accepted.dropped == 0;
        Bundle out = baseResponse(
                complete, responseEpoch, responseCutoffElapsedNanos);
        out.putInt(BridgeContract.EXTRA_ACCEPTED_ITEMS,
                currentIdentity ? accepted.accepted : 0);
        out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, accepted.dropped);
        if (!complete) out.putString(
                BridgeContract.EXTRA_ERROR,
                !currentIdentity
                        ? "epoch_mismatch"
                        : accepted.errorCode.isEmpty() ? "backend_busy" : accepted.errorCode);
        return out;
    }

    private BatchResult awaitPersistence(CompletableFuture<BatchResult> completion) {
        if (completion == null) return new BatchResult(0, 0, "backend_failure");
        try {
            BatchResult result = completion.get(ackTimeoutMillis, TimeUnit.MILLISECONDS);
            return result == null ? new BatchResult(0, 0, "backend_failure") : result;
        } catch (TimeoutException timeout) {
            return new BatchResult(0, 0, "backend_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new BatchResult(0, 0, "backend_interrupted");
        } catch (ExecutionException failure) {
            return new BatchResult(0, 0, "backend_failure");
        }
    }

    boolean isAllowedCaller(int uid) {
        return uid >= 0 && (uid == moduleUid
                || uid == systemUid
                || (athenaUid >= 0 && uid == athenaUid));
    }

    private boolean canReportHookBatch(int uid) {
        return uid >= 0 && uid != moduleUid
                && (uid == systemUid || (athenaUid >= 0 && uid == athenaUid));
    }

    private boolean processIdMatchesCaller(int uid, String processId) {
        if (!canReportHookBatch(uid)) return false;
        boolean isSystem = systemUid >= 0 && uid == systemUid;
        boolean isAthena = athenaUid >= 0 && uid == athenaUid;
        // Athena can run under the system UID, which Binder cannot disambiguate further.
        if (isSystem && isAthena) {
            return BridgeContract.PROCESS_SYSTEM_SERVER.equals(processId)
                    || BridgeContract.PROCESS_ATHENA.equals(processId);
        }
        return isSystem
                ? BridgeContract.PROCESS_SYSTEM_SERVER.equals(processId)
                : BridgeContract.PROCESS_ATHENA.equals(processId);
    }

    private void enforceAllowedCaller(int uid) {
        if (!isAllowedCaller(uid)) throw new SecurityException("diagnostics caller rejected");
    }

    private boolean ready(long epoch, long epochCutoffElapsedNanos) {
        return sink != null && !currentBootId.isEmpty()
                && epoch >= 0L && epochCutoffElapsedNanos >= 0L;
    }

    private Bundle rejectedBatch(
            long epoch,
            long epochCutoffElapsedNanos,
            String errorCode,
            int droppedItems) {
        Bundle out = baseResponse(false, epoch, epochCutoffElapsedNanos);
        out.putString(BridgeContract.EXTRA_ERROR, errorCode);
        out.putInt(BridgeContract.EXTRA_ACCEPTED_ITEMS, 0);
        out.putInt(BridgeContract.EXTRA_DROPPED_ITEMS, Math.max(0, droppedItems));
        return out;
    }

    private Bundle baseResponse(
            boolean ok, long epoch, long epochCutoffElapsedNanos) {
        Bundle out = new Bundle();
        out.putBoolean(BridgeContract.EXTRA_OK, ok);
        out.putInt(BridgeContract.EXTRA_BRIDGE_VERSION, BridgeContract.BRIDGE_VERSION);
        out.putLong(BridgeContract.EXTRA_DIAGNOSTICS_EPOCH, epoch);
        out.putLong(
                BridgeContract.EXTRA_DIAGNOSTICS_EPOCH_CUTOFF_ELAPSED_NANOS,
                epochCutoffElapsedNanos);
        out.putString(BridgeContract.EXTRA_BOOT_ID, currentBootId);
        return out;
    }
}
