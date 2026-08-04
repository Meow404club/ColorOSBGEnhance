package com.frostwork.bgenhance.ui;

import android.content.Context;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Lightweight, generation-aware owner for current-boot Hook installation state. */
public final class HookStatusViewModel implements AutoCloseable {
    private static final int MAX_HOOK_STATES = 256;

    public enum Status { IDLE, LOADING, READY, ERROR }

    public enum Installation { INSTALLED, FAILED, UNSUPPORTED, UNKNOWN }

    public interface Source {
        String currentBootId();
        CompletableFuture<DiagnosticsQuery.HookStatePage> queryHookStatesAsync(int limit);
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        private final Status status;
        private final String bootId;
        private final List<DiagnosticsHookState> rows;
        private final String errorCode;

        private State(
                Status status,
                String bootId,
                List<DiagnosticsHookState> rows,
                String errorCode) {
            this.status = status;
            this.bootId = safe(bootId);
            this.rows = immutable(rows);
            this.errorCode = safe(errorCode);
        }

        static State idle() {
            return new State(Status.IDLE, "", Collections.emptyList(), "");
        }

        static State loading() {
            return new State(Status.LOADING, "", Collections.emptyList(), "");
        }

        static State ready(String bootId, List<DiagnosticsHookState> rows) {
            ArrayList<DiagnosticsHookState> current = new ArrayList<>();
            if (bootId != null && !bootId.isEmpty() && rows != null) {
                for (DiagnosticsHookState row : rows) {
                    if (row != null && bootId.equals(row.bootId)) current.add(row);
                }
            }
            return new State(Status.READY, bootId, current, "");
        }

        static State error(String errorCode) {
            String normalized = safe(errorCode);
            return new State(Status.ERROR, "", Collections.emptyList(),
                    normalized.isEmpty() ? "database_unavailable" : normalized);
        }

        public Status status() {
            return status;
        }

        public String bootId() {
            return bootId;
        }

        public List<DiagnosticsHookState> rows() {
            return rows;
        }

        public String errorCode() {
            return errorCode;
        }

