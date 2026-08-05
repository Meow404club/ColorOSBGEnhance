package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.WakeContext;
import com.frostwork.bgenhance.residency.decision.WakeDecision;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.RuntimeConfigSnapshot;
import java.util.Collections;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class DiagnosticsUidResolverTest {
    private static final int UID = 10_771;
    private static final String PACKAGE = "com.example.uid.reader";

    @Test
    public void uniqueApplicationResolvesToManageableDisplayIdentity() {
        Application application = RuntimeEnvironment.getApplication();
        install(application, PACKAGE, "Reader", UID);
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            DiagnosticsDataSource.UidResolution resolution =
                    resolver.resolve(Set.of(UID)).items.get(UID);

            assertEquals(DiagnosticsDataSource.UidKind.SINGLE_APPLICATION,
                    resolution.kind);
            assertEquals(1, resolution.applications.size());
            assertEquals(PACKAGE, resolution.applications.get(0).packageName);
            assertEquals("Reader", resolution.applications.get(0).displayName);
            assertTrue(resolution.applications.get(0).manageable);
        } finally {
            resolver.close();
        }
    }

    @Test
    public void sharedUidIsRenderedAsAGroupAndCannotManageOneMember() {
        Application application = RuntimeEnvironment.getApplication();
        install(application, "com.example.shared.one", "First", UID + 1);
        install(application, "com.example.shared.two", "Second", UID + 1);
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            DiagnosticsDataSource.UidResolution resolution =
                    resolver.resolve(Set.of(UID + 1)).items.get(UID + 1);

            assertEquals(DiagnosticsDataSource.UidKind.SHARED_UID, resolution.kind);
            assertEquals(2, resolution.applications.size());
            assertFalse(resolution.applications.get(0).manageable);
            assertFalse(resolution.applications.get(1).manageable);
        } finally {
            resolver.close();
        }
    }

    @Test
    public void otherUserUidCanBeNamedButCannotOpenCurrentUserPolicyManagement() {
        Application application = RuntimeEnvironment.getApplication();
        int otherUserUid = UID + 100_000;
        install(application, PACKAGE, "Reader", otherUserUid);
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            DiagnosticsDataSource.UidResolution resolution =
                    resolver.resolve(Set.of(otherUserUid)).items.get(otherUserUid);

            assertEquals(DiagnosticsDataSource.UidKind.SINGLE_APPLICATION,
                    resolution.kind);
            assertEquals(PACKAGE, resolution.applications.get(0).packageName);
            assertFalse(resolution.applications.get(0).manageable);
        } finally {
            resolver.close();
        }
    }

    @Test
    public void packageBroadcastInvalidatesTheFullUidCache() {
        Application application = RuntimeEnvironment.getApplication();
        int uid = UID + 2;
        install(application, "com.example.cache.one", "First", uid);
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            assertEquals(DiagnosticsDataSource.UidKind.SINGLE_APPLICATION,
                    resolver.resolve(Set.of(uid)).items.get(uid).kind);

            install(application, "com.example.cache.two", "Second", uid);
            application.sendBroadcast(new Intent(
                    Intent.ACTION_PACKAGE_ADDED,
                    Uri.parse("package:com.example.cache.two")));
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

            assertEquals(DiagnosticsDataSource.UidKind.SHARED_UID,
                    resolver.resolve(Set.of(uid)).items.get(uid).kind);
        } finally {
            resolver.close();
        }
    }

    @Test
    public void unknownUidIsRetriedAfterShortCacheExpires() {
        Application application = RuntimeEnvironment.getApplication();
        int uid = UID + 3;
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            assertEquals(DiagnosticsDataSource.UidKind.UNKNOWN,
                    resolver.resolve(Set.of(uid)).items.get(uid).kind);

            install(application, "com.example.expiring", "Expiring", uid);
            assertEquals(DiagnosticsDataSource.UidKind.UNKNOWN,
                    resolver.resolve(Set.of(uid)).items.get(uid).kind);

            ShadowSystemClock.advanceBy(Duration.ofSeconds(6));
            assertEquals(DiagnosticsDataSource.UidKind.SINGLE_APPLICATION,
                    resolver.resolve(Set.of(uid)).items.get(uid).kind);
        } finally {
            resolver.close();
        }
    }

    @Test
    public void classifiesSystemSandboxIsolatedAndUnknownUidRanges() {
        assertEquals(DiagnosticsDataSource.UidKind.SYSTEM_UID,
                DiagnosticsUidResolver.classifyUid(1_000));
        assertEquals(DiagnosticsDataSource.UidKind.SYSTEM_UID,
                DiagnosticsUidResolver.classifyUid(99_901_000));
        assertEquals(DiagnosticsDataSource.UidKind.SDK_SANDBOX_UID,
                DiagnosticsUidResolver.classifyUid(20_123));
        assertEquals(DiagnosticsDataSource.UidKind.ISOLATED_UID,
                DiagnosticsUidResolver.classifyUid(99_004));
        assertEquals(DiagnosticsDataSource.UidKind.UNKNOWN,
                DiagnosticsUidResolver.classifyUid(40_000));
    }

    @Test
    public void packageManagerIdentityCanNameAnApplicationOutsideKnownUidRanges() {
        Application application = RuntimeEnvironment.getApplication();
        int uid = 40_123;
        install(application, PACKAGE, "Reader", uid);
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            DiagnosticsDataSource.UidResolution resolution =
                    resolver.resolve(Set.of(uid)).items.get(uid);

            assertEquals(DiagnosticsDataSource.UidKind.SINGLE_APPLICATION,
                    resolution.kind);
            assertEquals(PACKAGE, resolution.applications.get(0).packageName);
            assertTrue(resolution.applications.get(0).manageable);
        } finally {
            resolver.close();
        }
    }

    @Test
    public void resolvedPackagePolicyMatchesTheUidOnlyWakeCallback() {
        Application application = RuntimeEnvironment.getApplication();
        install(application, PACKAGE, "Reader", UID);
        DiagnosticsUidResolver resolver = new DiagnosticsUidResolver(application);
        try {
            DiagnosticsDataSource.ResolvedApplication resolved =
                    resolver.resolve(Set.of(UID)).items.get(UID).applications.get(0);
            AppResidencyPolicy base = ResidencyPresets.create(
                    resolved.packageName, ResidencyMode.CUSTOM);
            WakePolicy blockedWake = new WakePolicy(
                    WakeChannelPolicy.allow(),
                    WakeChannelPolicy.allow(),
                    EnumSet.of(WakeSource.ASYNC_BINDER));
            AppResidencyPolicy policy = new AppResidencyPolicy(
                    resolved.packageName,
                    true,
                    ResidencyMode.CUSTOM,
                    base.killPolicy(),
                    base.freezePolicy(),
                    blockedWake,
                    base.resourcePolicy(),
                    base.refreezePolicy());
            RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                    3L, true, true, false, 4L,
                    Map.of(resolved.packageName, policy));
            ResidencyIdentityIndex index = ResidencyIdentityIndex.build(
                    snapshot, Map.of(resolved.packageName, UID));

            WakeDecision currentUser = new ResidencyDecisionEngine(
                    snapshot, index).decideWake(new WakeContext(
                    ResidencyTarget.forUid(UID), WakeSource.ASYNC_BINDER));
            WakeDecision unverifiedOtherUser = new ResidencyDecisionEngine(
                    snapshot, index).decideWake(new WakeContext(
                    ResidencyTarget.forUid(UID + 100_000), WakeSource.ASYNC_BINDER));

            assertEquals(WakeDecision.Action.BLOCK_WAKE,
                    currentUser.effectiveAction());
            assertEquals(WakeDecision.Action.ALLOW_SYSTEM,
                    unverifiedOtherUser.effectiveAction());
        } finally {
            resolver.close();
        }
    }

    private static void install(
            Application application, String packageName, String label, int uid) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        packageInfo.applicationInfo.uid = uid;
        packageInfo.applicationInfo.nonLocalizedLabel = label;
        ((ShadowPackageManager) Shadow.extract(application.getPackageManager()))
                .installPackage(packageInfo);
    }
}
