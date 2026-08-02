package com.frostwork.bgenhance.hooks;

import android.os.SystemClock;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookDiagnostics;
import com.frostwork.bgenhance.HookEvent;
import com.frostwork.bgenhance.HookResidencyRuntime;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import com.frostwork.bgenhance.ResidencyDecisionReporter;
import com.frostwork.bgenhance.hooks.alarm.AlarmDeferredRegistry;
import com.frostwork.bgenhance.hooks.alarm.AlarmObjectAdapter;
import com.frostwork.bgenhance.hooks.alarm.AlarmRuntime;
import com.frostwork.bgenhance.hooks.hans.WakeThrottleState;
import com.frostwork.bgenhance.residency.decision.DecisionDisposition;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Coordinates HANS Alarm thawing with the final vendor delivery gate. */
public final class AlarmWakeHook implements FeatureHook {
    private static final String EXT_CLASS =
            "com.android.server.alarm.AlarmManagerServiceExtImpl";
    private static final String HANS_MANAGER_CLASS =
            "com.android.server.am.OplusHansManager";
    private static final int DEFERRED_CAPACITY = 2_048;
    private static final int DRIVER_UNKNOWN = 0;
    private static final int DRIVER_NATIVE = 1;
    private static final int DRIVER_FALLBACK = 2;

    private final WakeDecisionResolver decisionResolver;
    private final WakeThrottleState throttleState;
    private final ElapsedRealtimeClock clock;
    private final AlarmDeferredRegistry<DeferredAlarm> deferred;
    private final WakeDiagnosticsRecorder diagnosticsRecorder;
    private final HansReplay replay;
    private final AlarmFinisher finisher;
    private final AlarmDriverProbe driverProbe;
    private final ThreadLocal<AlarmStageFrame> activeAlarm = new ThreadLocal<>();
    private final ThreadLocal<Integer> replayDepth = new ThreadLocal<>();

    private volatile Method hansCheckMethod;
    private volatile AlarmRuntime.CompletionAccess completionAccess;
    private volatile AlarmRuntime.DriverAccess driverAccess;
    private volatile int driverState;
    private volatile boolean active;

    public AlarmWakeHook() {
        this(
                AlarmWakeHook::decide,
                new WakeThrottleState(),
                SystemClock::elapsedRealtime,
                new AlarmDeferredRegistry<>(DEFERRED_CAPACITY),
                new DefaultWakeDiagnosticsRecorder(),
                null,
                null,
                null,
                false);
    }

    AlarmWakeHook(
            WakeDecisionResolver decisionResolver,
            WakeThrottleState throttleState,
            ElapsedRealtimeClock clock,
            AlarmDeferredRegistry<DeferredAlarm> deferred,
            WakeDiagnosticsRecorder diagnosticsRecorder,
            HansReplay replay,
            AlarmFinisher finisher,
            boolean active) {
        this(
                decisionResolver,
                throttleState,
                clock,
                deferred,
                diagnosticsRecorder,
                replay,
                finisher,
                extension -> true,
                active);
    }

    AlarmWakeHook(
            WakeDecisionResolver decisionResolver,
            WakeThrottleState throttleState,
            ElapsedRealtimeClock clock,
            AlarmDeferredRegistry<DeferredAlarm> deferred,
            WakeDiagnosticsRecorder diagnosticsRecorder,
            HansReplay replay,
            AlarmFinisher finisher,
            AlarmDriverProbe driverProbe,
            boolean active) {
        this.decisionResolver = Objects.requireNonNull(
                decisionResolver, "decisionResolver");
        this.throttleState = Objects.requireNonNull(throttleState, "throttleState");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deferred = Objects.requireNonNull(deferred, "deferred");
        this.diagnosticsRecorder = Objects.requireNonNull(
                diagnosticsRecorder, "diagnosticsRecorder");
        this.replay = replay == null ? this::invokeHansCheck : replay;
        this.finisher = finisher == null ? this::invokeNoteAlarmFinish : finisher;
        this.driverProbe = driverProbe == null
                ? this::invokeDriverProbe : driverProbe;
        this.active = active;
    }