        public boolean hasProcessReport(String processId) {
            if (status != Status.READY) return false;
            for (DiagnosticsHookState row : rows) {
                if (same(processId, row.processId)
                        && DiagnosticsTargetIds.RUNTIME_PROCESS.equals(row.hookTargetId)) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasCurrentProcess(String processId, int localModuleVersionCode) {
            if (status != Status.READY) return false;
            for (DiagnosticsHookState row : rows) {
                if (same(processId, row.processId)
                        && DiagnosticsTargetIds.RUNTIME_PROCESS.equals(row.hookTargetId)
                        && ModuleRuntimeStatus.isCurrentVersion(
                                row.installStatus, localModuleVersionCode)) {
                    return true;
                }
            }
            return false;
        }

        public int configRevision(String processId) {
            if (status != Status.READY) return -1;
            int revision = -1;
            for (DiagnosticsHookState row : rows) {
                if (same(processId, row.processId)
                        && DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId)
                        && row.installStatus.startsWith("CONFIG_READY")) {
                    revision = Math.max(revision, row.configRevision);
                }
            }
            return revision;
        }

        public boolean hasConfigError(String processId) {
            if (status != Status.READY) return false;
            for (DiagnosticsHookState row : rows) {
                if (same(processId, row.processId)
                        && DiagnosticsTargetIds.RUNTIME_CONFIG.equals(row.hookTargetId)
                        && safe(row.installStatus).startsWith("CONFIG_ERROR")) {
                    return true;
                }
            }
            return false;
        }

        public Installation installation(String hookTargetId) {
            if (status != Status.READY || hookTargetId == null || hookTargetId.isEmpty()) {
                return Installation.UNKNOWN;
            }
            boolean failed = false;
            boolean unsupported = false;
            String expectedProcessId = HookTargetIds.expectedProcessId(hookTargetId);
            for (DiagnosticsHookState row : rows) {
                if (!hookTargetId.equals(row.hookTargetId)) continue;
                if (!expectedProcessId.isEmpty()
                        && !expectedProcessId.equals(row.processId)) continue;
                String value = safe(row.installStatus);
                if (value.startsWith("INSTALLED")) return Installation.INSTALLED;
                if (value.startsWith("FAILED")) failed = true;
                if (value.startsWith("UNSUPPORTED")) unsupported = true;
            }
            if (failed) return Installation.FAILED;
            if (unsupported) return Installation.UNSUPPORTED;
            return Installation.UNKNOWN;
        }

        public boolean isInstalled(String hookTargetId) {
            return installation(hookTargetId) == Installation.INSTALLED;
        }

        public boolean isExplicitlyUnavailable(String hookTargetId) {
            Installation installation = installation(hookTargetId);
            return installation == Installation.FAILED
                    || installation == Installation.UNSUPPORTED;
        }

        private static boolean same(String expected, String actual) {
            return expected != null && expected.equals(actual);
        }

        private static List<DiagnosticsHookState> immutable(List<DiagnosticsHookState> rows) {
            if (rows == null || rows.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(rows));
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    private final Object lock = new Object();
    private final Source source;
    private final Executor callbackExecutor;
    private State state = State.idle();
    private Listener listener;
    private long generation;
    private boolean closed;

    public HookStatusViewModel(Source source, Executor callbackExecutor) {
        this.source = Objects.requireNonNull(source, "source");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    public static HookStatusViewModel create(Context context) {
        Context requested = Objects.requireNonNull(context, "context");
        Context application = requested.getApplicationContext();
        if (application == null) application = requested;
        ResidencyDiagnosticsBackend backend = ModuleApplication.diagnosticsBackend();
        if (backend == null) backend = ModuleApplication.initializeDiagnostics(application);
        ResidencyDiagnosticsBackend sharedBackend = backend;
        Source source = new Source() {
            @Override
            public String currentBootId() {
                return ModuleApplication.diagnosticsBootId();
            }

            @Override
            public CompletableFuture<DiagnosticsQuery.HookStatePage> queryHookStatesAsync(
                    int limit) {
                return sharedBackend.queryHookStatesAsync(limit);
            }
        };
        return new HookStatusViewModel(source, application.getMainExecutor());
    }

    public void setListener(Listener listener) {
        State snapshot;
        long currentGeneration;
        synchronized (lock) {
            if (closed) return;
            this.listener = listener;
            snapshot = state;
            currentGeneration = generation;
        }
        dispatch(currentGeneration, snapshot);
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public void refresh() {
        long requestGeneration;
        synchronized (lock) {
            if (closed) return;
            requestGeneration = ++generation;
        }
        publish(requestGeneration, State.loading());

        String requestedBootId;
        CompletableFuture<DiagnosticsQuery.HookStatePage> future;
        try {
            requestedBootId = safeBootId(source.currentBootId());
            if (requestedBootId.isEmpty()) {
                publish(requestGeneration, State.error("boot_unavailable"));
                return;
            }
            future = source.queryHookStatesAsync(MAX_HOOK_STATES);
        } catch (Throwable error) {
            publish(requestGeneration, State.error("database_unavailable"));
            return;
        }
        if (future == null) {
            publish(requestGeneration, State.error("database_unavailable"));
            return;
        }

        future.whenComplete((page, failure) -> {
            if (failure != null || page == null) {
                publish(requestGeneration, State.error("database_unavailable"));
                return;
            }
            if (!page.errorCode.isEmpty()) {
                publish(requestGeneration, State.error(page.errorCode));
                return;
            }
            String completionBootId;
            try {
                completionBootId = safeBootId(source.currentBootId());
            } catch (Throwable error) {
                publish(requestGeneration, State.error("boot_unavailable"));
                return;
            }
            if (!requestedBootId.equals(completionBootId)) {
                publish(requestGeneration, State.error("boot_changed"));
                return;
            }
            publish(requestGeneration, State.ready(requestedBootId, page.items));
        });
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            listener = null;
        }
    }

    private void publish(long requestGeneration, State next) {
        synchronized (lock) {
            if (closed || generation != requestGeneration) return;
            state = next;
        }
        dispatch(requestGeneration, next);
    }

    private void dispatch(long requestGeneration, State expected) {
        try {
            callbackExecutor.execute(() -> {
                Listener current;
                synchronized (lock) {
                    if (closed || generation != requestGeneration || state != expected) return;
                    current = listener;
                }
                if (current != null) {
                    try {
                        current.onStateChanged(expected);
                    } catch (RuntimeException ignored) {
                        // Rendering failures must not affect Hook state collection.
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // A detached UI executor must not affect the diagnostics backend.
        }
    }

    private static String safeBootId(String value) {
        return value == null ? "" : value.trim();
    }
}
