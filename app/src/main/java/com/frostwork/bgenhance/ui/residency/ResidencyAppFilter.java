package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure filtering logic shared by the managed list and application picker. */
public final class ResidencyAppFilter {
    private ResidencyAppFilter() {}

    public enum ModeFilter {
        ALL(null),
        SYSTEM(ResidencyMode.SYSTEM),
        BALANCED(ResidencyMode.BALANCED),
        LOW_POWER(ResidencyMode.LOW_POWER),
        ACTIVE(ResidencyMode.ACTIVE),
        CUSTOM(ResidencyMode.CUSTOM);

        private final ResidencyMode mode;

        ModeFilter(ResidencyMode mode) {
            this.mode = mode;
        }
    }

    public enum StatusFilter {
        ALL,
        ENABLED,
        DISABLED,
        UNMANAGED,
        CONFIGURABLE,
        RESTRICTED
    }

    public static List<ResidencyAppItem> apply(
            List<ResidencyAppItem> source,
            ResidencyAppCatalog.Source catalogSource,
            String query,
            ModeFilter modeFilter,
            StatusFilter statusFilter,
            boolean showSystemApps) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(catalogSource, "catalogSource");
        Objects.requireNonNull(modeFilter, "modeFilter");
        Objects.requireNonNull(statusFilter, "statusFilter");
        String normalizedQuery = query == null
                ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ResidencyAppItem> result = new ArrayList<>();
        for (ResidencyAppItem item : source) {
            if (!matchesScope(item, catalogSource, showSystemApps)
                    || !matchesQuery(item, normalizedQuery)
                    || !matchesMode(item.policy(), modeFilter)
                    || !matchesStatus(item, statusFilter)) {
                continue;
            }
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean matchesScope(
            ResidencyAppItem item,
            ResidencyAppCatalog.Source source,
            boolean showSystemApps) {
        if (source == ResidencyAppCatalog.Source.MANAGED) return true;
        if (item.systemApp()) return showSystemApps;
        return item.launchable();
    }

    private static boolean matchesQuery(ResidencyAppItem item, String query) {
        if (query.isEmpty()) return true;
        return item.label().toLowerCase(Locale.ROOT).contains(query)
                || item.packageName().toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean matchesMode(AppResidencyPolicy policy, ModeFilter filter) {
        return filter.mode == null || (policy != null && policy.mode() == filter.mode);
    }

    private static boolean matchesStatus(ResidencyAppItem item, StatusFilter filter) {
        switch (filter) {
            case ALL:
                return true;
            case ENABLED:
                return item.policy() != null && item.policy().enabled();
            case DISABLED:
                return item.policy() != null && !item.policy().enabled();
            case UNMANAGED:
                return item.policy() == null;
            case CONFIGURABLE:
                return !item.safety().isRestricted();
            case RESTRICTED:
                return item.safety().isRestricted();
            default:
                throw new AssertionError(filter);
        }
    }
}