    @Override public String hookTargetId() {
        return HookTargetIds.ALARM_WAKE_DELIVERY;
    }

    @Override public String prefKey() { return ""; }

    @Override
    public String install(ClassLoader loader) {
        active = false;
        hansCheckMethod = null;
        completionAccess = null;
        driverAccess = null;
        driverState = DRIVER_UNKNOWN;

        AlarmRuntime.CapabilityMatrix matrix = AlarmRuntime.inspect(loader);
        if (!matrix.allAvailable()) return unavailableStatus(matrix);

        Method filter = matrix.methodOrNull(AlarmRuntime.Capability.FILTER_HANS);
        Method check = matrix.methodOrNull(AlarmRuntime.Capability.HANS_CHECK);
        Method delivery = matrix.methodOrNull(AlarmRuntime.Capability.DELIVERY_GATE);
        AlarmRuntime.CompletionAccess completion = matrix.completionAccessOrNull();
        AlarmRuntime.DriverAccess driver = matrix.driverAccessOrNull();
        if (filter == null
                || check == null
                || delivery == null
                || completion == null
                || driver == null
                || !AlarmRuntime.matches(AlarmRuntime.Capability.FILTER_HANS, filter)
                || !AlarmRuntime.matches(AlarmRuntime.Capability.HANS_CHECK, check)
                || !AlarmRuntime.matches(AlarmRuntime.Capability.DELIVERY_GATE, delivery)) {
            return "FAILED_FAIL_OPEN:CAPABILITY_METHOD_UNAVAILABLE";
        }
        hansCheckMethod = check;
        completionAccess = completion;
        driverAccess = driver;

        int checkCount = HookUtils.hookMatching(
                HANS_MANAGER_CLASS, loader, check::equals, this::interceptHansCheck);
        if (checkCount != 1) return hookCountFailure("check", checkCount);

        int filterCount = HookUtils.hookMatching(
                EXT_CLASS, loader, filter::equals, this::interceptFilter);
        if (filterCount != 1) {
            return partialHookFailure("check", "filter", filterCount);
        }

        int deliveryCount = HookUtils.hookMatching(
                EXT_CLASS, loader, delivery::equals, this::interceptDelivery);
        if (deliveryCount != 1) {
            return partialHookFailure("check+filter", "delivery", deliveryCount);
        }

        active = true;
        return "INSTALLED:check=1,filter=1,delivery=1,fallback=FAIL_OPEN";
    }

    Object interceptFilter(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        if (!nativeDriverAvailable(chain.getThisObject())) {
            recordRuntimeFailOpen(unknownAlarmEvent());
            return chain.proceed();
        }
        Object alarm = HookUtils.objectArg(chain, 0);
        AlarmObjectAdapter.Resolution resolution;
        try {
            resolution = AlarmObjectAdapter.resolve(alarm);
        } catch (Throwable error) {
            recordFailOpen(unknownAlarmEvent());
            return chain.proceed();
        }
        if (!resolution.isResolved()) {
            recordFailOpen(unknownAlarmEvent());
            return chain.proceed();
        }

        AlarmStageFrame previous = activeAlarm.get();
        activeAlarm.set(new AlarmStageFrame(alarm, resolution.targetOrNull()));
        try {
            return chain.proceed();
        } finally {
            restoreAlarmFrame(previous);
        }
    }

