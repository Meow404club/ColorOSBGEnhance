package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStats;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBehaviorClassifier;
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
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Converts storage DTOs into immutable, deterministic rows for UI adapters. */
public final class DiagnosticsUiMapper {
    private final IntSupplier localConfigRevisionSupplier;
    private final LongSupplier localPolicyRevisionSupplier;

    public DiagnosticsUiMapper() {
        this(() -> -1, () -> -1L);
    }

    DiagnosticsUiMapper(IntSupplier localConfigRevisionSupplier) {
        this(localConfigRevisionSupplier, () -> -1L);
    }

    DiagnosticsUiMapper(
            IntSupplier localConfigRevisionSupplier,
            LongSupplier localPolicyRevisionSupplier) {
        this.localConfigRevisionSupplier = Objects.requireNonNull(
                localConfigRevisionSupplier, "localConfigRevisionSupplier");
        this.localPolicyRevisionSupplier = Objects.requireNonNull(
                localPolicyRevisionSupplier, "localPolicyRevisionSupplier");
    }

    public interface UiRow {
        String stableId();
    }

    public enum HookStatus {
        READY,
        INSTALLED,
        ENTRY_LOADED,
        BOOTSTRAP_FAILED,
        VERSION_MISMATCH,
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
        public final long observedOnly;
        public final long recovered;
        public final long noActionNeeded;
        public final long safetyAborted;
        public final long applied;
        public final long throttleAllowed;
        public final long throttleBlocked;
        public final long unknownResult;
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
            observedOnly = source.observedOnly;
            recovered = source.recovered;
            noActionNeeded = source.noActionNeeded;
            safetyAborted = source.safetyAborted;
            applied = source.applied;
            throttleAllowed = source.throttleAllowed;
            throttleBlocked = source.throttleBlocked;
            unknownResult = source.unknownResult;
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
        public final int userId;
        public final String displayName;
        public final boolean unidentified;
        public final boolean manageable;
        public final int identityUid;
        public final DiagnosticsDataSource.UidKind uidKind;
        public final List<DiagnosticsDataSource.ResolvedApplication> identityApplications;
        public final List<Integer> observedUids;
        public final Map<DiagnosticsBehavior, Metrics> behaviors;
        public final Metrics totals;

        private ApplicationRow(
                String stableIdentity,
                String packageName,
                int userId,
                String displayName,
                boolean manageable,
                int identityUid,
                DiagnosticsDataSource.UidKind uidKind,
                List<DiagnosticsDataSource.ResolvedApplication> identityApplications,
                List<Integer> observedUids,
                Map<DiagnosticsBehavior, Metrics> behaviors,
                Metrics totals) {
            this.unidentified = packageName == null || packageName.isEmpty();
            this.stableId = "application:" + stableIdentity;
            this.packageName = packageName == null ? "" : packageName;
            this.userId = userId;
            this.displayName = displayName == null || displayName.isEmpty()
                    ? unidentified ? "" : packageName : displayName;
            this.manageable = !unidentified && userId >= 0 && manageable;
            this.identityUid = identityUid;
            this.uidKind = Objects.requireNonNull(uidKind, "uidKind");
            this.identityApplications = identityApplications == null
                    || identityApplications.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(identityApplications));
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

    public static final class BehaviorDetailRow implements UiRow {
        public final String stableId;
        public final DiagnosticsBehavior behavior;
        public final int titleRes;
        public final int titleFormatRes;
        public final boolean fallback;
        public final Metrics metrics;

        private BehaviorDetailRow(
                String stableId,
                DiagnosticsBehavior behavior,
                int titleRes,
                int titleFormatRes,
                boolean fallback,
                Metrics metrics) {
            this.stableId = "behavior-detail:" + behavior.wireId + ":" + stableId;
            this.behavior = behavior;
            this.titleRes = titleRes;
            this.titleFormatRes = titleFormatRes;
            this.fallback = fallback;
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
                DiagnosticsHookState state,
                int localConfigRevision,
                long localPolicyRevision) {
            stableId = entry.stableId();
            hookTargetId = entry.hookTargetId;
            expectedProcessId = entry.expectedProcessId;
            titleKey = entry.titleKey;
            fallbackTitle = entry.fallbackTitle;
            kind = entry.kind;
            reported = state != null;
            status = state == null
                    ? HookStatus.MISSING
                    : hookStatus(state, localConfigRevision, localPolicyRevision);
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
        return mapApplicationsInternal(
                null, DiagnosticsDataSource.UidResolutionPage.unsupported(), rows);
    }

