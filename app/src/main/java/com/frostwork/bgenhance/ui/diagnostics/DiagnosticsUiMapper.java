package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Converts storage DTOs into immutable, deterministic rows for UI adapters. */
public final class DiagnosticsUiMapper {
    public interface UiRow {
        String stableId();
    }

    public enum HookStatus {
        READY,
        INSTALLED,
        FAILED,
        UNSUPPORTED,
        SKIPPED,
        PENDING,
        UNKNOWN,
        MISSING
    }

    /** Long-backed counters; percentages must be calculated by the rendering layer. */
    public static final class Metrics {
        public final long observed;
        public final long blocked;
        public final long passed;
        public final long throttled;
        public final long failOpen;
        public final long rawHits;
        public final long identityResolved;
        public final long latencyMicrosTotal;
        public final long latencyMicrosMax;
        public final long firstAt;
        public final long lastAt;

        private Metrics(MutableMetrics source) {
            observed = source.observed;
            blocked = source.blocked;
            passed = source.passed;
            throttled = source.throttled;
            failOpen = source.failOpen;
            rawHits = source.rawHits;
            identityResolved = source.identityResolved;
            latencyMicrosTotal = source.latencyMicrosTotal;
            latencyMicrosMax = source.latencyMicrosMax;
            firstAt = source.firstAt;
            lastAt = source.lastAt;
        }

        public boolean isEmpty() {
            return observed == 0L && rawHits == 0L;
        }
    }

    public static final class ApplicationRow implements UiRow {
        public final String stableId;
        public final String packageName;
        public final String displayName;
        public final List<Integer> observedUids;
        public final Map<DiagnosticsBehavior, Metrics> behaviors;
        public final Metrics totals;

        private ApplicationRow(
                String packageName,
                String displayName,
                List<Integer> observedUids,
                Map<DiagnosticsBehavior, Metrics> behaviors,
                Metrics totals) {
            this.stableId = "application:" + packageName;
            this.packageName = packageName;
            this.displayName = displayName == null || displayName.isEmpty()
                    ? packageName : displayName;
            this.observedUids = Collections.unmodifiableList(new ArrayList<>(observedUids));
            this.behaviors = Collections.unmodifiableMap(
                    new EnumMap<>(behaviors));
            this.totals = totals;
        }

        @Override
        public String stableId() {
            return stableId;
        }

        public Metrics behavior(DiagnosticsBehavior behavior) {
            Metrics metrics = behaviors.get(behavior);
            return metrics == null ? zeroMetrics() : metrics;
        }
    }

    public static final class BehaviorRow implements UiRow {
        public final String stableId;
        public final DiagnosticsBehavior behavior;
        public final String titleKey;
        public final String fallbackTitle;
        public final Metrics metrics;

        private BehaviorRow(DiagnosticsBehavior behavior, Metrics metrics) {
            this.stableId = "behavior:" + behavior.wireId;
            this.behavior = behavior;
            this.titleKey = behavior.titleKey;
            this.fallbackTitle = behavior.fallbackTitle;
            this.metrics = metrics;
        }

        @Override
        public String stableId() {
            return stableId;
        }
    }

    public static final class HookTargetRow implements UiRow {
        public final String stableId;
        public final String hookTargetId;
        public final String expectedProcessId;
        public final String titleKey;
        public final String fallbackTitle;
        public final DiagnosticsHookCatalog.Kind kind;
        public final boolean reported;
        public final HookStatus status;
        public final String installStatus;
        public final String featureId;
        public final String bootId;
        public final long lastHookAt;
        public final long lastEventAt;
        public final long rawHitCount;
        public final long parsedCount;
        public final long identityResolvedCount;
        public final int configRevision;
        public final int policyRevision;
        public final String lastError;
        public final long updatedAt;
        public final boolean countsConsistent;