    Object interceptHansCheck(XposedInterface.Chain chain) throws Throwable {
        if (!active || replayingHans()) return chain.proceed();
        AlarmStageFrame frame = activeAlarm.get();
        if (frame == null) return chain.proceed();

        Object receiver = chain.getThisObject();
        int uid = HookUtils.intArg(chain, 0, ResidencyTarget.UNKNOWN_UID);
        Object packageValue = HookUtils.objectArg(chain, 1);
        Object actionValue = HookUtils.objectArg(chain, 2);
        String packageName = packageValue instanceof String
                ? (String) packageValue : null;
        boolean validAction = actionValue == null || actionValue instanceof String;
        String action = validAction ? (String) actionValue : null;
        if (receiver == null
                || !validAction
                || uid != frame.target.ownerUid()
                || !Objects.equals(packageName, frame.target.ownerPackage())) {
            recordFailOpen(alarmEvent(frame.target));
            return chain.proceed();
        }

        WakeDecision decision;
        try {
            decision = decisionResolver.decide(
                    target(frame.target), WakeSource.ALARM);
        } catch (Throwable error) {
            recordFailOpen(alarmEvent(frame.target));
            return chain.proceed();
        }
        if (decision == null) {
            recordFailOpen(alarmEvent(frame.target));
            return chain.proceed();
        }

        if (!requiresCoordination(decision)) {
            recordDecision(
                    alarmEvent(frame.target),
                    decision,
                    ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED);
            return chain.proceed();
        }

        DeferredAlarm record = new DeferredAlarm(
                receiver, frame.target, uid, packageName, action);
        AlarmDeferredRegistry.PutResult result;
        try {
            result = deferred.putIfAbsent(frame.alarm, record);
        } catch (Throwable error) {
            recordRuntimeFailOpen(alarmEvent(frame.target));
            return chain.proceed();
        }
        if (result == AlarmDeferredRegistry.PutResult.ADDED
                || result == AlarmDeferredRegistry.PutResult.PRESENT) {
            return Boolean.FALSE;
        }

        recordRuntimeFailOpen(alarmEvent(frame.target));
        return chain.proceed();
    }

    Object interceptDelivery(XposedInterface.Chain chain) throws Throwable {
        if (!active) return chain.proceed();
        Object alarm = HookUtils.objectArg(chain, 0);
        Object vendorValue;
        try {
            vendorValue = chain.proceed();
        } catch (Throwable originalFailure) {
            DeferredAlarm record = removeDeferred(alarm);
            if (record != null && !replayQuietly(record)) {
                recordRuntimeFailOpen(alarmEvent(record));
            }
            throw originalFailure;
        }
        if (!(vendorValue instanceof Boolean)) return vendorValue;

        DeferredAlarm record = removeDeferred(alarm);
        if (((Boolean) vendorValue).booleanValue()) return vendorValue;
        if (record == null) return vendorValue;

        HookEvent event = alarmEvent(record);
        AlarmObjectAdapter.Resolution resolution;
        try {
            resolution = AlarmObjectAdapter.resolve(alarm);
        } catch (Throwable error) {
            restoreAndFailOpen(record, event);
            return vendorValue;
        }
        if (!resolution.isResolved()
                || !record.matchesOwner(resolution.targetOrNull())) {
            restoreAndFailOpen(record, event);
            return vendorValue;
        }

        AlarmObjectAdapter.Target current = resolution.targetOrNull();
        WakeDecision decision;
        try {
            decision = decisionResolver.decide(target(current), WakeSource.ALARM);
        } catch (Throwable error) {
            restoreAndFailOpen(record, event);
            return vendorValue;
        }
        if (decision == null) {
            restoreAndFailOpen(record, event);
            return vendorValue;
        }

        if (decision.trace().disposition() != DecisionDisposition.ENFORCED) {
            allowAfterReplay(
                    record,
                    event,
                    decision,
                    ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED);
            return vendorValue;
        }

        switch (decision.effectiveAction()) {
            case BLOCK_WAKE:
                return finishOrFailOpen(
                        chain.getThisObject(),
                        current,
                        record,
                        event,
                        decision,
                        ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED,
                        vendorValue);
            case THROTTLE_WAKE:
                return applyThrottle(
                        chain.getThisObject(),
                        current,
                        record,
                        event,
                        decision,
                        vendorValue);
            case ALLOW_SYSTEM:
            case ALLOW_WAKE:
            default:
                allowAfterReplay(
                        record,
                        event,
                        decision,
                        ResidencyDecisionReporter.WakeRuntimeOutcome.DECISION_APPLIED);
                return vendorValue;
        }
    }

