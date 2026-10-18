package com.frostwork.bgenhance.ui.diagnostics;

import android.content.Context;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStats;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStatsLoader;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStatsLoader;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Asynchronous query boundary consumed by the diagnostics UI data layer. */
public interface DiagnosticsDataSource extends AutoCloseable {
    CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregatesAsync(
            DiagnosticsQuery.AggregateQuery query);

    default CompletableFuture<DiagnosticsQuery.AggregatePage> queryAggregateSnapshotAsync(
            DiagnosticsQuery.AggregateQuery query, int maxRows) {
        return queryAggregatesAsync(query);
    }

    default CompletableFuture<RecentBehaviorStats> queryRecentBehaviorStatsAsync() {
        return RecentBehaviorStatsLoader.load(this::queryAggregatesAsync);
    }

    default CompletableFuture<BehaviorDetailStats> queryBehaviorDetailsAsync(
            DiagnosticsBehavior behavior) {
        if (behavior == null) {
            return CompletableFuture.completedFuture(
                    BehaviorDetailStats.empty("invalid_behavior"));
        }
        return BehaviorDetailStatsLoader.load(this::queryAggregatesAsync, behavior.wireId);
    }

    CompletableFuture<HookStatePage> queryHookStatesAsync(int limit);

    default CompletableFuture<ManagedApplicationPage> queryManagedApplicationsAsync() {
        return CompletableFuture.completedFuture(ManagedApplicationPage.unsupported());
    }

    default CompletableFuture<UidResolutionPage> resolveUidsAsync(Set<Integer> uids) {
        return CompletableFuture.completedFuture(UidResolutionPage.unsupported());
    }

    default String currentBootId() {
        return "";
    }

    @Override
    default void close() {}

    static DiagnosticsDataSource fromBackend(ResidencyDiagnosticsBackend backend) {
        return new BackendDiagnosticsDataSource(backend);
    }

    static DiagnosticsDataSource fromBackend(
            Context context, ResidencyDiagnosticsBackend backend) {
        return ManagedCatalogDiagnosticsDataSource.create(context, backend);
    }

    final class ManagedApplication {
        public final String packageName;
        public final int userId;
        public final String displayName;
        public final boolean manageable;

        public ManagedApplication(String packageName, String displayName) {
            this(packageName, displayName, true);
        }

        public ManagedApplication(
                String packageName, String displayName, boolean manageable) {
            this(packageName, 0, displayName, manageable);
        }

