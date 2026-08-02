package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.Objects;

/** Maps verified HANS kernel types and terminal reasons to stable wake sources. */
public final class HansWakeSourceClassifier {
    public static final String MODULE_RECOVERY_REASON = "force";
    public static final String MODULE_RECOVERY_COMPONENT = "BgEnhancePolicy";

    private HansWakeSourceClassifier() {}

    public static Resolution classifyKernel(int type) {
        switch (type) {
            case 0: return Resolution.resolved(WakeSource.ASYNC_BINDER);
            case 1: return Resolution.resolved(WakeSource.SYNC_BINDER);
            case 2: return Resolution.resolved(WakeSource.TRANSACTION_BINDER);
            case 3: return Resolution.resolved(WakeSource.SIGNAL);
            case 4: return Resolution.resolved(WakeSource.PACKET);
            default: return Resolution.failed(Failure.UNKNOWN_KERNEL_TYPE);
        }
    }

    public static Resolution classifyTerminal(String reason, String componentInfo) {
        if (isModuleRecovery(reason, componentInfo)) {
            return Resolution.resolved(WakeSource.INTERNAL_OPERATION);
        }
        if (reason == null) return Resolution.failed(Failure.NULL_REASON);
        if (reason.isEmpty()) return Resolution.failed(Failure.EMPTY_REASON);
        switch (reason) {
            case "Packet":
                return Resolution.resolved(WakeSource.PACKET);
            case "Alarm":
            case "Proxy2AlignAlarm":
                return Resolution.resolved(WakeSource.ALARM);
            case "AsyncBinder":
            case "AsyncBinderMax":
            case "FullAsyncBinder":
            case "W_AsyncBinder":
                return Resolution.resolved(WakeSource.ASYNC_BINDER);
            case "SyncBinder":
                return Resolution.resolved(WakeSource.SYNC_BINDER);
            case "TransBinder":
                return Resolution.resolved(WakeSource.TRANSACTION_BINDER);
            case "Signal":
                return Resolution.resolved(WakeSource.SIGNAL);
            case "Activity":
            case "TopActivity":
            case "RelaunchActivity":
            case "inputDispatcher":
            case "inputCancel":
            case "AppCardVisible":
                return Resolution.resolved(WakeSource.ACTIVITY_INPUT);
            case "StartService":
            case "BindService":
            case "restartService":
            case "BumpService":
            case "serviceTimeout":
            case "ExecutingComponent":
            case "fg-dependency":
                return Resolution.resolved(WakeSource.SERVICE);
            case "Broadcast":
            case "bcMax":
                return Resolution.resolved(WakeSource.BROADCAST);
            case "Provider":
                return Resolution.resolved(WakeSource.PROVIDER);
            case "Job":
            case "Sync":
                return Resolution.resolved(WakeSource.JOB_SYNC);
            case "Wakelock":
                return Resolution.resolved(WakeSource.WAKE_LOCK);
            case "AudioNotSilence":
            case "MediaUnmute":
            case "BtControl":
                return Resolution.resolved(WakeSource.AUDIO_MEDIA);
            case "traffic":
            case "navigationApp":
            case "TcpRetransmission":
                return Resolution.resolved(WakeSource.CONNECTIVITY);
            case "appSceneChanged":
            case "appZygote":
            case "backUp":
            case "exitDeepSleep":
            case "exitFF":
            case "exitFreezeDeepDoze":
            case "exitMinSystem":
            case "exitMultiWindowScene":
            case "exitStrictMode":
            case "exitThermal":
            case "force":
            case "forceUnfreeze":
            case "HansDisabled":
            case "LcdOn":
            case "Preload_UF":
            case "R_F_TargetMap":
            case "shutDown":
            case "skipImportance":
            case "trimMemPkgsExceed":
            case "UidGone":
            case "Watchdog":
            case "HansWatchDogBlocked":
            case "FreezerExempt":
            case "resetAbnormalEnergySysApp":
                return Resolution.resolved(WakeSource.SYSTEM_SCENE);
            default:
                return Resolution.failed(Failure.UNKNOWN_REASON);
        }
    }

    public static boolean isModuleRecovery(String reason, String componentInfo) {
        return MODULE_RECOVERY_REASON.equals(reason)
                && MODULE_RECOVERY_COMPONENT.equals(componentInfo);
    }

    public enum Failure {
        UNKNOWN_KERNEL_TYPE,
        NULL_REASON,
        EMPTY_REASON,
        UNKNOWN_REASON
    }

    public static final class Resolution {
        private final WakeSource source;
        private final Failure failure;

        private Resolution(WakeSource source, Failure failure) {
            this.source = source;
            this.failure = failure;
        }

        private static Resolution resolved(WakeSource source) {
            return new Resolution(Objects.requireNonNull(source, "source"), null);
        }

        private static Resolution failed(Failure failure) {
            return new Resolution(null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean isResolved() { return source != null; }
        public WakeSource sourceOrNull() { return source; }
        public Failure failureOrNull() { return failure; }
    }
}
