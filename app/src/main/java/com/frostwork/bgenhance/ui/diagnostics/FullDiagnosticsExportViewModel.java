package com.frostwork.bgenhance.ui.diagnostics;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.diagnostics.AtomicSafDiagnosticsExporter;
import com.frostwork.bgenhance.diagnostics.DiagnosticsExportCodec;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.DiagnosticsReportMetadata;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Retains one explicitly authorized full-report export across configuration changes. */
public final class FullDiagnosticsExportViewModel extends ViewModel {
    public static final String ERROR_INCOMPLETE_REPORT = "incomplete_report";
    public static final String ERROR_MISSING_PAYLOAD = "missing_payload";

    private static final DateTimeFormatter EXPORT_FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public enum Status {
        IDLE,
        PREPARING,
        READY,
        AWAITING_URI,
        WRITING,
        SUCCESS,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        public final Status status;
        public final long operationId;
        public final String errorCode;
        public final boolean destinationCreated;
        public final boolean incompleteDocumentDeleted;
        public final int payloadByteCount;

        private State(
                Status status,
                long operationId,
                String errorCode,
                boolean destinationCreated,
                boolean incompleteDocumentDeleted,
                int payloadByteCount) {
            this.status = status;
            this.operationId = operationId;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.destinationCreated = destinationCreated;
            this.incompleteDocumentDeleted = incompleteDocumentDeleted;
            this.payloadByteCount = Math.max(0, payloadByteCount);
        }

        public boolean busy() {
            return status == Status.PREPARING
                    || status == Status.READY
                    || status == Status.AWAITING_URI
                    || status == Status.WRITING;
        }

        private static State idle() {
            return new State(Status.IDLE, 0L, "", false, true, 0);
        }
    }

    public static final class DestinationRequest {
        public final long operationId;
        public final String displayName;

        private DestinationRequest(long operationId, String displayName) {
            this.operationId = operationId;
            this.displayName = displayName;
        }
    }

    public static final class Completion {
        public final boolean successful;
        public final String errorCode;
        public final boolean destinationCreated;
        public final boolean incompleteDocumentDeleted;

        private Completion(State state) {
            successful = state.status == Status.SUCCESS;
            errorCode = state.errorCode;
            destinationCreated = state.destinationCreated;
            incompleteDocumentDeleted = state.incompleteDocumentDeleted;
        }
    }

    interface SnapshotSource {
        CompletableFuture<DiagnosticsQuery.ExportSnapshot> load();
    }

    interface DestinationWriter {
        WriteOutcome write(
                Uri destinationUri, String expectedDisplayName, byte[] payload);
        boolean deleteIncomplete(Uri destinationUri);
    }

    static final class WriteOutcome {
        final boolean successful;
        final String errorCode;
        final boolean incompleteDocumentDeleted;

        WriteOutcome(
                boolean successful, String errorCode, boolean incompleteDocumentDeleted) {
            this.successful = successful;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.incompleteDocumentDeleted = incompleteDocumentDeleted;
        }

        static WriteOutcome succeeded() {
            return new WriteOutcome(true, "", true);
        }

        static WriteOutcome failed(String errorCode, boolean deleted) {
            return new WriteOutcome(false, errorCode, deleted);
        }
    }

    private final Object lock = new Object();
    private final SnapshotSource source;
    private final DestinationWriter writer;
    private final Executor worker;
    private final Executor callbackExecutor;
    private final ExecutorService ownedWorker;
    private final LongSupplier currentTimeMillis;

    private long generation;
    private State state = State.idle();
    private Listener listener;
    private byte[] payload;
    private String displayName = "";
    private boolean closed;

    private FullDiagnosticsExportViewModel(
            ContentResolver resolver, ResidencyDiagnosticsBackend backend) {
        this(
                () -> backend.exportAsync(
                        DiagnosticsQuery.ExportOptions.fullReport(), FeatureIds.all()),
                new DestinationWriter() {
                    @Override
                    public WriteOutcome write(
                            Uri destinationUri,
                            String expectedDisplayName,
                            byte[] payload) {
                        AtomicSafDiagnosticsExporter.Result result =
                                AtomicSafDiagnosticsExporter.export(
                                        resolver, destinationUri,
                                        expectedDisplayName, payload);
                        return new WriteOutcome(
                                result.successful(), result.errorCode,
                                result.incompleteDocumentDeleted);
                    }

                    @Override
                    public boolean deleteIncomplete(Uri destinationUri) {
                        return AtomicSafDiagnosticsExporter.deleteIncomplete(
                                resolver, destinationUri);
                    }
                },
                newExportExecutor(),
                mainExecutor(),
                System::currentTimeMillis,
                true);
    }

    FullDiagnosticsExportViewModel(
            SnapshotSource source,
            DestinationWriter writer,
            Executor worker,
            Executor callbackExecutor,
            LongSupplier currentTimeMillis) {
        this(source, writer, worker, callbackExecutor, currentTimeMillis, false);
    }