    public List<ApplicationRow> mapApplications(
            DiagnosticsDataSource.UidResolutionPage uidResolutions,
            List<DiagnosticsQuery.AggregateRow> rows) {
        return mapApplicationsInternal(null, uidResolutions, rows);
    }

    public List<ApplicationRow> mapApplications(
            List<DiagnosticsDataSource.ManagedApplication> managedApplications,
            List<DiagnosticsQuery.AggregateRow> rows) {
        return mapApplications(
                managedApplications,
                DiagnosticsDataSource.UidResolutionPage.unsupported(),
                rows);
    }

    public List<ApplicationRow> mapApplications(
            List<DiagnosticsDataSource.ManagedApplication> managedApplications,
            DiagnosticsDataSource.UidResolutionPage uidResolutions,
            List<DiagnosticsQuery.AggregateRow> rows) {
        Objects.requireNonNull(managedApplications, "managedApplications");
        return mapApplicationsInternal(managedApplications, uidResolutions, rows);
    }

    private List<ApplicationRow> mapApplicationsInternal(
            List<DiagnosticsDataSource.ManagedApplication> managedApplications,
            DiagnosticsDataSource.UidResolutionPage uidResolutionPage,
            List<DiagnosticsQuery.AggregateRow> rows) {
        DiagnosticsDataSource.UidResolutionPage resolutions = uidResolutionPage == null
                ? DiagnosticsDataSource.UidResolutionPage.unsupported()
                : uidResolutionPage;
        LinkedHashMap<String, ApplicationAccumulator> applications = new LinkedHashMap<>();
        if (rows != null) {
            for (DiagnosticsQuery.AggregateRow row : rows) {
                if (row == null) continue;
                String packageName = row.packageName == null ? "" : row.packageName.trim();
                boolean hasResolution = resolutions.supported
                        && resolutions.items.containsKey(row.uid);
                DiagnosticsDataSource.UidResolution resolution = resolutionFor(
                        resolutions, row.uid);
                if (packageName.isEmpty()
                        && resolution.kind
                        == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                        && isMergeableSingleApplication(resolution)) {
                    packageName = resolution.applications.get(0).packageName;
                }
                String identity = eventIdentity(
                        packageName, row.uid, resolution, hasResolution);
                // Ambiguous identities stay UID-scoped even when the event carries a label.
                String resolvedPackageName = identity.startsWith("@uid:")
                        || "@unidentified".equals(identity) ? "" : packageName;
                DiagnosticsBehavior behavior = DiagnosticsBehavior.fromWireId(row.behavior);
                ApplicationAccumulator accumulator = applications.computeIfAbsent(
                        identity, ignored -> new ApplicationAccumulator(
                                identity, resolvedPackageName, userIdForUid(row.uid),
                                row.uid, resolution));
                accumulator.considerResolution(resolution);
                accumulator.add(behavior, row);
            }
        }

        ArrayList<ApplicationRow> result = new ArrayList<>();
        if (managedApplications != null) {
            LinkedHashSet<String> addedInstances = new LinkedHashSet<>();
            for (DiagnosticsDataSource.ManagedApplication application : managedApplications) {
                if (application == null) continue;
                String identity = new AppInstanceKey(
                        application.packageName, application.userId).encode();
                if (!addedInstances.add(identity)) continue;
                ApplicationAccumulator accumulator = applications.remove(identity);
                if (accumulator == null) {
                    accumulator = new ApplicationAccumulator(
                            identity, application.packageName, application.userId, -1,
                            DiagnosticsDataSource.UidResolution.classified(
                                    -1, DiagnosticsDataSource.UidKind.UNKNOWN));
                }
                result.add(accumulator.build(
                        application.displayName, application.manageable));
            }
            appendHistoricalApplications(result, applications);
            return immutable(result);
        }

        for (ApplicationAccumulator accumulator : applications.values()) {
            result.add(accumulator.buildResolved());
        }
        result.sort((left, right) -> {
            int compared = Long.compare(right.totals.observed, left.totals.observed);
            if (compared != 0) return compared;
            compared = Long.compare(right.totals.lastAt, left.totals.lastAt);
            return compared != 0 ? compared : left.stableId.compareTo(right.stableId);
        });
        return immutable(result);
    }

