package com.frostwork.bgenhance.ui.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import com.frostwork.bgenhance.ui.residency.ResidencyAppSafety;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Resolves diagnostic UIDs away from Hook and main threads. */
final class DiagnosticsUidResolver implements AutoCloseable {
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final int PER_USER_RANGE = 100_000;
    private static final int FIRST_APPLICATION_UID = 10_000;
    private static final int LAST_APPLICATION_UID = 19_999;
    private static final int FIRST_SDK_SANDBOX_UID = 20_000;
    private static final int LAST_SDK_SANDBOX_UID = 29_999;
    private static final int FIRST_APP_ZYGOTE_UID = 90_000;
    private static final int LAST_APP_ZYGOTE_UID = 98_999;
    private static final int FIRST_ISOLATED_UID = 99_000;
    private static final int LAST_ISOLATED_UID = 99_999;
    private static final long RESOLVED_CACHE_MILLIS = 5 * 60_000L;
    private static final long UNKNOWN_CACHE_MILLIS = 5_000L;
    private static final long FAILURE_CACHE_MILLIS = 2_000L;

    private final Object lock = new Object();
    private final Context context;
    private final PackageManager packageManager;
    private final LinkedHashMap<Integer, CacheEntry> cache =
            new LinkedHashMap<>();
    private long cacheEpoch;
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            clearCache();
        }
    };
    private boolean receiverRegistered;
    private boolean closed;

    DiagnosticsUidResolver(Context context) {
        Context requested = Objects.requireNonNull(context, "context");
        Context applicationContext = requested.getApplicationContext();
        this.context = applicationContext == null ? requested : applicationContext;
        this.packageManager = this.context.getPackageManager();
        registerPackageChanges();
    }

    DiagnosticsDataSource.UidResolutionPage resolve(Set<Integer> requestedUids) {
        if (requestedUids == null || requestedUids.isEmpty()) {
            return DiagnosticsDataSource.UidResolutionPage.success(Collections.emptyMap());
        }
        TreeSet<Integer> uids = new TreeSet<>();
        for (Integer uid : requestedUids) {
            if (uid != null && uid >= 0) uids.add(uid);
        }
        LinkedHashMap<Integer, DiagnosticsDataSource.UidResolution> result =
                new LinkedHashMap<>();
        for (int uid : uids) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException(
                        "UID resolution cancelled");
            }
            result.put(uid, resolveOne(uid));
        }
        return DiagnosticsDataSource.UidResolutionPage.success(result);
    }

    @Override
    public void close() {
        boolean unregister;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            cache.clear();
            unregister = receiverRegistered;
            receiverRegistered = false;
        }
        if (unregister) {
            try {
                context.unregisterReceiver(packageChanges);
            } catch (RuntimeException ignored) {
                // Receiver teardown is best effort during Activity destruction.
            }
        }
    }

    static DiagnosticsDataSource.UidKind classifyUid(int uid) {
        if (uid < 0) return DiagnosticsDataSource.UidKind.UNKNOWN;
        int appId = uid % PER_USER_RANGE;
        if (appId < FIRST_APPLICATION_UID) {
            return DiagnosticsDataSource.UidKind.SYSTEM_UID;
        }
        if (appId >= FIRST_SDK_SANDBOX_UID && appId <= LAST_SDK_SANDBOX_UID) {
            return DiagnosticsDataSource.UidKind.SDK_SANDBOX_UID;
        }
        if (appId >= FIRST_APP_ZYGOTE_UID && appId <= LAST_APP_ZYGOTE_UID) {
            return DiagnosticsDataSource.UidKind.APP_ZYGOTE_UID;
        }
        if (appId >= FIRST_ISOLATED_UID && appId <= LAST_ISOLATED_UID) {
            return DiagnosticsDataSource.UidKind.ISOLATED_UID;
        }
        if (appId > LAST_APPLICATION_UID) {
            return DiagnosticsDataSource.UidKind.UNKNOWN;
        }
        return DiagnosticsDataSource.UidKind.SINGLE_APPLICATION;
    }

    private DiagnosticsDataSource.UidResolution resolveOne(int uid) {
        long epoch;
        synchronized (lock) {
            CacheEntry cached = cache.get(uid);
            long now = SystemClock.elapsedRealtime();
            if (cached != null && cached.expiresAt > now) return cached.resolution;
            if (cached != null) cache.remove(uid);
            if (closed) {
                return DiagnosticsDataSource.UidResolution.classified(
                        uid, DiagnosticsDataSource.UidKind.UNKNOWN);
            }
            epoch = cacheEpoch;
        }

        DiagnosticsDataSource.UidResolution resolved = resolveUncached(uid);
        synchronized (lock) {
            if (!closed && cacheEpoch == epoch) {
                cache.put(uid, new CacheEntry(
                        resolved,
                        SystemClock.elapsedRealtime() + cacheDuration(resolved.kind)));
                while (cache.size() > MAX_CACHE_ENTRIES) {
                    Integer eldest = cache.keySet().iterator().next();
                    cache.remove(eldest);
                }
            }
        }
        return resolved;
    }

    private DiagnosticsDataSource.UidResolution resolveUncached(int uid) {
        DiagnosticsDataSource.UidKind uidKind = classifyUid(uid);
        if (uidKind == DiagnosticsDataSource.UidKind.APP_ZYGOTE_UID
                || uidKind == DiagnosticsDataSource.UidKind.ISOLATED_UID
                || uidKind == DiagnosticsDataSource.UidKind.SDK_SANDBOX_UID) {
            return DiagnosticsDataSource.UidResolution.classified(uid, uidKind);
        }
        try {
            String[] packageArray = packageManager.getPackagesForUid(uid);
            if (packageArray == null || packageArray.length == 0) {
                return DiagnosticsDataSource.UidResolution.classified(
                        uid, DiagnosticsDataSource.UidKind.UNKNOWN);
            }
            LinkedHashSet<String> packageNames = new LinkedHashSet<>();
            for (String packageName : packageArray) {
                if (packageName != null && !packageName.trim().isEmpty()) {
                    packageNames.add(packageName.trim());
                }
            }
            if (packageNames.isEmpty()) {
                return DiagnosticsDataSource.UidResolution.classified(
                        uid, DiagnosticsDataSource.UidKind.UNKNOWN);
            }
            ArrayList<String> sortedPackages = new ArrayList<>(packageNames);
            Collections.sort(sortedPackages);
            ArrayList<DiagnosticsDataSource.ResolvedApplication> applications =
                    new ArrayList<>(sortedPackages.size());
            boolean shared = sortedPackages.size() > 1;
            for (String packageName : sortedPackages) {
                applications.add(resolveApplication(packageName, uid, !shared));
            }
            if (uidKind == DiagnosticsDataSource.UidKind.SYSTEM_UID) {
                return DiagnosticsDataSource.UidResolution.classified(
                        uid, uidKind, applications);
            }
            return shared
                    ? DiagnosticsDataSource.UidResolution.shared(uid, applications)
                    : DiagnosticsDataSource.UidResolution.single(uid, applications.get(0));
        } catch (RuntimeException error) {
            return DiagnosticsDataSource.UidResolution.classified(
                    uid, DiagnosticsDataSource.UidKind.RESOLUTION_FAILED);
        }
    }

    private DiagnosticsDataSource.ResolvedApplication resolveApplication(
            String packageName, int requestedUid, boolean allowManagement) {
        String label = packageName;
        boolean currentUser = isCurrentUserUid(requestedUid);
        boolean localIdentityConfirmed = false;
        if (currentUser) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                localIdentityConfirmed = info.uid == requestedUid;
                CharSequence loaded = info.loadLabel(packageManager);
                if (loaded != null && loaded.length() > 0) label = loaded.toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // The full UID membership is still known; package name remains the safe label.
            }
        }
        boolean manageable = allowManagement
                && localIdentityConfirmed
                && !ResidencyAppSafety.inspect(context, packageName).isRestricted();
        return new DiagnosticsDataSource.ResolvedApplication(
                packageName, label, manageable, currentUser, localIdentityConfirmed);
    }

    private void clearCache() {
        synchronized (lock) {
            cacheEpoch++;
            cache.clear();
        }
    }

    private static boolean isCurrentUserUid(int uid) {
        return uid >= 0
                && uid / PER_USER_RANGE == Process.myUid() / PER_USER_RANGE;
    }

    private static long cacheDuration(DiagnosticsDataSource.UidKind kind) {
        if (kind == DiagnosticsDataSource.UidKind.RESOLUTION_FAILED) {
            return FAILURE_CACHE_MILLIS;
        }
        if (kind == DiagnosticsDataSource.UidKind.UNKNOWN) {
            return UNKNOWN_CACHE_MILLIS;
        }
        return RESOLVED_CACHE_MILLIS;
    }

    private static final class CacheEntry {
        final DiagnosticsDataSource.UidResolution resolution;
        final long expiresAt;

        CacheEntry(DiagnosticsDataSource.UidResolution resolution, long expiresAt) {
            this.resolution = Objects.requireNonNull(resolution, "resolution");
            this.expiresAt = expiresAt;
        }
    }

    private void registerPackageChanges() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                        packageChanges, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(packageChanges, filter);
            }
            synchronized (lock) {
                if (!closed) receiverRegistered = true;
            }
        } catch (RuntimeException ignored) {
            // A short-lived cache remains correct for the current package snapshot.
        }
    }
}
