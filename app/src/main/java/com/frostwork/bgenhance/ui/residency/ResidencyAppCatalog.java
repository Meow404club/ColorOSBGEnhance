package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import com.frostwork.bgenhance.residency.config.ModuleConfigRepository;
import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads installed application metadata away from the main thread. */
public final class ResidencyAppCatalog {
    public enum Source {
        MANAGED,
        PICKER
    }

    private final Context context;
    private final PackageManager packageManager;

    public ResidencyAppCatalog(Context context) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.packageManager = this.context.getPackageManager();
    }

    public List<ResidencyAppItem> load(Source source) {
        ModuleConfigRepository.ensureInitialized(context);
        ResidencyPolicyDocument document = ModuleConfigRepository.readResidencyPolicy(context);
        if (source == Source.MANAGED) {
            return loadManaged(document);
        }
        return loadPicker(document);
    }

    public ResidencyAppItem loadSingle(String packageName) {
        return loadSingle(packageName, AppResidencyPolicy.PRIMARY_USER_ID);
    }

    public ResidencyAppItem loadSingle(String packageName, int userId) {
        Objects.requireNonNull(packageName, "packageName");
        ModuleConfigRepository.ensureInitialized(context);
        ResidencyPolicyDocument document = ModuleConfigRepository.readResidencyPolicy(context);
        return resolve(packageName, userId, findPolicy(document, packageName, userId));
    }

    /**
     * Accepts a profile only when its handle is the handle of {@code info.uid} and that UID's
     * user is the requested user. The UID is never rebuilt from another user's app id.
     */
    static boolean matches(UserHandle profile, ApplicationInfo info, int expectedUserId) {
        if (profile == null || info == null || info.packageName == null || info.uid < 0) {
            return false;
        }
        if (!profile.equals(UserHandle.getUserHandleForUid(info.uid))) return false;
        try {
            return AppInstanceKey.fromUid(info.packageName, info.uid).userId() == expectedUserId;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<ResidencyAppItem> loadManaged(ResidencyPolicyDocument document) {
        List<ResidencyAppItem> result = new ArrayList<>(document.rules().size());
        for (AppResidencyPolicy policy : document.rules()) {
            result.add(resolve(policy.packageName(), policy.userId(), policy));
        }
        sort(result);
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("deprecation")
    private List<ResidencyAppItem> loadPicker(ResidencyPolicyDocument document) {
        Map<AppInstanceKey, AppResidencyPolicy> policies = index(document);
        int currentUserId = currentUserId();
        UserHandle currentUser = Process.myUserHandle();
        Set<String> launchable = launcherPackages();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        LinkedHashMap<AppInstanceKey, ResidencyAppItem> result = new LinkedHashMap<>();
        for (ApplicationInfo info : packageManager.getInstalledApplications(flags)) {
            if (!matches(currentUser, info, currentUserId)) continue;
            AppInstanceKey key = AppInstanceKey.fromUid(info.packageName, info.uid);
            result.put(key, createInstalled(info, policies.get(key), launchable.contains(key.packageName())));
        }
        addProfileLauncherEntries(policies, currentUser, result);
        List<ResidencyAppItem> items = new ArrayList<>(result.values());
        sort(items);
        return Collections.unmodifiableList(items);
    }

    private void addProfileLauncherEntries(
            Map<AppInstanceKey, AppResidencyPolicy> policies,
            UserHandle currentUser,
            Map<AppInstanceKey, ResidencyAppItem> result) {
        UserManager userManager = context.getSystemService(UserManager.class);
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        if (userManager == null || launcherApps == null) return;
        Set<UserHandle> visible = new HashSet<>(userManager.getUserProfiles());
        for (UserHandle profile : launcherApps.getProfiles()) {
            if (profile == null || profile.equals(currentUser) || !visible.contains(profile)) {
                continue;
            }
            List<LauncherActivityInfo> activities;
            try {
                activities = launcherApps.getActivityList(null, profile);
            } catch (RuntimeException exception) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (LauncherActivityInfo activity : activities) {
                if (activity.getComponentName() == null) continue;
                String packageName = activity.getComponentName().getPackageName();
                if (packageName == null || !seen.add(packageName)) continue;
                ApplicationInfo info;
                try {
                    info = launcherApps.getApplicationInfo(packageName, 0, profile);
                } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
                    continue;
                }
                if (info.uid < 0 || !matches(profile, info,
                        AppInstanceKey.fromUid(info.packageName, info.uid).userId())) {
                    continue;
                }
                AppInstanceKey key = AppInstanceKey.fromUid(info.packageName, info.uid);
                if (key.userId() == currentUserId()) continue;
                result.put(key, createInstalled(info, policies.get(key), true));
            }
        }
    }

    private ResidencyAppItem resolve(
            String packageName, int userId, AppResidencyPolicy policy) {
        if (!AppInstanceKey.isRepresentableUserId(userId) || packageName.isEmpty()) {
            return absent(packageName, Math.max(userId, 0), policy);
        }
        ApplicationInfo info = lookup(packageName, userId);
        if (info == null) return absent(packageName, userId, policy);
        boolean launchable = isLaunchable(packageName, userId, info.uid);
        return createInstalled(info, policy, launchable);
    }

    private ApplicationInfo lookup(String packageName, int userId) {
        int currentUserId = currentUserId();
        if (userId == currentUserId) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                return matches(Process.myUserHandle(), info, userId) ? info : null;
            } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
                return null;
            }
        }
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        UserManager userManager = context.getSystemService(UserManager.class);
        if (launcherApps == null || userManager == null) return null;
        for (UserHandle profile : userManager.getUserProfiles()) {
            if (profile == null || profile.equals(Process.myUserHandle())) continue;
            try {
                ApplicationInfo info = launcherApps.getApplicationInfo(packageName, 0, profile);
                if (matches(profile, info, userId)) return info;
            } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
                // This profile does not contain the package, or the UID does not belong to it.
            }
        }
        return null;
    }

    private boolean isLaunchable(String packageName, int userId, int uid) {
        if (userId == currentUserId()) return launcherPackages().contains(packageName);
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        if (launcherApps == null) return false;
        UserHandle profile = UserHandle.getUserHandleForUid(uid);
        try {
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(packageName, profile);
            return activities != null && !activities.isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ResidencyAppItem createInstalled(
            ApplicationInfo info, AppResidencyPolicy policy, boolean launchable) {
        AppInstanceKey key = AppInstanceKey.fromUid(info.packageName, info.uid);
        int systemMask = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return new ResidencyAppItem(
                safeLabel(info),
                key.packageName(),
                key.userId(),
                info.uid,
                true,
                (info.flags & systemMask) != 0,
                launchable,
                ResidencyAppSafety.inspect(context, info),
                policy);
    }

    private ResidencyAppItem absent(String packageName, int userId, AppResidencyPolicy policy) {
        String label = packageName == null || packageName.isEmpty() ? "unknown" : packageName;
        int safeUserId = AppInstanceKey.isRepresentableUserId(userId)
                ? userId : AppResidencyPolicy.PRIMARY_USER_ID;
        return new ResidencyAppItem(
                label,
                label,
                safeUserId,
                -1,
                false,
                false,
                false,
                ResidencyAppSafety.Result.restricted(ResidencyAppSafety.Restriction.NOT_INSTALLED),
                policy);
    }

    @SuppressWarnings("deprecation")
    private Set<String> launcherPackages() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        Set<String> result = new HashSet<>();
        for (ResolveInfo activity : activities) {
            if (activity.activityInfo != null && activity.activityInfo.packageName != null) {
                result.add(activity.activityInfo.packageName);
            }
        }
        return result;
    }

    private String safeLabel(ApplicationInfo info) {
        try {
            CharSequence label = info.loadLabel(packageManager);
            if (label != null && label.length() > 0) return label.toString();
        } catch (RuntimeException ignored) {
            // The package name remains a stable fallback when a label resource is unavailable.
        }
        return info.packageName;
    }

    private int currentUserId() {
        return AppInstanceKey.fromUid(context.getPackageName(), Process.myUid()).userId();
    }

    private static Map<AppInstanceKey, AppResidencyPolicy> index(ResidencyPolicyDocument document) {
        Map<AppInstanceKey, AppResidencyPolicy> policies = new HashMap<>();
        for (AppResidencyPolicy policy : document.rules()) {
            policies.put(policy.instanceKey(), policy);
        }
        return policies;
    }

    private static AppResidencyPolicy findPolicy(
            ResidencyPolicyDocument document, String packageName, int userId) {
        if (!AppInstanceKey.isRepresentableUserId(userId) || packageName.isEmpty()) return null;
        AppInstanceKey key = new AppInstanceKey(packageName, userId);
        for (AppResidencyPolicy policy : document.rules()) {
            if (policy.instanceKey().equals(key)) return policy;
        }
        return null;
    }

    private static void sort(List<ResidencyAppItem> items) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        items.sort((first, second) -> {
            int byLabel = collator.compare(first.label(), second.label());
            if (byLabel != 0) return byLabel;
            int byPackage = first.packageName().compareTo(second.packageName());
            if (byPackage != 0) return byPackage;
            return Integer.compare(first.userId(), second.userId());
        });
    }
}