    private static void appendHistoricalApplications(
            List<ApplicationRow> result,
            Map<String, ApplicationAccumulator> applications) {
        ArrayList<ApplicationRow> historical = new ArrayList<>();
        for (ApplicationAccumulator accumulator : applications.values()) {
            historical.add(accumulator.buildResolved());
        }
        historical.sort((left, right) -> {
            int compared = Long.compare(right.totals.observed, left.totals.observed);
            if (compared != 0) return compared;
            compared = Long.compare(right.totals.lastAt, left.totals.lastAt);
            if (compared != 0) return compared;
            if (left.unidentified != right.unidentified) return left.unidentified ? 1 : -1;
            return left.stableId.compareTo(right.stableId);
        });
        result.addAll(historical);
    }

    private static DiagnosticsDataSource.UidResolution resolutionFor(
            DiagnosticsDataSource.UidResolutionPage page, int uid) {
        DiagnosticsDataSource.UidResolution resolution = page.items.get(uid);
        if (resolution != null) return resolution;
        DiagnosticsDataSource.UidKind kind = DiagnosticsUidResolver.classifyUid(uid);
        if (kind == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION) {
            kind = DiagnosticsDataSource.UidKind.UNKNOWN;
        }
        return DiagnosticsDataSource.UidResolution.classified(
                uid, kind);
    }

    private static boolean isMergeableSingleApplication(
            DiagnosticsDataSource.UidResolution resolution) {
        return resolution.applications.size() == 1
                && resolution.applications.get(0).identityConfirmed
                && resolution.applications.get(0).userId == userIdForUid(resolution.uid);
    }

    private static String eventIdentity(
            String packageName,
            int uid,
            DiagnosticsDataSource.UidResolution resolution,
            boolean hasResolution) {
        if (packageName.isEmpty()) {
            return uid >= 0 ? "@uid:" + uid : "@unidentified";
        }
        if (uid < 0) return "@legacy:" + packageName;
        if (!hasResolution || resolution == null) {
            DiagnosticsDataSource.UidKind kind = DiagnosticsUidResolver.classifyUid(uid);
            return kind == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                    || kind == DiagnosticsDataSource.UidKind.UNKNOWN
                    ? AppInstanceKey.fromUid(packageName, uid).encode() : "@uid:" + uid;
        }
        if (resolution.kind == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                && isMergeableSingleApplication(resolution)
                && packageName.equals(resolution.applications.get(0).packageName)) {
            return AppInstanceKey.fromUid(packageName, uid).encode();
        }
        return "@uid:" + uid;
    }

    private static int userIdForUid(int uid) {
        return uid < 0 ? -1 : uid / AppInstanceKey.PER_USER_RANGE;
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

    public List<BehaviorDetailRow> mapBehaviorDetails(
            DiagnosticsBehavior selectedBehavior, BehaviorDetailStats snapshot) {
        Objects.requireNonNull(selectedBehavior, "selectedBehavior");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.isSuccessful()) {
            throw new IllegalArgumentException(
                    "cannot map failed behavior details: " + snapshot.errorCode);
        }

        List<DiagnosticsBehaviorDetailCatalog.Entry> entries =
                DiagnosticsBehaviorDetailCatalog.entriesFor(selectedBehavior);
        ArrayList<MutableMetrics> entryMetrics = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            entryMetrics.add(new MutableMetrics());
        }
        MutableMetrics fallbackMetrics = new MutableMetrics();