    private FullDiagnosticsExportViewModel(
            SnapshotSource source,
            DestinationWriter writer,
            Executor worker,
            Executor callbackExecutor,
            LongSupplier currentTimeMillis,
            boolean ownsWorker) {
        this.source = Objects.requireNonNull(source, "source");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis, "currentTimeMillis");
        ownedWorker = ownsWorker && worker instanceof ExecutorService
                ? (ExecutorService) worker : null;
    }

    public static FullDiagnosticsExportViewModel create(
            ViewModelStoreOwner owner,
            Context context,
            ResidencyDiagnosticsBackend backend) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(backend, "backend");
        Context application = context.getApplicationContext();
        ContentResolver resolver = (application == null ? context : application)
                .getContentResolver();
        ViewModelProvider.Factory factory = new ViewModelProvider.Factory() {
            @Override
            public <T extends ViewModel> T create(Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(
                        FullDiagnosticsExportViewModel.class)) {
                    throw new IllegalArgumentException("unsupported view model");
                }
                return modelClass.cast(
                        new FullDiagnosticsExportViewModel(resolver, backend));
            }
        };
        return new ViewModelProvider(owner, factory)
                .get(FullDiagnosticsExportViewModel.class);
    }

    public void setListener(Listener requestedListener) {
        State snapshot;
        synchronized (lock) {
            if (closed) return;
            listener = requestedListener;
            snapshot = state;
        }
        if (requestedListener != null) emit(snapshot);
    }

    public void removeListener(Listener requestedListener) {
        synchronized (lock) {
            if (listener == requestedListener) listener = null;
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean prepare(DiagnosticsReportMetadata metadata) {
        final long operationId;
        final State preparing;
        synchronized (lock) {
            if (closed || state.status != Status.IDLE) return false;
            operationId = ++generation;
            payload = null;
            displayName = "";
            preparing = new State(
                    Status.PREPARING, operationId, "", false, true, 0);
            state = preparing;
        }
        emit(preparing);

        CompletableFuture<DiagnosticsQuery.ExportSnapshot> future;
        try {
            future = source.load();
        } catch (Throwable error) {
            finishError(operationId, "report_unavailable", false, true);
            return true;
        }
        if (future == null) {
            finishError(operationId, "report_unavailable", false, true);
            return true;
        }
        DiagnosticsReportMetadata safeMetadata = metadata == null
                ? DiagnosticsReportMetadata.empty() : metadata;
        future.whenComplete((snapshot, error) -> executeWorker(
                () -> preparePayload(operationId, snapshot, error, safeMetadata),
                () -> finishError(
                        operationId, "report_unavailable", false, true)));
        return true;
    }

    public DestinationRequest takeDestinationRequest() {
        State awaiting;
        DestinationRequest request;
        synchronized (lock) {
            if (closed || state.status != Status.READY
                    || payload == null || displayName.isEmpty()) return null;
            request = new DestinationRequest(state.operationId, displayName);
            awaiting = new State(
                    Status.AWAITING_URI,
                    state.operationId,
                    "",
                    false,
                    true,
                    payload.length);
            state = awaiting;
        }
        emit(awaiting);
        return request;
    }

    public void destinationLaunchFailed(long operationId) {
        finishError(operationId, "destination_unavailable", false, true);
    }

    public void onDestinationSelected(Uri destinationUri) {
        if (destinationUri == null) {
            cancelAwaitingDestination();
            return;
        }

        final long operationId;
        final byte[] value;
        final String expectedDisplayName;
        final boolean missingPayload;
        final State writing;
        synchronized (lock) {
            if (closed) return;
            if (state.status == Status.AWAITING_URI && payload != null) {
                operationId = state.operationId;
                value = payload;
                expectedDisplayName = displayName;
                missingPayload = false;
            } else if (state.status == Status.IDLE) {
                operationId = ++generation;
                value = null;
                expectedDisplayName = "";
                missingPayload = true;
            } else {
                return;
            }
            writing = new State(
                    Status.WRITING,
                    operationId,
                    "",
                    true,
                    false,
                    value == null ? 0 : value.length);
            state = writing;
        }
        emit(writing);
        executeWorker(
                () -> {
                    if (missingPayload) {
                        finishError(
                                operationId,
                                ERROR_MISSING_PAYLOAD,
                                true,
                                deleteIncomplete(destinationUri));
                        return;
                    }
                    writePayload(
                            operationId, destinationUri, expectedDisplayName, value);
                },
                () -> finishError(
                        operationId,
                        "worker_unavailable",
                        true,
                        deleteIncomplete(destinationUri)));
    }

    public Completion consumeCompletion(long operationId) {
        Completion completion;
        State idle;
        synchronized (lock) {
            if (closed || state.operationId != operationId
                    || (state.status != Status.SUCCESS && state.status != Status.ERROR)) {
                return null;
            }
            completion = new Completion(state);
            generation++;
            payload = null;
            displayName = "";
            idle = State.idle();
            state = idle;
        }
        emit(idle);
        return completion;
    }

    private void preparePayload(
            long operationId,
            DiagnosticsQuery.ExportSnapshot snapshot,
            Throwable failure,
            DiagnosticsReportMetadata metadata) {
        if (!isCurrent(operationId, Status.PREPARING)) return;
        if (failure != null || snapshot == null || !snapshot.errorCode.isEmpty()) {
            finishError(operationId, "report_unavailable", false, true);
            return;
        }
        if (snapshot.exportStatus == null
                || snapshot.exportStatus.truncated
                || snapshot.exportStatus.hasErrors
                || !snapshot.packageNamesIncluded
                || !snapshot.uidsIncluded
                || !snapshot.sensitiveDetailsIncluded) {
            finishError(operationId, ERROR_INCOMPLETE_REPORT, false, true);
            return;
        }

        DiagnosticsExportCodec.EncodeResult encoded =
                DiagnosticsExportCodec.encode(snapshot, metadata);
        if (!encoded.successful()) {
            finishError(operationId, encoded.errorCode, false, true);
            return;
        }
        byte[] prepared = (encoded.json + "\n").getBytes(StandardCharsets.UTF_8);
        if (!AtomicSafDiagnosticsExporter.isValidPayload(prepared)) {
            finishError(operationId, "invalid_payload", false, true);
            return;
        }

        State ready;
        synchronized (lock) {
            if (!isCurrentLocked(operationId, Status.PREPARING)) return;
            payload = prepared;
            displayName = "BgEnhance-diagnostics-"
                    + EXPORT_FILE_TIME.format(Instant.ofEpochMilli(
                            currentTimeMillis.getAsLong()))
                    + ".json" + AtomicSafDiagnosticsExporter.PARTIAL_SUFFIX;
            ready = new State(
                    Status.READY, operationId, "", false, true, prepared.length);
            state = ready;
        }
        emit(ready);
    }

    private void writePayload(
            long operationId,
            Uri destinationUri,
            String expectedDisplayName,
            byte[] value) {
        WriteOutcome outcome;
        try {
            outcome = writer.write(destinationUri, expectedDisplayName, value);
        } catch (Throwable error) {
            outcome = WriteOutcome.failed(
                    "write_failed", deleteIncomplete(destinationUri));
        }
        if (outcome == null) {
            outcome = WriteOutcome.failed(
                    "write_failed", deleteIncomplete(destinationUri));
        }

        State completed;
        synchronized (lock) {
            if (!isCurrentLocked(operationId, Status.WRITING)) return;
            payload = null;
            displayName = "";
            completed = new State(
                    outcome.successful ? Status.SUCCESS : Status.ERROR,
                    operationId,
                    outcome.errorCode,
                    true,
                    outcome.incompleteDocumentDeleted,
                    0);
            state = completed;
        }
        emit(completed);
    }

    private void cancelAwaitingDestination() {
        State idle;
        synchronized (lock) {
            if (closed || state.status != Status.AWAITING_URI) return;
            generation++;
            payload = null;
            displayName = "";
            idle = State.idle();
            state = idle;
        }
        emit(idle);
    }

    private void finishError(
            long operationId,
            String errorCode,
            boolean destinationCreated,
            boolean incompleteDocumentDeleted) {
        State error;
        synchronized (lock) {
            if (closed || state.operationId != operationId
                    || state.status == Status.SUCCESS || state.status == Status.ERROR) return;
            payload = null;
            displayName = "";
            error = new State(
                    Status.ERROR,
                    operationId,
                    errorCode,
                    destinationCreated,
                    incompleteDocumentDeleted,
                    0);
            state = error;
        }
        emit(error);
    }

    private boolean isCurrent(long operationId, Status status) {
        synchronized (lock) {
            return isCurrentLocked(operationId, status);
        }
    }

    private boolean isCurrentLocked(long operationId, Status status) {
        return !closed && state.operationId == operationId && state.status == status;
    }

    private boolean deleteIncomplete(Uri destinationUri) {
        try {
            return writer.deleteIncomplete(destinationUri);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void executeWorker(Runnable command, Runnable rejected) {
        try {
            worker.execute(() -> {
                try {
                    command.run();
                } catch (Throwable error) {
                    rejected.run();
                }
            });
        } catch (Throwable error) {
            rejected.run();
        }
    }

    private void emit(State expected) {
        try {
            callbackExecutor.execute(() -> {
                Listener current;
                synchronized (lock) {
                    if (closed || state != expected) return;
                    current = listener;
                }
                if (current != null) current.onStateChanged(expected);
            });
        } catch (Throwable ignored) {
            // A detached UI executor cannot affect report generation or cleanup.
        }
    }

    @Override
    protected void onCleared() {
        close();
    }

    void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            listener = null;
            payload = null;
            displayName = "";
            state = State.idle();
        }
        if (ownedWorker != null) ownedWorker.shutdown();
    }

    private static ExecutorService newExportExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BgEnhanceFullDiagnosticsExport");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Executor mainExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return command -> {
            if (Looper.myLooper() == handler.getLooper()) {
                command.run();
            } else {
                handler.post(command);
            }
        };
    }
}
