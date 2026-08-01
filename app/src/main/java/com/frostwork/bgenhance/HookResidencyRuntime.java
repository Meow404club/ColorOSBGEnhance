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
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyPackageChangeFilter;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeController;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.concurrent.Executors;

/** Process-local facade used by hooks; all platform identity work stays on a background thread. */
public final class HookResidencyRuntime {
    private HookResidencyRuntime() {}

    private static final ResidencyRuntimeController CONTROLLER =
            new ResidencyRuntimeController(Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BgEnhanceIdentity");
        thread.setDaemon(true);
        return thread;
    }));
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
    private static boolean sPackageReceiverRegistered;

    public static synchronized void attach(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        Context host = application != null ? application : context;
        try {
            ensurePackageReceiver(host);
            PackageManager packageManager = host.getPackageManager();
            if (packageManager == sPackageManager) return;
            CONTROLLER.setPackageUidSource(
                    new AndroidPackageUidSource(packageManager));
            sPackageManager = packageManager;
        } catch (Throwable error) {
            ModuleLog.w("residency identity source attach failed: " + error);
        }
    }

    private static void ensurePackageReceiver(Context context) {
        if (sPackageReceiverRegistered) return;
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
        sPackageReceiverRegistered = true;
    }

    public static void updateConfig(HookRuntimeConfigState state) {
        if (state == null) return;
        CONTROLLER.updateConfig(state.snapshot());
    }

    public static ResidencyRuntimeState state() { return CONTROLLER.state(); }
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