        private HookTargetRow(
                DiagnosticsHookCatalog.Entry entry,
                DiagnosticsHookState state) {
            stableId = entry.stableId();
            hookTargetId = entry.hookTargetId;
            expectedProcessId = entry.expectedProcessId;
            titleKey = entry.titleKey;
            fallbackTitle = entry.fallbackTitle;
            kind = entry.kind;
            reported = state != null;
            status = state == null ? HookStatus.MISSING : hookStatus(state.installStatus);
            installStatus = state == null ? "" : state.installStatus;
            featureId = state == null ? "" : state.featureId;
            bootId = state == null ? "" : state.bootId;
            lastHookAt = state == null ? 0L : state.lastHookAt;
            lastEventAt = state == null ? 0L : state.lastEventAt;
            rawHitCount = state == null ? 0L : state.rawHitCount;
            parsedCount = state == null ? 0L : state.parsedCount;
            identityResolvedCount = state == null ? 0L : state.identityResolvedCount;
            configRevision = state == null ? -1 : state.configRevision;
            policyRevision = state == null ? -1 : state.policyRevision;
            lastError = state == null ? "" : state.lastError;
            updatedAt = state == null ? 0L : state.updatedAt;
            countsConsistent = state == null || state.hasConsistentCounts();
        }

        @Override
        public String stableId() {
            return stableId;
        }
    }

    public List<ApplicationRow> mapApplications(
            List<DiagnosticsQuery.AggregateRow> rows) {
        return mapApplicationsInternal(null, rows);
    }

    public List<ApplicationRow> mapApplications(
            List<DiagnosticsDataSource.ManagedApplication> managedApplications,
            List<DiagnosticsQuery.AggregateRow> rows) {
        Objects.requireNonNull(managedApplications, "managedApplications");
        return mapApplicationsInternal(managedApplications, rows);
    }

    private List<ApplicationRow> mapApplicationsInternal(
            List<DiagnosticsDataSource.ManagedApplication> managedApplications,
            List<DiagnosticsQuery.AggregateRow> rows) {
        LinkedHashMap<String, ApplicationAccumulator> applications = new LinkedHashMap<>();
        if (rows != null) {
            for (DiagnosticsQuery.AggregateRow row : rows) {
                if (row == null || row.packageName == null || row.packageName.isEmpty()) continue;
                DiagnosticsBehavior behavior = DiagnosticsBehavior.fromWireId(row.behavior);
                if (behavior == null) continue;
                ApplicationAccumulator accumulator = applications.computeIfAbsent(
                        row.packageName, ApplicationAccumulator::new);
                accumulator.add(behavior, row);
            }
        }

        ArrayList<ApplicationRow> result = new ArrayList<>();
        if (managedApplications != null) {
            LinkedHashSet<String> addedPackages = new LinkedHashSet<>();
            for (DiagnosticsDataSource.ManagedApplication application : managedApplications) {
                if (application == null || !addedPackages.add(application.packageName)) continue;
                ApplicationAccumulator accumulator = applications.get(application.packageName);
                if (accumulator == null) {
                    accumulator = new ApplicationAccumulator(application.packageName);
                }
                result.add(accumulator.build(application.displayName));
            }
            return immutable(result);
        }

        for (ApplicationAccumulator accumulator : applications.values()) {
            result.add(accumulator.build(accumulator.packageName));
        }
        result.sort((left, right) -> {
            int compared = Long.compare(right.totals.observed, left.totals.observed);
            if (compared != 0) return compared;
            compared = Long.compare(right.totals.lastAt, left.totals.lastAt);
            return compared != 0 ? compared : left.packageName.compareTo(right.packageName);
        });
        return immutable(result);
    }

    public List<BehaviorRow> mapBehaviors(List<DiagnosticsQuery.AggregateRow> rows) {
        return mapBehaviors(RecentBehaviorStats.fromAggregateRows(rows));
    }

