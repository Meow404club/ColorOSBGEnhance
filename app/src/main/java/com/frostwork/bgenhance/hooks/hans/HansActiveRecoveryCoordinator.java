package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryAdapter.Outcome;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.DesiredState;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.RecoveryIntent;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryPlanner.Revalidation;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Serializes recovery planning while isolating blocking platform calls on one worker. */
public final class HansActiveRecoveryCoordinator {
    static final int MAX_PENDING_UIDS = 512;
    static final int MAX_EXHAUSTED_UIDS = MAX_PENDING_UIDS * 2;
    static final long CALL_TIMEOUT_MS = 15_000L;
    static final long[] RETRY_DELAYS_MS = {
            1_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L
    };

    private final HansActiveRecoveryPlanner planner;
    private final RecoveryScheduler scheduler;
    private final RecoveryCallExecutor callExecutor;
    private final RuntimeStateSource runtimeStateSource;
    private final UidPackageSource uidPackageSource;
    private final RecoveryAction recoveryAction;
    private final DiagnosticsRecorder diagnosticsRecorder;
    private final FatalFailureListener fatalFailureListener;
    private final int maxPendingUids;
    private final Map<Integer, Request> requests = new LinkedHashMap<>();
    private final Map<Integer, Request> exhaustedRequests = new LinkedHashMap<>();
    private final Map<Integer, RetryToken> retryTokens = new LinkedHashMap<>();
    private final AtomicBoolean reconcileQueued = new AtomicBoolean();
    private final AtomicBoolean reconcileAgain = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean fatalNotified = new AtomicBoolean();
    private volatile CallToken inFlight;
    private long nextSequence;

    public HansActiveRecoveryCoordinator(
            HansActiveRecoveryPlanner planner,
            RecoveryScheduler scheduler,
            RecoveryCallExecutor callExecutor,
            RuntimeStateSource runtimeStateSource,
            UidPackageSource uidPackageSource,
            RecoveryAction recoveryAction,
            DiagnosticsRecorder diagnosticsRecorder) {
        this(
                planner,
                scheduler,
                callExecutor,
                runtimeStateSource,
                uidPackageSource,
                recoveryAction,
                diagnosticsRecorder,
                FatalFailureListener.NO_OP,
                MAX_PENDING_UIDS);
    }

    public HansActiveRecoveryCoordinator(
            HansActiveRecoveryPlanner planner,
            RecoveryScheduler scheduler,
            RecoveryCallExecutor callExecutor,
            RuntimeStateSource runtimeStateSource,
            UidPackageSource uidPackageSource,
            RecoveryAction recoveryAction,
            DiagnosticsRecorder diagnosticsRecorder,
            FatalFailureListener fatalFailureListener) {
        this(
                planner,
                scheduler,
                callExecutor,
                runtimeStateSource,
                uidPackageSource,
                recoveryAction,
                diagnosticsRecorder,
                fatalFailureListener,
                MAX_PENDING_UIDS);
    }

    HansActiveRecoveryCoordinator(
            HansActiveRecoveryPlanner planner,
            RecoveryScheduler scheduler,
            RecoveryCallExecutor callExecutor,
            RuntimeStateSource runtimeStateSource,
            UidPackageSource uidPackageSource,
            RecoveryAction recoveryAction,
            DiagnosticsRecorder diagnosticsRecorder,
            int maxPendingUids) {
        this(
                planner,
                scheduler,
                callExecutor,
                runtimeStateSource,
                uidPackageSource,
                recoveryAction,
                diagnosticsRecorder,
                FatalFailureListener.NO_OP,
                maxPendingUids);
    }