    private Object applyThrottle(
            Object extension,
            AlarmObjectAdapter.Target target,
            DeferredAlarm record,
            HookEvent event,
            WakeDecision decision,
            Object vendorValue) {
        WakeThrottleState.Outcome outcome;
        try {
            outcome = throttleState.evaluate(
                    target.ownerUid(),
                    WakeSource.ALARM,
                    decision.trace().configRevision(),
                    decision.trace().policyRevision(),
                    decision.trace().identityGeneration(),
                    decision.cooldownMs(),
                    clock.elapsedRealtime());
        } catch (Throwable error) {
            outcome = WakeThrottleState.Outcome.FAIL_OPEN;
        }

        if (outcome == WakeThrottleState.Outcome.THROTTLED) {
            return finishOrFailOpen(
                    extension,
                    target,
                    record,
                    event,
                    decision,
                    ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_BLOCKED,
                    vendorValue);
        }
        if (outcome == WakeThrottleState.Outcome.ALLOWED) {
            allowAfterReplay(
                    record,
                    event,
                    decision,
                    ResidencyDecisionReporter.WakeRuntimeOutcome.THROTTLE_ALLOWED);
            return vendorValue;
        }

        allowAfterReplay(
                record,
                event,
                decision,
                ResidencyDecisionReporter.WakeRuntimeOutcome.FAIL_OPEN);
        return vendorValue;
    }

    private Object finishOrFailOpen(
            Object extension,
            AlarmObjectAdapter.Target target,
            DeferredAlarm record,
            HookEvent event,
            WakeDecision decision,
            ResidencyDecisionReporter.WakeRuntimeOutcome outcome,
            Object vendorValue) {
        try {
            finisher.finish(extension, target);
            recordDecision(event, decision, outcome);
            return Boolean.TRUE;
        } catch (Throwable error) {
            replayQuietly(record);
            recordRuntimeFailOpen(event);
            return vendorValue;
        }
    }

    private void allowAfterReplay(
            DeferredAlarm record,
            HookEvent event,
            WakeDecision decision,
            ResidencyDecisionReporter.WakeRuntimeOutcome outcome) {
        try {
            replay.replay(record);
            recordDecision(event, decision, outcome);
        } catch (Throwable error) {
            recordRuntimeFailOpen(event);
        }
    }

    private void restoreAndFailOpen(DeferredAlarm record, HookEvent event) {
        if (replayQuietly(record)) {
            recordFailOpen(event);
        } else {
            recordRuntimeFailOpen(event);
        }
    }

    private boolean replayQuietly(DeferredAlarm record) {
        try {
            replay.replay(record);
            return true;
        } catch (Throwable ignored) {
            // Delivery must continue even when the deferred vendor thaw cannot be restored.
            return false;
        }
    }

    private DeferredAlarm removeDeferred(Object alarm) {
        if (alarm == null) return null;
        try {
            return deferred.remove(alarm);
        } catch (Throwable error) {
            recordRuntimeFailOpen(unknownAlarmEvent());
            return null;
        }
    }

    private void invokeHansCheck(DeferredAlarm record) throws Throwable {
        Method method = hansCheckMethod;
        if (method == null) throw new IllegalStateException("HANS check unavailable");
        Integer previous = replayDepth.get();
        replayDepth.set(previous == null ? 1 : previous + 1);
        try {
            method.invoke(
                    record.hansManager,
                    record.checkUid,
                    record.checkPackage,
                    record.checkAction);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        } finally {
            if (previous == null) {
                replayDepth.remove();
            } else {
                replayDepth.set(previous);
            }
        }
    }