    public List<BehaviorRow> mapBehaviors(RecentBehaviorStats snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.isSuccessful()) {
            throw new IllegalArgumentException(
                    "cannot map failed behavior stats: " + snapshot.errorCode);
        }
        EnumMap<DiagnosticsBehavior, MutableMetrics> metrics = new EnumMap<>(
                DiagnosticsBehavior.class);
        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            metrics.put(behavior, new MutableMetrics());
        }
        for (RecentBehaviorStats.Row row : snapshot.rows) {
            if (row != null) {
                DiagnosticsBehavior behavior = DiagnosticsBehavior.fromWireId(row.behavior);
                metrics.get(behavior).add(row);
            }
        }

        ArrayList<BehaviorRow> result = new ArrayList<>();
        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            Metrics behaviorMetrics = metrics.get(behavior).build();
            if (behavior != DiagnosticsBehavior.UNCLASSIFIED
                    || behaviorMetrics.observed > 0L
                    || behaviorMetrics.rawHits > 0L) {
                result.add(new BehaviorRow(behavior, behaviorMetrics));
            }
        }
        return immutable(result);
    }

    public List<HookTargetRow> mapHookTargets(DiagnosticsDataSource.HookStatePage page) {
        return mapHookTargets(page, "");
    }

    public List<HookTargetRow> mapHookTargets(
            DiagnosticsDataSource.HookStatePage page, String currentBootId) {
        Objects.requireNonNull(page, "page");
        if (!page.isSuccessful()) {
            throw new IllegalArgumentException(
                    "cannot map a failed Hook state page: " + page.errorCode);
        }

        LinkedHashMap<String, DiagnosticsHookState> byIdentity = new LinkedHashMap<>();
        String expectedBootId = currentBootId == null ? "" : currentBootId.trim();
        for (DiagnosticsHookState state : page.items) {
            if (state == null) continue;
            if (!expectedBootId.isEmpty() && !expectedBootId.equals(state.bootId)) continue;
            DiagnosticsHookCatalog.Entry entry = DiagnosticsHookCatalog.find(
                    state.processId, state.hookTargetId);
            if (entry == null) continue;
            String key = identity(entry.expectedProcessId, entry.hookTargetId);
            DiagnosticsHookState previous = byIdentity.get(key);
            if (previous == null || compareState(state, previous) > 0) {
                byIdentity.put(key, state);
            }
        }

        ArrayList<HookTargetRow> result = new ArrayList<>();
        for (DiagnosticsHookCatalog.Entry entry : DiagnosticsHookCatalog.all()) {
            result.add(new HookTargetRow(entry,
                    byIdentity.get(identity(entry.expectedProcessId, entry.hookTargetId))));
        }
        return immutable(result);
    }

    private static String identity(String processId, String hookTargetId) {
        return processId + '\n' + hookTargetId;
    }

    private static int compareState(DiagnosticsHookState left, DiagnosticsHookState right) {
        int compared = Long.compare(left.updatedAt, right.updatedAt);
        if (compared != 0) return compared;
        compared = Long.compare(left.lastHookAt, right.lastHookAt);
        if (compared != 0) return compared;
        compared = Long.compare(left.lastEventAt, right.lastEventAt);
        if (compared != 0) return compared;
        compared = Long.compare(left.rawHitCount, right.rawHitCount);
        if (compared != 0) return compared;
        return left.installStatus.compareTo(right.installStatus);
    }

    private static HookStatus hookStatus(String installStatus) {
        String value = installStatus == null ? "" : installStatus;
        if (ModuleRuntimeStatus.isCurrentVersion(value, BuildConfig.VERSION_CODE)
                || value.startsWith("CONFIG_READY")) return HookStatus.READY;
        if (value.startsWith("FEATURES_INSTALLED")) return HookStatus.PENDING;
        if (value.startsWith("ENTRY_LOADED")) return HookStatus.PENDING;
        if (value.startsWith("INSTALLED")) return HookStatus.INSTALLED;
        if (value.startsWith("FAILED")
                || value.startsWith("BOOTSTRAP_FAILED")
                || value.startsWith("CONFIG_ERROR")) return HookStatus.FAILED;
        if (value.startsWith("UNSUPPORTED")) return HookStatus.UNSUPPORTED;
        if (value.startsWith("SKIPPED")) return HookStatus.SKIPPED;
        if (value.startsWith("PENDING")) return HookStatus.PENDING;
        return HookStatus.UNKNOWN;
    }

    private static Metrics zeroMetrics() {
        return new MutableMetrics().build();
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class ApplicationAccumulator {
        final String packageName;
        final TreeSet<Integer> uids = new TreeSet<>();
        final EnumMap<DiagnosticsBehavior, MutableMetrics> behaviors = new EnumMap<>(
                DiagnosticsBehavior.class);
        final MutableMetrics totals = new MutableMetrics();

        ApplicationAccumulator(String packageName) {
            this.packageName = packageName;
            for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
                behaviors.put(behavior, new MutableMetrics());
            }
        }

        void add(DiagnosticsBehavior behavior, DiagnosticsQuery.AggregateRow row) {
            if (row.uid >= 0) uids.add(row.uid);
            behaviors.get(behavior).add(row);
            totals.add(row);
        }

        ApplicationRow build(String displayName) {
            EnumMap<DiagnosticsBehavior, Metrics> immutableMetrics = new EnumMap<>(
                    DiagnosticsBehavior.class);
            for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
                immutableMetrics.put(behavior, behaviors.get(behavior).build());
            }
            return new ApplicationRow(
                    packageName, displayName, new ArrayList<>(uids),
                    immutableMetrics, totals.build());
        }
    }

    private static final class MutableMetrics {
        long observed;
        long blocked;
        long passed;
        long throttled;
        long failOpen;
        long rawHits;
        long identityResolved;
        long latencyMicrosTotal;
        long latencyMicrosMax;
        long firstAt;
        long lastAt;

        void add(DiagnosticsQuery.AggregateRow row) {
            observed = saturatingAdd(observed, row.eventCount);
            blocked = saturatingAdd(blocked, row.blockedCount);
            failOpen = saturatingAdd(failOpen, row.failOpenCount);
            rawHits = saturatingAdd(rawHits, row.rawHitCount);
            identityResolved = saturatingAdd(identityResolved, row.identityResolvedCount);
            latencyMicrosTotal = saturatingAdd(latencyMicrosTotal, row.latencyMicrosTotal);
            latencyMicrosMax = Math.max(latencyMicrosMax, row.latencyMicrosMax);
            if (countsAsPassed(row.result)) {
                passed = saturatingAdd(passed, row.eventCount);
            }
            if (DiagnosticsResult.THROTTLE_ALLOWED.equals(row.result)
                    || DiagnosticsResult.THROTTLE_BLOCKED.equals(row.result)) {
                throttled = saturatingAdd(throttled, row.eventCount);
            }
            if (row.firstAt > 0L && (firstAt == 0L || row.firstAt < firstAt)) {
                firstAt = row.firstAt;
            }
            lastAt = Math.max(lastAt, row.lastAt);
        }

        void add(RecentBehaviorStats.Row row) {
            observed = saturatingAdd(observed, row.eventCount);
            blocked = saturatingAdd(blocked, row.blockedCount);
            failOpen = saturatingAdd(failOpen, row.failOpenCount);
            rawHits = saturatingAdd(rawHits, row.rawHitCount);
            identityResolved = saturatingAdd(identityResolved, row.identityResolvedCount);
            latencyMicrosTotal = saturatingAdd(latencyMicrosTotal, row.latencyMicrosTotal);
            latencyMicrosMax = Math.max(latencyMicrosMax, row.latencyMicrosMax);
            if (countsAsPassed(row.result)) {
                passed = saturatingAdd(passed, row.eventCount);
            }
            if (DiagnosticsResult.THROTTLE_ALLOWED.equals(row.result)
                    || DiagnosticsResult.THROTTLE_BLOCKED.equals(row.result)) {
                throttled = saturatingAdd(throttled, row.eventCount);
            }
            if (row.firstAt > 0L && (firstAt == 0L || row.firstAt < firstAt)) {
                firstAt = row.firstAt;
            }
            lastAt = Math.max(lastAt, row.lastAt);
        }

        Metrics build() {
            return new Metrics(this);
        }
    }

    private static boolean countsAsPassed(String result) {
        return DiagnosticsResult.PASSED.equals(result)
                || DiagnosticsResult.CANDIDATE_FILTERED.equals(result)
                || DiagnosticsResult.RECOVERED.equals(result)
                || DiagnosticsResult.NO_ACTION_NEEDED.equals(result);
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
