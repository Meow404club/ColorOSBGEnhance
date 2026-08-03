package com.frostwork.bgenhance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Process-local facade used by hooks; all platform identity work stays on a background thread. */
public final class HookResidencyRuntime {
    private HookResidencyRuntime() {}

    private static final ScheduledExecutorService IDENTITY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceIdentity");
        thread.setDaemon(true);
        return thread;
    });
    private static final ResidencyRuntimeController CONTROLLER =
            new ResidencyRuntimeController(IDENTITY_EXECUTOR);
    private static final RetryingRegistration PACKAGE_RECEIVER_REGISTRATION =
            new RetryingRegistration(
                    IDENTITY_EXECUTOR::schedule,
                    new RetryingRegistration.Observer() {
                        @Override
                        public void onFirstFailure(Throwable error) {
                            ModuleLog.w("package change receiver registration deferred: " + error);
                        }

                        @Override
                        public void onRecovery() {
                            ModuleLog.i("package change receiver registration recovered");
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
        attachPackageUidSource(host);
        PACKAGE_RECEIVER_REGISTRATION.ensure(() -> registerPackageReceiver(host));
    }

    private static void attachPackageUidSource(Context host) {
        try {
            PackageManager packageManager = host.getPackageManager();
            if (packageManager == sPackageManager) return;
            PackageUidSource source = new AndroidPackageUidSource(packageManager);
            sPackageUidSource = source;
            CONTROLLER.setPackageUidSource(source);
            sPackageManager = packageManager;
        } catch (Throwable error) {
            ModuleLog.w("residency identity source attach failed: " + error);
        }
    }

    private static void registerPackageReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                    PACKAGE_CHANGE_RECEIVER, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(PACKAGE_CHANGE_RECEIVER, filter);
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
