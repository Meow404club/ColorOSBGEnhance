package com.frostwork.bgenhance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionContext;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionDecision;
import com.frostwork.bgenhance.residency.decision.FreezeTimingContext;
import com.frostwork.bgenhance.residency.decision.FreezeTimingDecision;
import com.frostwork.bgenhance.residency.decision.KillContext;
import com.frostwork.bgenhance.residency.decision.KillDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.RefreezeContext;
import com.frostwork.bgenhance.residency.decision.RefreezeDecision;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.runtime.AndroidPackageUidSource;
import com.frostwork.bgenhance.residency.runtime.HookRuntimeConfigState;
import com.frostwork.bgenhance.residency.runtime.PackageUidSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyPackageChangeFilter;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-local facade used by hooks; all platform identity work stays on a background thread. */
public final class HookResidencyRuntime {
    private static final String ACTION_USER_ADDED = "android.intent.action.USER_ADDED";
    private static final String ACTION_USER_REMOVED = "android.intent.action.USER_REMOVED";

    private HookResidencyRuntime() {}

    private static final ScheduledThreadPoolExecutor IDENTITY_EXECUTOR =
            createIdentityExecutor();
    private static final ResidencyRuntimeController.RetryScheduler IDENTITY_RETRY_SCHEDULER =
            (command, delayMs) -> {
        ScheduledFuture<?> future = IDENTITY_EXECUTOR.schedule(
                command, delayMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    };
    private static final ResidencyRuntimeController CONTROLLER =
            new ResidencyRuntimeController(IDENTITY_EXECUTOR, IDENTITY_RETRY_SCHEDULER);
    static {
        CONTROLLER.addStateListener(HookDiagnostics::reportResidencyRuntime);
    }

    private static ScheduledThreadPoolExecutor createIdentityExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "BgEnhanceIdentity");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final RetryingRegistration HOST_INITIALIZATION =
            new RetryingRegistration(
                    IDENTITY_EXECUTOR::schedule,
                    new RetryingRegistration.Observer() {
                        @Override
                        public void onFirstFailure(Throwable error) {
                            ModuleLog.w("residency host initialization deferred: " + error);
                        }

                        @Override
                        public void onRecovery() {
                            ModuleLog.i("residency host initialization recovered");
                        }
                    },
                    2_000L,
                    60_000L);
    private static final ResidencyDecisionEngine FAIL_OPEN_ENGINE =
            new ResidencyDecisionEngine(
                    RuntimeConfigSnapshot.unavailable(0L),
                    ResidencyIdentityIndex.invalidated());
    private static final BroadcastReceiver PACKAGE_CHANGE_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_USER_ADDED.equals(action) || ACTION_USER_REMOVED.equals(action)) {
                CONTROLLER.invalidateIdentities();
                return;
            }
            Uri data = intent == null ? null : intent.getData();
            String packageName = data == null ? null : data.getSchemeSpecificPart();
            RuntimeConfigSnapshot snapshot = CONTROLLER.state().snapshot();
            if (ResidencyPackageChangeFilter.shouldInvalidate(packageName, snapshot)) {
                CONTROLLER.invalidateIdentities();
            }
        }
    };
    private static PackageManager sPackageManager;
    private static volatile PackageUidSource sPackageUidSource;

    public static synchronized void attach(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        Context host = application != null ? application : context;
        HOST_INITIALIZATION.ensure(() -> initializeHost(host));
    }

    private static void initializeHost(Context host) {
        attachPackageUidSource(host);
        try {
            registerPackageReceiver(host);
        } catch (RuntimeException error) {
            sPackageManager = null;
            sPackageUidSource = null;
            CONTROLLER.clearPackageUidSource();
            throw error;
        }
    }

    private static void attachPackageUidSource(Context host) {
        PackageManager packageManager = Objects.requireNonNull(
                host.getPackageManager(), "packageManager");
        if (packageManager == sPackageManager && sPackageUidSource != null) return;
        PackageUidSource source = new AndroidPackageUidSource(packageManager);
        sPackageUidSource = source;
        CONTROLLER.setPackageUidSource(source);
        sPackageManager = packageManager;
    }

    private static void registerPackageReceiver(Context context) {
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerForAllUsers(context, PACKAGE_CHANGE_RECEIVER, packages);

        IntentFilter users = new IntentFilter();
        users.addAction(ACTION_USER_ADDED);
        users.addAction(ACTION_USER_REMOVED);
        registerForAllUsers(context, PACKAGE_CHANGE_RECEIVER, users);
    }

    /** Hidden Context.registerReceiverAsUser. Failure stays fail-closed for every user. */
    private static void registerForAllUsers(
            Context context, BroadcastReceiver receiver, IntentFilter filter) {
        Object allUsers = allUsersHandle();
        try {
            Method method = allUserRegistration();
            if (method.getParameterTypes().length == 6) {
                method.invoke(
                        context,
                        receiver,
                        allUsers,
                        filter,
                        null,
                        null,
                        Context.RECEIVER_EXPORTED);
            } else {
                method.invoke(context, receiver, allUsers, filter, null, null);
            }
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("all-user receiver registration failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("all-user receiver registration failed", error);
        }
    }

    private static Method allUserRegistration() throws NoSuchMethodException {
        try {
            return Context.class.getMethod(
                    "registerReceiverAsUser",
                    BroadcastReceiver.class,
                    UserHandle.class,
                    IntentFilter.class,
                    String.class,
                    android.os.Handler.class,
                    int.class);
        } catch (NoSuchMethodException ignored) {
            return Context.class.getMethod(
                    "registerReceiverAsUser",
                    BroadcastReceiver.class,
                    UserHandle.class,
                    IntentFilter.class,
                    String.class,
                    android.os.Handler.class);
        }
    }

    /** Hidden all-users handle. A missing field fails host initialization. */
    private static Object allUsersHandle() {
        try {
            Field field = UserHandle.class.getField("ALL");
            Object value = field.get(null);
            if (value == null) {
                throw new IllegalStateException("all-users handle unavailable");
            }
            return value;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("all-users handle unavailable", error);
        }
    }

    public static void updateConfig(HookRuntimeConfigState state) {
        if (state == null) return;
        CONTROLLER.updateConfig(state.snapshot());
    }

    public static void addStateListener(ResidencyRuntimeController.StateListener listener) {
        CONTROLLER.addStateListener(listener);
    }

    public static void removeStateListener(ResidencyRuntimeController.StateListener listener) {
        CONTROLLER.removeStateListener(listener);
    }

    public static ResidencyRuntimeState state() { return CONTROLLER.state(); }

    /** Performs a fresh full-UID ownership check from a dedicated background worker. */
    public static Set<String> findPackagesForUidForRecovery(int uid) {
        if (uid < 0) throw new IllegalArgumentException("uid < 0");
        PackageUidSource source = sPackageUidSource;
        if (source == null || !source.hasCompleteUidEnumeration()) {
            throw new IllegalStateException("complete UID package source unavailable");
        }
        Set<String> resolved = source.findPackagesForUid(uid);
        if (resolved == null || resolved.isEmpty()) return Collections.emptySet();
        TreeSet<String> packages = new TreeSet<>();
        for (String packageName : resolved) {
            if (packageName == null || packageName.isEmpty()) {
                throw new IllegalStateException("invalid package name for UID");
            }
            packages.add(packageName);
        }
        return Collections.unmodifiableSet(packages);
    }

    public static KillDecision decideKill(KillContext context) {
        return decisionEngine().decideKill(context);
    }
    public static KillDecision decideKill(
            KillContext context, String featureKey, boolean defaultEnabled) {
        return decisionEngine().decideKill(context, featureKey, defaultEnabled);
    }
    public static FreezeDecision decideFreeze(FreezeContext context) {
        return decisionEngine().decideFreeze(context);
    }
    public static FreezeTimingDecision decideFreezeTiming(FreezeTimingContext context) {
        return decisionEngine().decideFreezeTiming(context);
    }
    public static FreezeExemptionDecision decideFreezeExemption(
            FreezeExemptionContext context) {
        return decisionEngine().decideFreezeExemption(context);
    }
    public static WakeDecision decideWake(WakeContext context) {
        return decisionEngine().decideWake(context);
    }
    public static ResourceDecision decideResource(ResourceContext context) {
        return decisionEngine().decideResource(context);
    }
    public static RefreezeDecision decideRefreeze(RefreezeContext context) {
        return decisionEngine().decideRefreeze(context);
    }

    private static ResidencyDecisionEngine decisionEngine() {
        HookRuntimeConfigState config = HookConfig.state();
        ResidencyRuntimeState runtime = CONTROLLER.state();
        if (!config.available()
                || !runtime.snapshot().available()
                || config.snapshot() != runtime.snapshot()) {
            return FAIL_OPEN_ENGINE;
        }
        return runtime.decisionEngine();
    }
}
