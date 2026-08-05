package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import com.frostwork.bgenhance.residency.config.ModuleConfigRepository;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
        ModuleConfigRepository.ensureInitialized(context);
        ResidencyPolicyDocument document = ModuleConfigRepository.readResidencyPolicy(context);
        return createItem(packageName, findPolicy(document, packageName), launcherPackages());
    }

    private List<ResidencyAppItem> loadManaged(ResidencyPolicyDocument document) {
        Set<String> launchable = launcherPackages();
        List<ResidencyAppItem> result = new ArrayList<>(document.rules().size());
        for (AppResidencyPolicy policy : document.rules()) {
            result.add(createItem(policy.packageName(), policy, launchable));
        }
        sort(result);
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("deprecation")
    private List<ResidencyAppItem> loadPicker(ResidencyPolicyDocument document) {
        Map<String, AppResidencyPolicy> policies = new HashMap<>();
        for (AppResidencyPolicy policy : document.rules()) {
            policies.put(policy.packageName(), policy);
        }
        Set<String> launchable = launcherPackages();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        List<ApplicationInfo> applications = packageManager.getInstalledApplications(flags);
        List<ResidencyAppItem> result = new ArrayList<>(applications.size());
        for (ApplicationInfo info : applications) {
            result.add(createItem(info, policies.get(info.packageName), launchable));
        }
        sort(result);
        return Collections.unmodifiableList(result);
    }

    private ResidencyAppItem createItem(
            String packageName,
            AppResidencyPolicy policy,
            Set<String> launchablePackages) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return createItem(info, policy, launchablePackages);
        } catch (PackageManager.NameNotFoundException exception) {
            return new ResidencyAppItem(
                    packageName,
                    packageName,
                    false,
                    false,
                    false,
                    ResidencyAppSafety.Result.restricted(
                            ResidencyAppSafety.Restriction.NOT_INSTALLED),
                    policy);
        }
    }

    private ResidencyAppItem createItem(
            ApplicationInfo info,
            AppResidencyPolicy policy,
            Set<String> launchablePackages) {
        String packageName = info.packageName;
        int systemMask = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return new ResidencyAppItem(
                safeLabel(info),
                packageName,
                true,
                (info.flags & systemMask) != 0,
                launchablePackages.contains(packageName),
                ResidencyAppSafety.inspect(context, info),
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

    private static AppResidencyPolicy findPolicy(
            ResidencyPolicyDocument document, String packageName) {
        for (AppResidencyPolicy policy : document.rules()) {
            if (policy.packageName().equals(packageName)) return policy;
        }
        return null;
    }

    private static void sort(List<ResidencyAppItem> items) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        items.sort((first, second) -> {
            int byLabel = collator.compare(first.label(), second.label());
            if (byLabel != 0) return byLabel;
            return first.packageName().compareTo(second.packageName());
        });
    }
}