    private void invokeNoteAlarmFinish(
            Object extension, AlarmObjectAdapter.Target target) throws Throwable {
        AlarmRuntime.CompletionAccess access = completionAccess;
        if (access == null) throw new IllegalStateException("completion unavailable");
        access.noteAlarmFinish(
                extension,
                target.operation(),
                target.workSource(),
                target.schedulerUid(),
                target.statsTag());
    }

    private boolean invokeDriverProbe(Object extension) throws Throwable {
        AlarmRuntime.DriverAccess access = driverAccess;
        if (access == null) throw new IllegalStateException("driver access unavailable");
        return access.isNativeDriverPresent(extension);
    }

    private boolean nativeDriverAvailable(Object extension) {
        int current = driverState;
        if (current == DRIVER_NATIVE) return true;
        if (current == DRIVER_FALLBACK) return false;
        try {
            boolean present = driverProbe.isNativeDriverPresent(extension);
            driverState = present ? DRIVER_NATIVE : DRIVER_FALLBACK;
            return present;
        } catch (Throwable error) {
            driverState = DRIVER_FALLBACK;
            return false;
        }
    }

    private boolean replayingHans() {
        Integer depth = replayDepth.get();
        return depth != null && depth > 0;
    }

    private void restoreAlarmFrame(AlarmStageFrame previous) {
        if (previous == null) {
            activeAlarm.remove();
        } else {
            activeAlarm.set(previous);
        }
    }

    private static boolean requiresCoordination(WakeDecision decision) {
        if (decision.trace().disposition() != DecisionDisposition.ENFORCED) {
            return false;
        }
        return decision.effectiveAction() == WakeDecision.Action.BLOCK_WAKE
                || decision.effectiveAction() == WakeDecision.Action.THROTTLE_WAKE;
    }

    private static ResidencyTarget target(AlarmObjectAdapter.Target target) {
        return new ResidencyTarget(
                target.ownerPackage(),
                target.ownerUid(),
                ResidencyTarget.UNKNOWN_PID);
    }

    private static WakeDecision decide(
            ResidencyTarget target, WakeSource source) {
        return HookResidencyRuntime.decideWake(new WakeContext(
                target,
                source,
                FeatureIds.HANS_WAKE,
                HookTargetIds.ALARM_WAKE_DELIVERY));
    }

    private static HookEvent alarmEvent(AlarmObjectAdapter.Target target) {
        return new HookEvent(
                FeatureIds.HANS_WAKE,
                WakeSource.ALARM.ordinal(),
                target == null ? ResidencyTarget.UNKNOWN_UID : target.ownerUid(),
                target == null ? "" : target.ownerPackage(),
                "",
                "alarm_delivery");
    }

    private static HookEvent unknownAlarmEvent() {
        return alarmEvent((AlarmObjectAdapter.Target) null);
    }

    private static HookEvent alarmEvent(DeferredAlarm record) {
        return new HookEvent(
                FeatureIds.HANS_WAKE,
                WakeSource.ALARM.ordinal(),
                record.ownerUid,
                record.ownerPackage,
                "",
                "alarm_delivery");
    }

    private void recordDecision(
            HookEvent event,
            WakeDecision decision,
            ResidencyDecisionReporter.WakeRuntimeOutcome outcome) {
        try {
            diagnosticsRecorder.recordDecision(event, decision, outcome);
        } catch (Throwable ignored) {
            // Diagnostics must never alter Alarm delivery.
        }
    }

    private void recordFailOpen(HookEvent event) {
        try {
            diagnosticsRecorder.recordFailOpen(event);
        } catch (Throwable ignored) {
            // Failure diagnostics are also fail-open.
        }
    }

    private void recordRuntimeFailOpen(HookEvent event) {
        try {
            diagnosticsRecorder.recordRuntimeFailOpen(event);
        } catch (Throwable ignored) {
            // Runtime diagnostics are also fail-open.
        }
    }