        for (DiagnosticsQuery.AggregateRow row : snapshot.rows) {
            if (row == null || DiagnosticsBehavior.fromWireId(
                    DiagnosticsBehaviorClassifier.normalizeStoredBehavior(row.behavior))
                    != selectedBehavior) {
                continue;
            }
            int matchedIndex = -1;
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).matches(row)) {
                    matchedIndex = index;
                    break;
                }
            }
            if (matchedIndex >= 0) {
                entryMetrics.get(matchedIndex).add(row);
            } else {
                fallbackMetrics.add(row);
            }
        }

        ArrayList<BehaviorDetailRow> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            DiagnosticsBehaviorDetailCatalog.Entry entry = entries.get(index);
            result.add(new BehaviorDetailRow(
                    entry.stableId,
                    selectedBehavior,
                    entry.titleRes,
                    entry.titleFormatRes,
                    false,
                    entryMetrics.get(index).build()));
        }
        Metrics fallback = fallbackMetrics.build();
        if (selectedBehavior == DiagnosticsBehavior.UNCLASSIFIED
                || !fallback.isEmpty()) {
            result.add(new BehaviorDetailRow(
                    "other",
                    selectedBehavior,
                    DiagnosticsBehaviorDetailCatalog.fallbackTitleRes(selectedBehavior),
                    0,
                    true,
                    fallback));
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
        int localConfigRevision = localConfigRevisionSupplier.getAsInt();
        long localPolicyRevision = localPolicyRevisionSupplier.getAsLong();
        for (DiagnosticsHookCatalog.Entry entry : DiagnosticsHookCatalog.all()) {
            result.add(new HookTargetRow(entry,
                    byIdentity.get(identity(entry.expectedProcessId, entry.hookTargetId)),
                    localConfigRevision,
                    localPolicyRevision));
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

    private static HookStatus hookStatus(
            DiagnosticsHookState state,
            int localConfigRevision,
            long localPolicyRevision) {
        String value = state.installStatus == null ? "" : state.installStatus;
        if (value.startsWith("CONFIG_ERROR")) return HookStatus.FAILED;
        if (value.startsWith("IDENTITY_READY")) {
            return state.configRevision >= localConfigRevision
                    && revisionAtLeast(state.policyRevision, localPolicyRevision)
                    ? HookStatus.READY : HookStatus.PENDING;
        }
        if (value.startsWith("PENDING_IDENTITY")) return HookStatus.PENDING;
        if (value.startsWith("CONFIG_READY")) {
            return state.configRevision >= localConfigRevision
                    ? HookStatus.READY : HookStatus.PENDING;
        }
        if (ModuleRuntimeStatus.isCurrentVersion(value, BuildConfig.VERSION_CODE)) {
            return HookStatus.READY;
        }
        if (ModuleRuntimeStatus.isFeaturesInstalled(value)) {
            return HookStatus.VERSION_MISMATCH;
        }
        if (value.startsWith("FEATURES_INSTALLED")) return HookStatus.PENDING;
        if (value.startsWith("ENTRY_LOADED")) return HookStatus.ENTRY_LOADED;
        if (value.startsWith("INSTALLED")) return HookStatus.INSTALLED;
        if (value.startsWith("BOOTSTRAP_FAILED")) return HookStatus.BOOTSTRAP_FAILED;
        if (value.startsWith("FAILED")) return HookStatus.FAILED;
        if (value.startsWith("UNSUPPORTED")) return HookStatus.UNSUPPORTED;
        if (value.startsWith("SKIPPED")) return HookStatus.SKIPPED;
        if (value.startsWith("PENDING")) return HookStatus.PENDING;
        return HookStatus.UNKNOWN;
    }

    private static boolean revisionAtLeast(int reported, long local) {
        return local < 0L || reported >= Math.min(local, Integer.MAX_VALUE);
    }

    public static Metrics emptyMetrics() {
        return new MutableMetrics().build();
    }

    public static Metrics sumDetailMetrics(List<BehaviorDetailRow> rows) {
        MutableMetrics total = new MutableMetrics();
        if (rows != null) {
            for (BehaviorDetailRow row : rows) {
                if (row != null) total.add(row.metrics);
            }
        }
        return total.build();
    }

    private static Metrics zeroMetrics() {
        return emptyMetrics();
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class ApplicationAccumulator {
        final String stableIdentity;
        final String packageName;
        final int userId;
        final int identityUid;
        final TreeSet<Integer> uids = new TreeSet<>();
        final EnumMap<DiagnosticsBehavior, MutableMetrics> behaviors = new EnumMap<>(
                DiagnosticsBehavior.class);
        final MutableMetrics totals = new MutableMetrics();
        DiagnosticsDataSource.UidResolution resolution;

        ApplicationAccumulator(
                String stableIdentity,
                String packageName,
                int userId,
                int identityUid,
                DiagnosticsDataSource.UidResolution resolution) {
            this.stableIdentity = stableIdentity;
            this.packageName = packageName;
            this.userId = userId;
            this.identityUid = identityUid;
            this.resolution = resolution;
            for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
                behaviors.put(behavior, new MutableMetrics());
            }
        }

        void considerResolution(DiagnosticsDataSource.UidResolution candidate) {
            if (candidate == null) return;
            if (!packageName.isEmpty()
                    && candidate.kind == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                    && (candidate.applications.size() != 1
                    || !packageName.equals(candidate.applications.get(0).packageName)
                    || userId != candidate.applications.get(0).userId)) {
                return;
            }
            if (resolution == null
                    || resolution.kind == DiagnosticsDataSource.UidKind.UNKNOWN
                    || isSaferSingleApplication(candidate, resolution)) {
                resolution = candidate;
            }
        }

        private static boolean isSaferSingleApplication(
                DiagnosticsDataSource.UidResolution candidate,
                DiagnosticsDataSource.UidResolution current) {
            if (candidate.kind != DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                    || candidate.applications.size() != 1
                    || !candidate.applications.get(0).manageable) {
                return false;
            }
            if (current.kind != DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                    || current.applications.size() != 1) {
                return true;
            }
            DiagnosticsDataSource.ResolvedApplication next = candidate.applications.get(0);
            DiagnosticsDataSource.ResolvedApplication previous = current.applications.get(0);
            return next.packageName.equals(previous.packageName)
                    && next.userId == previous.userId && !previous.manageable;
        }

        void add(DiagnosticsBehavior behavior, DiagnosticsQuery.AggregateRow row) {
            if (row.uid >= 0) uids.add(row.uid);
            behaviors.get(behavior).add(row);
            totals.add(row);
        }

        ApplicationRow buildResolved() {
            DiagnosticsDataSource.ResolvedApplication application = resolvedApplication();
            String displayName = application == null ? packageName : application.displayName;
            boolean manageable = application != null && application.manageable;
            return build(displayName, manageable);
        }

        ApplicationRow build(String displayName, boolean manageable) {
            EnumMap<DiagnosticsBehavior, Metrics> immutableMetrics = new EnumMap<>(
                    DiagnosticsBehavior.class);
            for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
                immutableMetrics.put(behavior, behaviors.get(behavior).build());
            }
            return new ApplicationRow(
                    stableIdentity,
                    packageName,
                    userId,
                    displayName,
                    manageable,
                    identityUid,
                    resolution == null
                            ? DiagnosticsDataSource.UidKind.UNKNOWN : resolution.kind,
                    resolution == null
                            ? Collections.emptyList() : resolution.applications,
                    new ArrayList<>(uids),
                    immutableMetrics, totals.build());
        }

        private DiagnosticsDataSource.ResolvedApplication resolvedApplication() {
            if (resolution == null
                    || resolution.kind != DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                    || resolution.applications.size() != 1) {
                return null;
            }
            DiagnosticsDataSource.ResolvedApplication application =
                    resolution.applications.get(0);
            return packageName.isEmpty() || (packageName.equals(application.packageName)
                    && userId == application.userId && application.identityConfirmed)
                    ? application : null;
        }
    }

    private static final class MutableMetrics {
        long observed;
        long blocked;
        long passed;
        long observedOnly;
        long recovered;
        long noActionNeeded;
        long safetyAborted;
        long applied;
        long throttleAllowed;
        long throttleBlocked;
        long unknownResult;
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
            long normalizedBlocked = DiagnosticsResult.CANDIDATE_FILTERED.equals(row.result)
                    ? Math.max(row.blockedCount, row.eventCount)
                    : row.blockedCount;
            blocked = saturatingAdd(blocked, normalizedBlocked);
            failOpen = saturatingAdd(failOpen, row.failOpenCount);
            rawHits = saturatingAdd(rawHits, row.rawHitCount);
            identityResolved = saturatingAdd(identityResolved, row.identityResolvedCount);
            latencyMicrosTotal = saturatingAdd(latencyMicrosTotal, row.latencyMicrosTotal);
            latencyMicrosMax = Math.max(latencyMicrosMax, row.latencyMicrosMax);
            addResult(row.result, row.eventCount);
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
            addResult(row.result, row.eventCount);
            if (row.firstAt > 0L && (firstAt == 0L || row.firstAt < firstAt)) {
                firstAt = row.firstAt;
            }
            lastAt = Math.max(lastAt, row.lastAt);
        }

        void add(Metrics metrics) {
            if (metrics == null) return;
            observed = saturatingAdd(observed, metrics.observed);
            blocked = saturatingAdd(blocked, metrics.blocked);
            passed = saturatingAdd(passed, metrics.passed);
            observedOnly = saturatingAdd(observedOnly, metrics.observedOnly);
            recovered = saturatingAdd(recovered, metrics.recovered);
            noActionNeeded = saturatingAdd(noActionNeeded, metrics.noActionNeeded);
            safetyAborted = saturatingAdd(safetyAborted, metrics.safetyAborted);
            applied = saturatingAdd(applied, metrics.applied);
            throttleAllowed = saturatingAdd(throttleAllowed, metrics.throttleAllowed);
            throttleBlocked = saturatingAdd(throttleBlocked, metrics.throttleBlocked);
            unknownResult = saturatingAdd(unknownResult, metrics.unknownResult);
            throttled = saturatingAdd(throttled, metrics.throttled);
            failOpen = saturatingAdd(failOpen, metrics.failOpen);
            rawHits = saturatingAdd(rawHits, metrics.rawHits);
            identityResolved = saturatingAdd(identityResolved, metrics.identityResolved);
            latencyMicrosTotal = saturatingAdd(
                    latencyMicrosTotal, metrics.latencyMicrosTotal);
            latencyMicrosMax = Math.max(latencyMicrosMax, metrics.latencyMicrosMax);
            if (metrics.firstAt > 0L && (firstAt == 0L || metrics.firstAt < firstAt)) {
                firstAt = metrics.firstAt;
            }
            lastAt = Math.max(lastAt, metrics.lastAt);
        }

        Metrics build() {
            return new Metrics(this);
        }

        private void addResult(String result, long count) {
            if (DiagnosticsResult.PASSED.equals(result)) {
                passed = saturatingAdd(passed, count);
            } else if (DiagnosticsResult.OBSERVED.equals(result)) {
                observedOnly = saturatingAdd(observedOnly, count);
            } else if (DiagnosticsResult.RECOVERED.equals(result)) {
                recovered = saturatingAdd(recovered, count);
            } else if (DiagnosticsResult.NO_ACTION_NEEDED.equals(result)) {
                noActionNeeded = saturatingAdd(noActionNeeded, count);
            } else if (DiagnosticsResult.SAFETY_ABORTED.equals(result)) {
                safetyAborted = saturatingAdd(safetyAborted, count);
            } else if (DiagnosticsResult.APPLIED.equals(result)) {
                applied = saturatingAdd(applied, count);
            } else if (DiagnosticsResult.THROTTLE_ALLOWED.equals(result)) {
                throttleAllowed = saturatingAdd(throttleAllowed, count);
                throttled = saturatingAdd(throttled, count);
            } else if (DiagnosticsResult.THROTTLE_BLOCKED.equals(result)) {
                throttleBlocked = saturatingAdd(throttleBlocked, count);
                throttled = saturatingAdd(throttled, count);
            } else if (!DiagnosticsResult.isKnown(result)) {
                unknownResult = saturatingAdd(unknownResult, count);
            }
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