    HansActiveRecoveryCoordinator(
            HansActiveRecoveryPlanner planner,
            RecoveryScheduler scheduler,
            RecoveryCallExecutor callExecutor,
            RuntimeStateSource runtimeStateSource,
            UidPackageSource uidPackageSource,
            RecoveryAction recoveryAction,
            DiagnosticsRecorder diagnosticsRecorder,
            FatalFailureListener fatalFailureListener,
            int maxPendingUids) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.callExecutor = Objects.requireNonNull(callExecutor, "callExecutor");
        this.runtimeStateSource = Objects.requireNonNull(
                runtimeStateSource, "runtimeStateSource");
        this.uidPackageSource = Objects.requireNonNull(uidPackageSource, "uidPackageSource");
        this.recoveryAction = Objects.requireNonNull(recoveryAction, "recoveryAction");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.fatalFailureListener = Objects.requireNonNull(
                fatalFailureListener, "fatalFailureListener");
        if (maxPendingUids <= 0 || maxPendingUids > MAX_PENDING_UIDS) {
            throw new IllegalArgumentException("invalid maxPendingUids");
        }
        this.maxPendingUids = maxPendingUids;
    }

    public boolean requestReconcile() {
        if (closed.get()) return false;
        reconcileAgain.set(true);
        if (!reconcileQueued.compareAndSet(false, true)) return true;
        if (execute(this::drainReconcile) == DispatchResult.ACCEPTED) return true;
        reconcileQueued.set(false);
        return false;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        reconcileAgain.set(false);
        CallToken activeCall = inFlight;
        if (activeCall != null) activeCall.cancelOemPermit();
        try {
            callExecutor.shutdown();
        } catch (Throwable ignored) {
            // The hook is already inactive.
        }
        try {
            scheduler.shutdown();
        } catch (Throwable ignored) {
            // The hook is already inactive.
        }
    }

    private void drainReconcile() {
        try {
            while (!closed.get() && reconcileAgain.getAndSet(false)) {
                ResidencyRuntimeState state = authoritativeState();
                if (state != null) reconcile(state);
            }
        } finally {
            reconcileQueued.set(false);
            if (!closed.get() && reconcileAgain.get()) requestReconcile();
        }
    }

    private void reconcile(ResidencyRuntimeState state) {
        revalidateActive(state);
        revalidateExhausted(state);

        int budget = Math.max(0, maxPendingUids - requests.size());
        HansActiveRecoveryPlanner.Update update = planner.update(state, budget);
        if (update.status() != HansActiveRecoveryPlanner.Update.Status.WAITING) {
            for (RecoveryIntent intent : update.intents()) {
                if (requests.size() >= maxPendingUids
                        && !requests.containsKey(intent.desiredState().uid())) {
                    break;
                }
                queue(intent.desiredState());
            }
            restartEligibleExhausted(state);
        }
        pump();
    }

    private void revalidateActive(ResidencyRuntimeState state) {
        for (Request request : new ArrayList<>(requests.values())) {
            Revalidation revalidation = planner.revalidate(
                    state, request.uid(), request.expectedPackages());
            if (revalidation.status() != Revalidation.Status.STABLE) continue;
            DesiredState desired = revalidation.desiredStateOrNull();
            if (desired == null || !request.samePhysicalState(desired)) {
                requests.remove(request.uid());
                cancelRetry(request.uid());
                continue;
            }
            requests.put(request.uid(), request.rebind(desired.runtimeGeneration()));
        }
    }

    private void revalidateExhausted(ResidencyRuntimeState state) {
        for (Request request : new ArrayList<>(exhaustedRequests.values())) {
            Revalidation revalidation = planner.revalidate(
                    state, request.uid(), request.expectedPackages());
            if (revalidation.status() != Revalidation.Status.STABLE) continue;
            DesiredState desired = revalidation.desiredStateOrNull();
            if (desired == null || !request.samePhysicalState(desired)) {
                exhaustedRequests.remove(request.uid());
            }
        }
    }

    private void restartEligibleExhausted(ResidencyRuntimeState state) {
        if (!state.ready() || requests.size() >= maxPendingUids) return;
        for (Request exhausted : new ArrayList<>(exhaustedRequests.values())) {
            if (requests.size() >= maxPendingUids) break;
            Revalidation revalidation = planner.revalidate(
                    state, exhausted.uid(), exhausted.expectedPackages());
            if (revalidation.status() != Revalidation.Status.STABLE) continue;
            DesiredState desired = revalidation.desiredStateOrNull();
            if (desired == null || !exhausted.samePhysicalState(desired)) {
                exhaustedRequests.remove(exhausted.uid());
                continue;
            }
            if (desired.runtimeGeneration() <= exhausted.runtimeGeneration()) continue;
            exhaustedRequests.remove(exhausted.uid());
            Request restarted = exhausted.restart(
                    nextSequence(), desired.runtimeGeneration());
            requests.put(restarted.uid(), restarted);
        }
    }

    private void queue(DesiredState desired) {
        cancelRetry(desired.uid());
        Request request = new Request(
                desired.uid(),
                desired.expectedPackages(),
                desired.runtimeGeneration(),
                desired.signature(),
                nextSequence(),
                0,
                true,
                false,
                null);
        exhaustedRequests.remove(request.uid());
        requests.put(request.uid(), request);
    }

    private void pump() {
        if (closed.get() || inFlight != null) return;
        while (true) {
            Request request = firstReadyRequest();
            if (request == null) return;
            AuthoritativeValidation validation = validateAuthoritative(request);
            if (validation.status == ValidationStatus.OBSOLETE) {
                requests.remove(request.uid());
                requestReconcile();
                continue;
            }
            if (validation.status != ValidationStatus.VALID) {
                retry(request, null);
                continue;
            }
            request = validation.request;
            requests.put(request.uid(), request);
            startPackageQuery(request);
            return;
        }
    }

    private Request firstReadyRequest() {
        for (Request request : requests.values()) {
            if (request.ready() && !request.exhausted()) return request;
        }
        return null;
    }

    private void startPackageQuery(Request request) {
        CallToken token = new CallToken(request, CallPhase.PACKAGE_QUERY);
        inFlight = token;
        ScheduleDispatch timeoutDispatch = schedule(
                () -> onTimeout(token), CALL_TIMEOUT_MS);
        token.timeoutCallback = timeoutDispatch.callback;
        if (timeoutDispatch.result != DispatchResult.ACCEPTED) {
            inFlight = null;
            if (timeoutDispatch.result == DispatchResult.REJECTED) {
                schedulingFailed(request, TerminalOutcome.SCHEDULER_REJECTED);
            }
            return;
        }
        DispatchResult callDispatch = executeCall(() -> {
            if (!callMayRun(token, CallPhase.PACKAGE_QUERY)) return;
            Set<String> packages = null;
            Throwable failure = null;
            try {
                packages = immutablePackages(
                        uidPackageSource.findPackagesForUid(request.uid()));
            } catch (Throwable error) {
                failure = error;
            }
            Set<String> result = packages;
            Throwable error = failure;
            dispatchCallback(
                    () -> onPackagesResolved(token, result, error),
                    "recovery package callback rejected");
        });
        if (callDispatch != DispatchResult.ACCEPTED) {
            inFlight = null;
            cancelTimeout(token);
            if (callDispatch == DispatchResult.REJECTED) {
                schedulingFailed(request, TerminalOutcome.CALL_EXECUTOR_REJECTED);
            }
        }
    }

    private void onPackagesResolved(CallToken token, Set<String> packages, Throwable error) {
        if (inFlight != token) return;
        cancelTimeout(token);
        if (token.timedOut) {
            inFlight = null;
            pump();
            return;
        }
        Request request = currentRequest(token.request);
        if (request == null || closed.get()) {
            inFlight = null;
            pump();
            return;
        }
        if (error != null) {
            inFlight = null;
            retry(request, null);
            pump();
            return;
        }
        if (!request.expectedPackages().equals(packages)) {
            inFlight = null;
            finish(request, TerminalOutcome.UID_PACKAGE_MISMATCH, null);
            pump();
            return;
        }

        AuthoritativeValidation validation = validateAuthoritative(request);
        if (validation.status == ValidationStatus.OBSOLETE) {
            inFlight = null;
            requests.remove(request.uid());
            requestReconcile();
            pump();
            return;
        }
        if (validation.status != ValidationStatus.VALID) {
            inFlight = null;
            retry(request, null);
            pump();
            return;
        }
        request = validation.request;
        requests.put(request.uid(), request);
        if (closed.get()) {
            inFlight = null;
            return;
        }
        startOemRecovery(request);
    }

    private void startOemRecovery(Request request) {
        CallToken token = new CallToken(request, CallPhase.OEM_RECOVERY);
        inFlight = token;
        ScheduleDispatch timeoutDispatch = schedule(
                () -> onTimeout(token), CALL_TIMEOUT_MS);
        token.timeoutCallback = timeoutDispatch.callback;
        if (timeoutDispatch.result != DispatchResult.ACCEPTED) {
            inFlight = null;
            if (timeoutDispatch.result == DispatchResult.REJECTED) {
                schedulingFailed(request, TerminalOutcome.SCHEDULER_REJECTED);
            }
            return;
        }
        DispatchResult callDispatch = executeCall(() -> {
            OemCallResult result = performOemCall(token);
            dispatchCallback(
                    () -> onRecoveryCompleted(token, result),
                    "recovery result callback rejected");
        });
        if (callDispatch != DispatchResult.ACCEPTED) {
            inFlight = null;
            cancelTimeout(token);
            if (callDispatch == DispatchResult.REJECTED) {
                schedulingFailed(request, TerminalOutcome.CALL_EXECUTOR_REJECTED);
            }
            pump();
        }
    }

    private OemCallResult performOemCall(CallToken token) {
        if (!callMayRun(token, CallPhase.OEM_RECOVERY)) {
            return OemCallResult.aborted();
        }
        ResidencyRuntimeState state = authoritativeState();
        if (state == null) return OemCallResult.retry();

        Revalidation revalidation;
        try {
            revalidation = planner.revalidate(
                    state, token.request.uid(), token.request.expectedPackages());
        } catch (Throwable ignored) {
            return OemCallResult.retry();
        }
        if (revalidation.status() != Revalidation.Status.STABLE) {
            return OemCallResult.retry();
        }
        DesiredState desired = revalidation.desiredStateOrNull();
        if (desired == null || !token.request.samePhysicalState(desired)) {
            return OemCallResult.obsolete();
        }

        Set<String> packages;
        try {
            packages = immutablePackages(
                    uidPackageSource.findPackagesForUid(token.request.uid()));
        } catch (Throwable ignored) {
            return OemCallResult.retry();
        }
        if (!token.request.expectedPackages().equals(packages)) {
            return OemCallResult.uidPackageMismatch();
        }
        if (!callMayRun(token, CallPhase.OEM_RECOVERY)) {
            return OemCallResult.aborted();
        }
        if (!token.claimOemPermit()) return OemCallResult.aborted();

        ResidencyRuntimeState finalState = authoritativeState();
        if (!callMayRun(token, CallPhase.OEM_RECOVERY)
                || !token.isOemPermitClaimed()) {
            return OemCallResult.aborted();
        }
        if (finalState != state
                || finalState == null
                || finalState.generation() != desired.runtimeGeneration()) {
            return OemCallResult.retry();
        }

        Outcome outcome;
        try {
            outcome = recoveryAction.recover(token.request.uid());
        } catch (Throwable ignored) {
            outcome = Outcome.ACCESS_FAILED;
        }
        return OemCallResult.completed(
                outcome == null ? Outcome.ACCESS_FAILED : outcome);
    }

    private void onRecoveryCompleted(CallToken token, OemCallResult result) {
        if (inFlight != token) return;
        cancelTimeout(token);
        inFlight = null;
        if (token.timedOut) {
            pump();
            return;
        }
        Request request = currentRequest(token.request);
        if (request == null || closed.get()) {
            pump();
            return;
        }
        switch (result.status) {
            case ABORTED:
                break;
            case RETRY:
                retry(request, null);
                break;
            case OBSOLETE:
                requests.remove(request.uid());
                requestReconcile();
                break;
            case UID_PACKAGE_MISMATCH:
                finish(request, TerminalOutcome.UID_PACKAGE_MISMATCH, null);
                break;
            case COMPLETED:
                Outcome outcome = result.outcome;
                if (outcome.retryable()) {
                    retry(request, outcome);
                } else {
                    finish(request, terminal(outcome), outcome);
                }
                break;
            default:
                throw new AssertionError("unknown OEM call result " + result.status);
        }
        pump();
    }

    private boolean callMayRun(CallToken token, CallPhase expectedPhase) {
        return token != null
                && token.phase == expectedPhase
                && !closed.get()
                && !token.timedOut
                && inFlight == token;
    }

    private void onTimeout(CallToken token) {
        if (inFlight != token || token.timedOut || closed.get()) return;
        token.timedOut = true;
        token.cancelOemPermit();
        Request request = currentRequest(token.request);
        if (request != null) {
            requests.remove(request.uid());
            putExhausted(request.exhausted(request.lastAdapterOutcome()));
            record(request, TerminalOutcome.CALL_TIMEOUT, request.lastAdapterOutcome());
            requestReconcile();
        } else {
            record(token.request, TerminalOutcome.CALL_TIMEOUT, null);
        }
        // Keep the token in-flight. A stuck platform call must not cause concurrent retries.
    }

    private Request currentRequest(Request tokenRequest) {
        Request current = requests.get(tokenRequest.uid());
        return current != null && current.sequence() == tokenRequest.sequence()
                ? current : null;
    }

    private AuthoritativeValidation validateAuthoritative(Request request) {
        ResidencyRuntimeState state = authoritativeState();
        if (state == null) return AuthoritativeValidation.retry();
        Revalidation revalidation = planner.revalidate(
                state, request.uid(), request.expectedPackages());
        if (revalidation.status() != Revalidation.Status.STABLE) {
            return AuthoritativeValidation.retry();
        }
        DesiredState desired = revalidation.desiredStateOrNull();
        if (desired == null || !request.samePhysicalState(desired)) {
            return AuthoritativeValidation.obsolete();
        }
        if (authoritativeState() != state) return AuthoritativeValidation.retry();
        return AuthoritativeValidation.valid(
                request.rebind(desired.runtimeGeneration()));
    }

    private void retry(Request request, Outcome adapterOutcome) {
        Request current = currentRequest(request);
        if (current == null) return;
        if (request.retryIndex() >= RETRY_DELAYS_MS.length) {
            requests.remove(request.uid());
            cancelRetry(request.uid());
            Request exhausted = request.exhausted(adapterOutcome);
            putExhausted(exhausted);
            record(exhausted, TerminalOutcome.RETRY_EXHAUSTED, adapterOutcome);
            requestReconcile();
            return;
        }
        long delay = RETRY_DELAYS_MS[request.retryIndex()];
        Request retry = request.waitingForRetry(adapterOutcome);
        requests.put(retry.uid(), retry);
        cancelRetry(retry.uid());
        RetryToken retryToken = new RetryToken(retry.uid(), retry.sequence());
        ScheduleDispatch dispatch = schedule(() -> markReady(retryToken), delay);
        retryToken.callback = dispatch.callback;
        if (dispatch.result == DispatchResult.ACCEPTED) {
            retryTokens.put(retry.uid(), retryToken);
        } else if (dispatch.result == DispatchResult.REJECTED) {
            schedulingFailed(retry, TerminalOutcome.SCHEDULER_REJECTED);
        }
    }

    private void markReady(RetryToken token) {
        if (retryTokens.get(token.uid) != token) return;
        retryTokens.remove(token.uid);
        Request request = requests.get(token.uid);
        if (request == null || request.sequence() != token.sequence || closed.get()) return;
        requests.put(token.uid, request.readyForAttempt());
        pump();
    }

    private void finish(Request request, TerminalOutcome terminal, Outcome adapterOutcome) {
        Request current = currentRequest(request);
        if (current == null) return;
        requests.remove(request.uid());
        cancelRetry(request.uid());
        exhaustedRequests.remove(request.uid());
        record(request, terminal, adapterOutcome);
        requestReconcile();
    }

    private void schedulingFailed(Request request, TerminalOutcome terminal) {
        Request current = currentRequest(request);
        if (current != null) requests.remove(request.uid());
        cancelRetry(request.uid());
        boolean retained = planner.requireRecovery(
                request.uid(),
                request.expectedPackages(),
                request.runtimeGeneration(),
                request.desiredSignature());
        record(
                request,
                retained ? terminal : TerminalOutcome.CAPACITY_DROPPED,
                request.lastAdapterOutcome());
    }

    private void putExhausted(Request request) {
        exhaustedRequests.remove(request.uid());
        if (exhaustedRequests.size() >= MAX_EXHAUSTED_UIDS) {
            Integer oldestUid = exhaustedRequests.keySet().iterator().next();
            Request dropped = exhaustedRequests.remove(oldestUid);
            record(dropped, TerminalOutcome.CAPACITY_DROPPED,
                    dropped.lastAdapterOutcome());
        }
        exhaustedRequests.put(request.uid(), request);
    }

    private void cancelTimeout(CallToken token) {
        if (token == null) return;
        Runnable callback = token.timeoutCallback;
        token.timeoutCallback = null;
        cancelScheduled(callback);
    }

    private void cancelRetry(int uid) {
        RetryToken token = retryTokens.remove(uid);
        if (token != null) cancelScheduled(token.callback);
    }

    private void cancelScheduled(Runnable callback) {
        if (callback == null) return;
        try {
            scheduler.cancel(callback);
        } catch (Throwable ignored) {
            // Token validation still makes a failed cancellation harmless.
        }
    }

    private void record(
            Request request, TerminalOutcome terminal, Outcome adapterOutcome) {
        try {
            diagnosticsRecorder.record(request, terminal, adapterOutcome);
        } catch (Throwable ignored) {
            // Diagnostics must never interrupt system recovery.
        }
    }

    private ResidencyRuntimeState authoritativeState() {
        try {
            return runtimeStateSource.current();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private DispatchResult execute(Runnable task) {
        if (closed.get()) return DispatchResult.CLOSED;
        try {
            boolean accepted = scheduler.execute(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    failWorker(error);
                }
            });
            if (accepted) return DispatchResult.ACCEPTED;
            return closed.get() ? DispatchResult.CLOSED : DispatchResult.REJECTED;
        } catch (Throwable error) {
            failAsynchronously(error);
            return DispatchResult.CLOSED;
        }
    }

    private ScheduleDispatch schedule(Runnable task, long delayMs) {
        if (closed.get()) return ScheduleDispatch.closed();
        Runnable callback = () -> {
            try {
                task.run();
            } catch (Throwable error) {
                failWorker(error);
            }
        };
        try {
            boolean accepted = scheduler.schedule(callback, delayMs);
            if (accepted) return ScheduleDispatch.accepted(callback);
            return closed.get()
                    ? ScheduleDispatch.closed()
                    : ScheduleDispatch.rejected();
        } catch (Throwable error) {
            failAsynchronously(error);
            return ScheduleDispatch.closed();
        }
    }

    private DispatchResult executeCall(Runnable task) {
        if (closed.get()) return DispatchResult.CLOSED;
        try {
            boolean accepted = callExecutor.execute(task);
            if (accepted) return DispatchResult.ACCEPTED;
            return closed.get() ? DispatchResult.CLOSED : DispatchResult.REJECTED;
        } catch (Throwable error) {
            failAsynchronously(error);
            return DispatchResult.CLOSED;
        }
    }

    private void dispatchCallback(Runnable task, String rejectionMessage) {
        DispatchResult dispatch = execute(task);
        if (dispatch == DispatchResult.REJECTED) {
            failAsynchronously(new IllegalStateException(rejectionMessage));
        }
    }

    private void failWorker(Throwable error) {
        try {
            diagnosticsRecorder.workerFailed(error);
        } catch (Throwable ignored) {
            // Failure reporting remains best effort.
        }
        close();
        notifyFatalFailure(error);
    }

    private void failAsynchronously(Throwable error) {
        try {
            diagnosticsRecorder.workerFailed(error);
        } catch (Throwable ignored) {
            // Failure reporting remains best effort.
        }
        close();
        notifyFatalFailure(error);
    }

    private void notifyFatalFailure(Throwable error) {
        if (!fatalNotified.compareAndSet(false, true)) return;
        try {
            fatalFailureListener.onFatalFailure(this, error);
        } catch (Throwable ignored) {
            // Hook lifecycle cleanup remains best effort after shutdown.
        }
    }

    private long nextSequence() {
        if (nextSequence == Long.MAX_VALUE) nextSequence = 0L;
        return ++nextSequence;
    }

    private static TerminalOutcome terminal(Outcome outcome) {
        switch (outcome) {
            case RECOVERED: return TerminalOutcome.RECOVERED;
            case NOT_FROZEN: return TerminalOutcome.NOT_FROZEN;
            case TARGET_UNAVAILABLE: return TerminalOutcome.TARGET_UNAVAILABLE;
            case INVALID_TARGET: return TerminalOutcome.INVALID_TARGET;
            case NOT_READY:
            case UNFREEZE_FAILED:
            case ACCESS_FAILED:
            default:
                return TerminalOutcome.RETRY_EXHAUSTED;
        }
    }

    private static Set<String> immutablePackages(Set<String> source) {
        if (source == null || source.isEmpty()) return Collections.emptySet();
        TreeSet<String> result = new TreeSet<>();
        for (String packageName : source) {
            if (packageName == null || packageName.isEmpty()) {
                throw new IllegalArgumentException("invalid package name");
            }
            result.add(packageName);
        }
        return Collections.unmodifiableSet(result);
    }

    int pendingRequestCount() { return requests.size(); }
    int exhaustedRequestCount() { return exhaustedRequests.size(); }
    Request pendingRequestOrNull(int uid) {
        Request request = requests.get(uid);
        return request != null ? request : exhaustedRequests.get(uid);
    }

    public enum TerminalOutcome {
        RECOVERED,
        NOT_FROZEN,
        TARGET_UNAVAILABLE,
        INVALID_TARGET,
        UID_PACKAGE_MISMATCH,
        RETRY_EXHAUSTED,
        SCHEDULER_REJECTED,
        CALL_EXECUTOR_REJECTED,
        CALL_TIMEOUT,
        CAPACITY_DROPPED
    }

    public interface RecoveryScheduler {
        boolean execute(Runnable task);
        boolean schedule(Runnable task, long delayMs);
        void cancel(Runnable task);
        void shutdown();
    }

    public interface RecoveryCallExecutor {
        boolean execute(Runnable task);
        void shutdown();
    }

    public interface RuntimeStateSource {
        ResidencyRuntimeState current();
    }

    public interface UidPackageSource {
        Set<String> findPackagesForUid(int uid);
    }

    public interface RecoveryAction {
        Outcome recover(int uid);
    }

    public interface DiagnosticsRecorder {
        void record(Request request, TerminalOutcome outcome, Outcome adapterOutcome);

        default void workerFailed(Throwable error) { }
    }

    public interface FatalFailureListener {
        FatalFailureListener NO_OP = (coordinator, error) -> { };

        void onFatalFailure(HansActiveRecoveryCoordinator coordinator, Throwable error);
    }

    public static final class Request {
        private final int uid;
        private final Set<String> expectedPackages;
        private final long runtimeGeneration;
        private final long desiredSignature;
        private final long sequence;
        private final int retryIndex;
        private final boolean ready;
        private final boolean exhausted;
        private final Outcome lastAdapterOutcome;

        private Request(
                int uid,
                Set<String> expectedPackages,
                long runtimeGeneration,
                long desiredSignature,
                long sequence,
                int retryIndex,
                boolean ready,
                boolean exhausted,
                Outcome lastAdapterOutcome) {
            this.uid = uid;
            this.expectedPackages = immutablePackages(expectedPackages);
            this.runtimeGeneration = runtimeGeneration;
            this.desiredSignature = desiredSignature;
            this.sequence = sequence;
            this.retryIndex = retryIndex;
            this.ready = ready;
            this.exhausted = exhausted;
            this.lastAdapterOutcome = lastAdapterOutcome;
        }

        private Request rebind(long generation) {
            if (generation == runtimeGeneration) return this;
            return new Request(uid, expectedPackages, generation, desiredSignature,
                    sequence, retryIndex, ready, exhausted, lastAdapterOutcome);
        }

        private Request restart(long newSequence, long generation) {
            return new Request(uid, expectedPackages, generation, desiredSignature,
                    newSequence, 0, true, false, null);
        }

        private Request waitingForRetry(Outcome outcome) {
            return new Request(uid, expectedPackages, runtimeGeneration, desiredSignature,
                    sequence, retryIndex + 1, false, false, outcome);
        }

        private Request readyForAttempt() {
            if (ready) return this;
            return new Request(uid, expectedPackages, runtimeGeneration, desiredSignature,
                    sequence, retryIndex, true, false, lastAdapterOutcome);
        }

        private Request exhausted(Outcome outcome) {
            return new Request(uid, expectedPackages, runtimeGeneration, desiredSignature,
                    sequence, retryIndex, false, true, outcome);
        }

        private boolean samePhysicalState(DesiredState desired) {
            return desired != null
                    && desiredSignature == desired.signature()
                    && expectedPackages.equals(desired.expectedPackages());
        }

        public int uid() { return uid; }
        public Set<String> expectedPackages() { return expectedPackages; }
        public long runtimeGeneration() { return runtimeGeneration; }
        public long desiredSignature() { return desiredSignature; }
        public long sequence() { return sequence; }
        public int retryIndex() { return retryIndex; }
        public boolean ready() { return ready; }
        public boolean exhausted() { return exhausted; }
        public Outcome lastAdapterOutcome() { return lastAdapterOutcome; }
    }

    private enum ValidationStatus { VALID, RETRY, OBSOLETE }
    private enum DispatchResult { ACCEPTED, CLOSED, REJECTED }
    private enum CallPhase { PACKAGE_QUERY, OEM_RECOVERY }
    private enum OemPermit { ARMED, CLAIMED, CANCELLED }
    private enum OemCallStatus {
        ABORTED,
        RETRY,
        OBSOLETE,
        UID_PACKAGE_MISMATCH,
        COMPLETED
    }

    private static final class ScheduleDispatch {
        private final DispatchResult result;
        private final Runnable callback;

        private ScheduleDispatch(DispatchResult result, Runnable callback) {
            this.result = Objects.requireNonNull(result, "result");
            this.callback = callback;
        }

        private static ScheduleDispatch accepted(Runnable callback) {
            return new ScheduleDispatch(
                    DispatchResult.ACCEPTED,
                    Objects.requireNonNull(callback, "callback"));
        }

        private static ScheduleDispatch closed() {
            return new ScheduleDispatch(DispatchResult.CLOSED, null);
        }

        private static ScheduleDispatch rejected() {
            return new ScheduleDispatch(DispatchResult.REJECTED, null);
        }
    }

    private static final class AuthoritativeValidation {
        private final ValidationStatus status;
        private final Request request;

        private AuthoritativeValidation(ValidationStatus status, Request request) {
            this.status = status;
            this.request = request;
        }

        private static AuthoritativeValidation valid(Request request) {
            return new AuthoritativeValidation(ValidationStatus.VALID, request);
        }

        private static AuthoritativeValidation retry() {
            return new AuthoritativeValidation(ValidationStatus.RETRY, null);
        }

        private static AuthoritativeValidation obsolete() {
            return new AuthoritativeValidation(ValidationStatus.OBSOLETE, null);
        }
    }

    private static final class CallToken {
        private final Request request;
        private final CallPhase phase;
        private final AtomicReference<OemPermit> oemPermit;
        private volatile boolean timedOut;
        private Runnable timeoutCallback;

        private CallToken(Request request, CallPhase phase) {
            this.request = request;
            this.phase = phase;
            this.oemPermit = new AtomicReference<>(phase == CallPhase.OEM_RECOVERY
                    ? OemPermit.ARMED : OemPermit.CANCELLED);
        }

        private boolean claimOemPermit() {
            return phase == CallPhase.OEM_RECOVERY
                    && oemPermit.compareAndSet(OemPermit.ARMED, OemPermit.CLAIMED);
        }

        private void cancelOemPermit() {
            if (phase == CallPhase.OEM_RECOVERY) {
                oemPermit.compareAndSet(OemPermit.ARMED, OemPermit.CANCELLED);
            }
        }

        private boolean isOemPermitClaimed() {
            return oemPermit.get() == OemPermit.CLAIMED;
        }
    }

    private static final class RetryToken {
        private final int uid;
        private final long sequence;
        private Runnable callback;

        private RetryToken(int uid, long sequence) {
            this.uid = uid;
            this.sequence = sequence;
        }
    }

    private static final class OemCallResult {
        private final OemCallStatus status;
        private final Outcome outcome;

        private OemCallResult(OemCallStatus status, Outcome outcome) {
            this.status = Objects.requireNonNull(status, "status");
            this.outcome = outcome;
        }

        private static OemCallResult aborted() {
            return new OemCallResult(OemCallStatus.ABORTED, null);
        }

        private static OemCallResult retry() {
            return new OemCallResult(OemCallStatus.RETRY, null);
        }

        private static OemCallResult obsolete() {
            return new OemCallResult(OemCallStatus.OBSOLETE, null);
        }

        private static OemCallResult uidPackageMismatch() {
            return new OemCallResult(OemCallStatus.UID_PACKAGE_MISMATCH, null);
        }

        private static OemCallResult completed(Outcome outcome) {
            return new OemCallResult(
                    OemCallStatus.COMPLETED,
                    Objects.requireNonNull(outcome, "outcome"));
        }
    }
}