    static String unavailableStatus(AlarmRuntime.CapabilityMatrix matrix) {
        String summary = matrix == null ? "matrix=UNAVAILABLE" : matrix.summary();
        String prefix = summary.contains("LOOKUP_FAILED")
                ? "FAILED_FAIL_OPEN:" : "UNSUPPORTED_";
        return prefix + summary;
    }

    private static String hookCountFailure(String target, int count) {
        return "FAILED_FAIL_OPEN:" + hookCountDetail(target, count);
    }

    private static String partialHookFailure(
            String installed, String target, int count) {
        return "FAILED_FAIL_OPEN:PARTIAL_" + installed + ':'
                + hookCountDetail(target, count);
    }

    private static String hookCountDetail(String target, int count) {
        return count < 0
                ? target + "_CLASS_LOOKUP"
                : target + "_HOOK_COUNT=" + count;
    }

    interface WakeDecisionResolver {
        WakeDecision decide(ResidencyTarget target, WakeSource source);
    }

    interface ElapsedRealtimeClock {
        long elapsedRealtime();
    }

    interface HansReplay {
        void replay(DeferredAlarm record) throws Throwable;
    }

    interface AlarmFinisher {
        void finish(
                Object extension, AlarmObjectAdapter.Target target) throws Throwable;
    }

    interface AlarmDriverProbe {
        boolean isNativeDriverPresent(Object extension) throws Throwable;
    }

    interface WakeDiagnosticsRecorder {
        void recordDecision(
                HookEvent event,
                WakeDecision decision,
                ResidencyDecisionReporter.WakeRuntimeOutcome outcome);

        void recordFailOpen(HookEvent event);

        void recordRuntimeFailOpen(HookEvent event);
    }

    private static final class DefaultWakeDiagnosticsRecorder
            implements WakeDiagnosticsRecorder {
        @Override
        public void recordDecision(
                HookEvent event,
                WakeDecision decision,
                ResidencyDecisionReporter.WakeRuntimeOutcome outcome) {
            ResidencyDecisionReporter.recordWakeFromLockedPath(
                    event, decision, outcome);
        }

        @Override
        public void recordFailOpen(HookEvent event) {
            HookDiagnostics.recordEventFromLockedPath(
                    event, DiagnosticsResult.FAIL_OPEN);
        }

        @Override
        public void recordRuntimeFailOpen(HookEvent event) {
            HookDiagnostics.recordEventFromLockedPath(
                    event, DiagnosticsResult.RUNTIME_FAIL_OPEN);
        }
    }

    static final class DeferredAlarm {
        private final Object hansManager;
        private final int ownerUid;
        private final String ownerPackage;
        private final int checkUid;
        private final String checkPackage;
        private final String checkAction;

        private DeferredAlarm(
                Object hansManager,
                AlarmObjectAdapter.Target target,
                int checkUid,
                String checkPackage,
                String checkAction) {
            this.hansManager = Objects.requireNonNull(hansManager, "hansManager");
            this.ownerUid = target.ownerUid();
            this.ownerPackage = target.ownerPackage();
            this.checkUid = checkUid;
            this.checkPackage = checkPackage;
            this.checkAction = checkAction;
        }

        private boolean matchesOwner(AlarmObjectAdapter.Target target) {
            return target != null
                    && ownerUid == target.ownerUid()
                    && ownerPackage.equals(target.ownerPackage());
        }

        int ownerUid() { return ownerUid; }
        String ownerPackage() { return ownerPackage; }
        int checkUid() { return checkUid; }
        String checkPackage() { return checkPackage; }
        String checkAction() { return checkAction; }
        Object hansManager() { return hansManager; }
    }

    private static final class AlarmStageFrame {
        private final Object alarm;
        private final AlarmObjectAdapter.Target target;

        private AlarmStageFrame(
                Object alarm, AlarmObjectAdapter.Target target) {
            this.alarm = Objects.requireNonNull(alarm, "alarm");
            this.target = Objects.requireNonNull(target, "target");
        }
    }
}