        public ManagedApplication(
                String packageName, int userId, String displayName, boolean manageable) {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be empty");
            }
            if (!AppInstanceKey.isRepresentableUserId(userId)) {
                throw new IllegalArgumentException("invalid userId");
            }
            this.packageName = packageName.trim();
            this.userId = userId;
            this.displayName = displayName == null || displayName.trim().isEmpty()
                    ? this.packageName : displayName.trim();
            this.manageable = manageable;
        }
    }

    enum UidKind {
        SINGLE_APPLICATION,
        SHARED_UID,
        SYSTEM_UID,
        APP_ZYGOTE_UID,
        ISOLATED_UID,
        SDK_SANDBOX_UID,
        RESOLUTION_FAILED,
        UNKNOWN
    }

    final class ResolvedApplication {
        public final String packageName;
        public final int userId;
        public final String displayName;
        public final boolean manageable;
        public final boolean currentUser;
        public final boolean identityConfirmed;

        public ResolvedApplication(
                String packageName, String displayName, boolean manageable) {
            this(packageName, displayName, manageable, true, true);
        }

        public ResolvedApplication(
                String packageName,
                String displayName,
                boolean manageable,
                boolean currentUser,
                boolean identityConfirmed) {
            this(packageName, 0, displayName, manageable, currentUser, identityConfirmed);
        }

        public ResolvedApplication(
                String packageName,
                int userId,
                String displayName,
                boolean manageable,
                boolean currentUser,
                boolean identityConfirmed) {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be empty");
            }
            if (!AppInstanceKey.isRepresentableUserId(userId)) {
                throw new IllegalArgumentException("invalid userId");
            }
            this.packageName = packageName.trim();
            this.userId = userId;
            this.displayName = displayName == null || displayName.trim().isEmpty()
                    ? this.packageName : displayName.trim();
            this.manageable = manageable;
            this.currentUser = currentUser;
            this.identityConfirmed = identityConfirmed;
            if (manageable && !identityConfirmed) {
                throw new IllegalArgumentException(
                        "manageable application identity must be confirmed");
            }
        }
    }

    final class UidResolution {
        public final int uid;
        public final UidKind kind;
        public final List<ResolvedApplication> applications;

        public UidResolution(
                int uid, UidKind kind, List<ResolvedApplication> applications) {
            this.uid = uid;
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
            if (uid < -1 || (uid < 0 && kind != UidKind.UNKNOWN)) {
                throw new IllegalArgumentException("invalid UID for " + kind);
            }
            ArrayList<ResolvedApplication> applicationCopy = new ArrayList<>();
            LinkedHashSet<String> packageNames = new LinkedHashSet<>();
            if (applications != null) {
                for (ResolvedApplication application : applications) {
                    ResolvedApplication value = java.util.Objects.requireNonNull(
                            application, "application");
                    if (value.identityConfirmed && (uid < 0 || value.userId != uid / AppInstanceKey.PER_USER_RANGE)) {
                        throw new IllegalArgumentException(
                                "confirmed application user does not match its UID");
                    }
                    if (!packageNames.add(value.packageName)) {
                        throw new IllegalArgumentException(
                                "duplicate application in UID resolution");
                    }
                    applicationCopy.add(value);
                }
            }
            this.applications = applicationCopy.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(applicationCopy);
            if (kind == UidKind.SINGLE_APPLICATION && this.applications.size() != 1) {
                throw new IllegalArgumentException(
                        "single-application resolution must contain exactly one application");
            }
            if (kind == UidKind.SHARED_UID && this.applications.size() < 2) {
                throw new IllegalArgumentException(
                        "shared-UID resolution must contain at least two applications");
            }
        }

        public static UidResolution classified(int uid, UidKind kind) {
            return new UidResolution(uid, kind, Collections.emptyList());
        }

        public static UidResolution classified(
                int uid, UidKind kind, List<ResolvedApplication> applications) {
            return new UidResolution(uid, kind, applications);
        }

        public static UidResolution single(int uid, ResolvedApplication application) {
            return new UidResolution(uid, UidKind.SINGLE_APPLICATION,
                    Collections.singletonList(application));
        }

        public static UidResolution shared(
                int uid, List<ResolvedApplication> applications) {
            return new UidResolution(uid, UidKind.SHARED_UID, applications);
        }
    }

    final class UidResolutionPage {
        public final Map<Integer, UidResolution> items;
        public final boolean supported;
        public final String errorCode;

        private UidResolutionPage(
                Map<Integer, UidResolution> items,
                boolean supported,
                String errorCode) {
            this.supported = supported;
            this.errorCode = normalizeError(errorCode);
            if (!this.errorCode.isEmpty() || items == null || items.isEmpty()) {
                this.items = Collections.emptyMap();
            } else {
                LinkedHashMap<Integer, UidResolution> copy = new LinkedHashMap<>();
                for (Map.Entry<Integer, UidResolution> entry : items.entrySet()) {
                    Integer uid = java.util.Objects.requireNonNull(
                            entry.getKey(), "uid");
                    UidResolution resolution = java.util.Objects.requireNonNull(
                            entry.getValue(), "resolution");
                    if (uid < 0 || uid != resolution.uid) {
                        throw new IllegalArgumentException(
                                "UID resolution key does not match its value");
                    }
                    copy.put(uid, resolution);
                }
                this.items = Collections.unmodifiableMap(copy);
            }
        }

        public static UidResolutionPage success(Map<Integer, UidResolution> items) {
            return new UidResolutionPage(items, true, "");
        }

        public static UidResolutionPage unsupported() {
            return new UidResolutionPage(Collections.emptyMap(), false, "");
        }

        public static UidResolutionPage failure(String errorCode) {
            String normalized = normalizeError(errorCode);
            return new UidResolutionPage(Collections.emptyMap(), true,
                    normalized.isEmpty() ? "identity_unavailable" : normalized);
        }

        public boolean isSuccessful() {
            return errorCode.isEmpty();
        }
    }

    /**
     * An authoritative page is the left-hand application set. Unsupported pages preserve the
     * aggregate-only behavior used by Context-free callers.
     */
    final class ManagedApplicationPage {
        public final List<ManagedApplication> items;
        public final boolean authoritative;
        public final String errorCode;

        private ManagedApplicationPage(
                List<ManagedApplication> items, boolean authoritative, String errorCode) {
            String normalizedError = normalizeError(errorCode);
            this.authoritative = authoritative;
            this.errorCode = normalizedError;
            if (!normalizedError.isEmpty() || items == null || items.isEmpty()) {
                this.items = Collections.emptyList();
            } else {
                this.items = Collections.unmodifiableList(new ArrayList<>(items));
            }
        }

        public static ManagedApplicationPage success(List<ManagedApplication> items) {
            return new ManagedApplicationPage(items, true, "");
        }

        public static ManagedApplicationPage unsupported() {
            return new ManagedApplicationPage(Collections.emptyList(), false, "");
        }

        public static ManagedApplicationPage failure(String errorCode) {
            String normalized = normalizeError(errorCode);
            return new ManagedApplicationPage(Collections.emptyList(), true,
                    normalized.isEmpty() ? "catalog_unavailable" : normalized);
        }

        public boolean isSuccessful() {
            return errorCode.isEmpty();
        }
    }

    /** UI-facing Hook query result with an explicit error channel. */
    final class HookStatePage {
        public final List<DiagnosticsHookState> items;
        public final String errorCode;

        private HookStatePage(List<DiagnosticsHookState> items, String errorCode) {
            String normalizedError = normalizeError(errorCode);
            this.errorCode = normalizedError;
            if (!normalizedError.isEmpty() || items == null || items.isEmpty()) {
                this.items = Collections.emptyList();
            } else {
                this.items = Collections.unmodifiableList(new ArrayList<>(items));
            }
        }

        public static HookStatePage success(List<DiagnosticsHookState> items) {
            return new HookStatePage(items, "");
        }

        public static HookStatePage failure(String errorCode) {
            String normalized = normalizeError(errorCode);
            return new HookStatePage(Collections.emptyList(),
                    normalized.isEmpty() ? "database_unavailable" : normalized);
        }

        public boolean isSuccessful() {
            return errorCode.isEmpty();
        }

        private static String normalizeError(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private static String normalizeError(String value) {
        return value == null ? "" : value.trim();
    }
}
