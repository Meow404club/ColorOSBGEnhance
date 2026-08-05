package com.frostwork.bgenhance.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Query, pagination, aggregation, clear and export DTOs for diagnostics consumers. */
public final class DiagnosticsQuery {
    private DiagnosticsQuery() {}

    public static final class EventFilter {
        public final long fromInclusive;
        public final long toExclusive;
        public final String packageName;
        public final Integer uid;
        public final String featureId;
        public final String hookTargetId;
        public final String behavior;
        public final String source;
        public final String decision;
        public final String result;
        public final boolean failOpenOnly;
        public final int limit;
        public final EventCursor before;

        private EventFilter(Builder builder) {
            fromInclusive = Math.max(0L, builder.fromInclusive);
            toExclusive = builder.toExclusive <= 0L
                    ? Long.MAX_VALUE : Math.max(fromInclusive + 1L, builder.toExclusive);
            packageName = DiagnosticsValues.packageName(builder.packageName);
            uid = builder.uid == null ? null : Math.max(-1, builder.uid);
            featureId = DiagnosticsValues.id(builder.featureId);
            hookTargetId = DiagnosticsValues.id(builder.hookTargetId);
            behavior = DiagnosticsValues.id(builder.behavior);
            source = DiagnosticsValues.id(builder.source);
            decision = DiagnosticsValues.id(builder.decision);
            result = DiagnosticsValues.id(builder.result);
            failOpenOnly = builder.failOpenOnly;
            limit = Math.max(1, Math.min(200, builder.limit));
            before = builder.before;
        }

        public static Builder builder() { return new Builder(); }

        public Builder nextPage(EventCursor cursor) {
            return new Builder()
                    .fromInclusive(fromInclusive)
                    .toExclusive(toExclusive)
                    .packageName(packageName)
                    .uid(uid)
                    .featureId(featureId)
                    .hookTargetId(hookTargetId)
                    .behavior(behavior)
                    .source(source)
                    .decision(decision)
                    .result(result)
                    .failOpenOnly(failOpenOnly)
                    .limit(limit)
                    .before(cursor);
        }

        public static final class Builder {
            private long fromInclusive;
            private long toExclusive = Long.MAX_VALUE;
            private String packageName = "";
            private Integer uid;
            private String featureId = "";
            private String hookTargetId = "";
            private String behavior = "";
            private String source = "";
            private String decision = "";
            private String result = "";
            private boolean failOpenOnly;
            private int limit = 50;
            private EventCursor before;

            public Builder fromInclusive(long value) { fromInclusive = value; return this; }
            public Builder toExclusive(long value) { toExclusive = value; return this; }
            public Builder packageName(String value) { packageName = value; return this; }
            public Builder uid(Integer value) { uid = value; return this; }
            public Builder featureId(String value) { featureId = value; return this; }
            public Builder hookTargetId(String value) { hookTargetId = value; return this; }
            public Builder behavior(String value) { behavior = value; return this; }
            public Builder source(String value) { source = value; return this; }
            public Builder decision(String value) { decision = value; return this; }
            public Builder result(String value) { result = value; return this; }
            public Builder failOpenOnly(boolean value) { failOpenOnly = value; return this; }
            public Builder limit(int value) { limit = value; return this; }
            public Builder before(EventCursor value) { before = value; return this; }
            public EventFilter build() { return new EventFilter(this); }
        }
    }

    public static final class EventCursor {
        public final long timestampMillis;
        public final long id;

        public EventCursor(long timestampMillis, long id) {
            this.timestampMillis = Math.max(0L, timestampMillis);
            this.id = Math.max(0L, id);
        }
    }

    public static final class EventPage {
        public final List<NormalizedDiagnosticsEvent> items;
        public final EventCursor nextCursor;
        public final String errorCode;

        public EventPage(List<NormalizedDiagnosticsEvent> items,
                         EventCursor nextCursor, String errorCode) {
            this.items = immutable(items);
            this.nextCursor = nextCursor;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static EventPage empty(String errorCode) {
            return new EventPage(Collections.emptyList(), null, errorCode);
        }
    }

    public enum AggregateDimension {
        APPLICATION,
        FEATURE,
        BEHAVIOR,
        HOOK_TARGET,
        SOURCE,
        DECISION,
        RESULT
    }

    public static final class AggregateQuery {
        public final long fromInclusive;
        public final long toExclusive;
        public final Set<AggregateDimension> dimensions;
        public final String packageName;
        public final String featureId;
        public final String hookTargetId;
        public final String behavior;
        public final String source;
        public final String decision;
        public final String result;
        public final int limit;
        public final int offset;

        private AggregateQuery(Builder builder) {
            fromInclusive = Math.max(0L, builder.fromInclusive);
            toExclusive = builder.toExclusive <= 0L
                    ? Long.MAX_VALUE : Math.max(fromInclusive + 1L, builder.toExclusive);
            EnumSet<AggregateDimension> selected = builder.dimensions.isEmpty()
                    ? EnumSet.of(AggregateDimension.BEHAVIOR)
                    : EnumSet.copyOf(builder.dimensions);
            dimensions = Collections.unmodifiableSet(selected);
            packageName = DiagnosticsValues.packageName(builder.packageName);
            featureId = DiagnosticsValues.id(builder.featureId);
            hookTargetId = DiagnosticsValues.id(builder.hookTargetId);
            behavior = DiagnosticsValues.id(builder.behavior);
            source = DiagnosticsValues.id(builder.source);
            decision = DiagnosticsValues.id(builder.decision);
            result = DiagnosticsValues.id(builder.result);
            limit = Math.max(1, Math.min(
                    DiagnosticsRetentionPolicy.DEFAULT_AGGREGATE_ROW_LIMIT,
                    builder.limit));
            offset = Math.max(0, builder.offset);
        }

        public static Builder builder() { return new Builder(); }

        public Builder nextPage(int nextOffset) {
            return new Builder()
                    .fromInclusive(fromInclusive)
                    .toExclusive(toExclusive)
                    .dimensions(dimensions)
                    .packageName(packageName)
                    .featureId(featureId)
                    .hookTargetId(hookTargetId)
                    .behavior(behavior)
                    .source(source)
                    .decision(decision)
                    .result(result)
                    .limit(limit)
                    .offset(nextOffset);
        }

        public static final class Builder {
            private long fromInclusive;
            private long toExclusive = Long.MAX_VALUE;
            private final EnumSet<AggregateDimension> dimensions =
                    EnumSet.noneOf(AggregateDimension.class);
            private String packageName = "";
            private String featureId = "";
            private String hookTargetId = "";
            private String behavior = "";
            private String source = "";
            private String decision = "";
            private String result = "";
            private int limit = 50;
            private int offset;

            public Builder fromInclusive(long value) { fromInclusive = value; return this; }
            public Builder toExclusive(long value) { toExclusive = value; return this; }
            public Builder dimension(AggregateDimension value) {
                if (value != null) dimensions.add(value);
                return this;
            }
            public Builder dimensions(Iterable<AggregateDimension> values) {
                if (values != null) {
                    for (AggregateDimension value : values) dimension(value);
                }
                return this;
            }
            public Builder packageName(String value) { packageName = value; return this; }
            public Builder featureId(String value) { featureId = value; return this; }
            public Builder hookTargetId(String value) { hookTargetId = value; return this; }
            public Builder behavior(String value) { behavior = value; return this; }
            public Builder source(String value) { source = value; return this; }
            public Builder decision(String value) { decision = value; return this; }
            public Builder result(String value) { result = value; return this; }
            public Builder limit(int value) { limit = value; return this; }
            public Builder offset(int value) { offset = value; return this; }
            public AggregateQuery build() { return new AggregateQuery(this); }
        }
    }

    public static final class AggregateRow {
        public final String packageName;
        public final int uid;
        public final String featureId;
        public final String hookTargetId;
        public final String behavior;
        public final String source;
        public final String decision;
        public final String result;
        public final long rawHitCount;
        public final long eventCount;
        public final long blockedCount;
        public final long failOpenCount;
        public final long identityResolvedCount;
        public final long latencyMicrosTotal;
        public final long latencyMicrosMax;
        public final long firstAt;
        public final long lastAt;

        public AggregateRow(
                String packageName, int uid, String featureId, String hookTargetId,
                String behavior, String source, String decision, String result,
                long rawHitCount, long eventCount, long blockedCount,
                long failOpenCount, long identityResolvedCount,
                long latencyMicrosTotal, long latencyMicrosMax,
                long firstAt, long lastAt) {
            this.packageName = DiagnosticsValues.packageName(packageName);
            this.uid = Math.max(-1, uid);
            this.featureId = DiagnosticsValues.id(featureId);
            this.hookTargetId = DiagnosticsValues.id(hookTargetId);
            this.behavior = DiagnosticsValues.id(behavior);
            this.source = DiagnosticsValues.id(source);
            this.decision = DiagnosticsValues.id(decision);
            this.result = DiagnosticsValues.id(result);
            this.rawHitCount = DiagnosticsValues.nonNegative(rawHitCount);
            this.eventCount = DiagnosticsValues.nonNegative(eventCount);
            this.blockedCount = DiagnosticsValues.nonNegative(blockedCount);
            this.failOpenCount = DiagnosticsValues.nonNegative(failOpenCount);
            this.identityResolvedCount = DiagnosticsValues.nonNegative(identityResolvedCount);
            this.latencyMicrosTotal = DiagnosticsValues.nonNegative(latencyMicrosTotal);
            this.latencyMicrosMax = DiagnosticsValues.nonNegative(latencyMicrosMax);
            this.firstAt = DiagnosticsValues.nonNegative(firstAt);
            this.lastAt = DiagnosticsValues.nonNegative(lastAt);
        }
    }

    public static final class AggregatePage {
        public final List<AggregateRow> items;
        public final int nextOffset;
        public final boolean hasMore;
        public final boolean historyTruncated;
        public final String errorCode;

        public AggregatePage(List<AggregateRow> items, int nextOffset,
                             boolean hasMore, String errorCode) {
            this(items, nextOffset, hasMore, false, errorCode);
        }

        public AggregatePage(
                List<AggregateRow> items,
                int nextOffset,
                boolean hasMore,
                boolean historyTruncated,
                String errorCode) {
            this.items = immutable(items);
            this.nextOffset = Math.max(0, nextOffset);
            this.hasMore = hasMore;
            this.historyTruncated = historyTruncated;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static AggregatePage empty(String errorCode) {
            return new AggregatePage(Collections.emptyList(), 0, false, errorCode);
        }
    }

    public static final class RawHookSummary {
        public final String processId;
        public final String featureId;
        public final String hookTargetId;
        public final long hitCount;
        public final long parsedCount;
        public final long identityResolvedCount;
        public final long lastHitAt;
        public final String lastError;

        public RawHookSummary(String processId, String featureId, String hookTargetId,
                              long hitCount, long parsedCount,
                              long identityResolvedCount, long lastHitAt,
                              String lastError) {
            this.processId = DiagnosticsValues.id(processId);
            this.featureId = DiagnosticsValues.id(featureId);
            this.hookTargetId = DiagnosticsValues.id(hookTargetId);
            this.hitCount = DiagnosticsValues.nonNegative(hitCount);
            this.parsedCount = DiagnosticsValues.nonNegative(parsedCount);
            this.identityResolvedCount = DiagnosticsValues.nonNegative(identityResolvedCount);
            this.lastHitAt = DiagnosticsValues.nonNegative(lastHitAt);
            this.lastError = DiagnosticsValues.error(lastError);
        }
    }

    public static final class HookStatePage {
        public final List<DiagnosticsHookState> items;
        public final String errorCode;

        public HookStatePage(List<DiagnosticsHookState> items, String errorCode) {
            this.items = immutable(items);
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static HookStatePage empty(String errorCode) {
            return new HookStatePage(Collections.emptyList(), errorCode);
        }
    }

    /** Behavior counters and Hook/configuration states read by one backend command. */
    public static final class HomeDiagnosticsSnapshot {
        public final AggregatePage behaviorStats;
        public final HookStatePage hookStates;

        public HomeDiagnosticsSnapshot(
                AggregatePage behaviorStats, HookStatePage hookStates) {
            this.behaviorStats = behaviorStats == null
                    ? AggregatePage.empty("database_unavailable") : behaviorStats;
            this.hookStates = hookStates == null
                    ? HookStatePage.empty("database_unavailable") : hookStates;
        }

        public static HomeDiagnosticsSnapshot empty(String errorCode) {
            return new HomeDiagnosticsSnapshot(
                    AggregatePage.empty(errorCode), HookStatePage.empty(errorCode));
        }
    }

    public static final class FeatureStats {
        public final String featureId;
        public final long observed;
        public final long blocked;
        public final long failOpen;
        public final long lastAt;
        public final String lastPackage;
        public final String lastProcess;
        public final String lastScene;
        public final String lastReason;
        public final String lastResult;

        public FeatureStats(String featureId, long observed, long blocked, long failOpen,
                            long lastAt, String lastPackage, String lastProcess,
                            String lastScene, String lastReason, String lastResult) {
            this.featureId = DiagnosticsValues.id(featureId);
            this.observed = DiagnosticsValues.nonNegative(observed);
            this.blocked = DiagnosticsValues.nonNegative(blocked);
            this.failOpen = DiagnosticsValues.nonNegative(failOpen);
            this.lastAt = DiagnosticsValues.nonNegative(lastAt);
            this.lastPackage = DiagnosticsValues.packageName(lastPackage);
            this.lastProcess = DiagnosticsValues.id(lastProcess);
            this.lastScene = DiagnosticsValues.text(lastScene);
            this.lastReason = DiagnosticsValues.text(lastReason);
            this.lastResult = DiagnosticsValues.id(lastResult);
        }

        public static FeatureStats empty(String featureId) {
            return new FeatureStats(featureId, 0L, 0L, 0L,
                    0L, "", "", "", "", "");
        }
    }

    public static final class HomeStatsSnapshot {
        public final Map<String, FeatureStats> features;
        public final long observed;
        public final long blocked;
        public final long failOpen;
        public final String errorCode;

        public HomeStatsSnapshot(Map<String, FeatureStats> features, String errorCode) {
            LinkedHashMap<String, FeatureStats> copy = new LinkedHashMap<>();
            long observedTotal = 0L;
            long blockedTotal = 0L;
            long failOpenTotal = 0L;
            if (features != null) {
                for (Map.Entry<String, FeatureStats> entry : features.entrySet()) {
                    String key = DiagnosticsValues.id(entry.getKey());
                    FeatureStats value = entry.getValue();
                    if (key.isEmpty() || value == null) continue;
                    copy.put(key, value);
                    observedTotal = saturatingAdd(observedTotal, value.observed);
                    blockedTotal = saturatingAdd(blockedTotal, value.blocked);
                    failOpenTotal = saturatingAdd(failOpenTotal, value.failOpen);
                }
            }
            this.features = Collections.unmodifiableMap(copy);
            observed = observedTotal;
            blocked = blockedTotal;
            failOpen = failOpenTotal;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static HomeStatsSnapshot empty(String errorCode) {
            return new HomeStatsSnapshot(Collections.emptyMap(), errorCode);
        }

        private static long saturatingAdd(long first, long second) {
            return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
        }
    }

    public enum ClearScope {
        RECENT_EVENTS,
        STATISTICS,
        HOOK_STATES,
        USER_ACTIVITY,
        ALL
    }

    public static final class ClearResult {
        public final boolean success;
        public final int deletedRows;
        public final long epoch;
        public final long epochCutoffElapsedNanos;
        public final String errorCode;

        public ClearResult(boolean success, int deletedRows, String errorCode) {
            this(success, deletedRows, -1L, -1L, errorCode);
        }

        public ClearResult(boolean success, int deletedRows, long epoch, String errorCode) {
            this(success, deletedRows, epoch, -1L, errorCode);
        }

        public ClearResult(
                boolean success,
                int deletedRows,
                long epoch,
                long epochCutoffElapsedNanos,
                String errorCode) {
            this.success = success;
            this.deletedRows = Math.max(0, deletedRows);
            this.epoch = Math.max(-1L, epoch);
            this.epochCutoffElapsedNanos = Math.max(-1L, epochCutoffElapsedNanos);
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static ClearResult failed(String errorCode) {
            return new ClearResult(false, 0, errorCode);
        }
    }

    public static final class ExportOptions {
        public final boolean includePackageNames;
        public final boolean includeUids;
        public final boolean includeSensitiveDetails;
        public final int recentEventLimit;
        public final int aggregateLimit;
        public final int rawHookLimit;
        public final int hookStateLimit;

        private ExportOptions(Builder builder) {
            includePackageNames = builder.includePackageNames;
            includeUids = builder.includeUids;
            includeSensitiveDetails = builder.includeSensitiveDetails;
            recentEventLimit = bounded(
                    builder.recentEventLimit, 1,
                    DiagnosticsRetentionPolicy.DEFAULT_RECENT_EVENT_LIMIT);
            aggregateLimit = bounded(
                    builder.aggregateLimit, 1,
                    DiagnosticsRetentionPolicy.DEFAULT_AGGREGATE_ROW_LIMIT);
            rawHookLimit = bounded(
                    builder.rawHookLimit, 1,
                    DiagnosticsRetentionPolicy.DEFAULT_RAW_AGGREGATE_ROW_LIMIT);
            hookStateLimit = bounded(
                    builder.hookStateLimit, 1,
                    DiagnosticsRetentionPolicy.DEFAULT_HOOK_STATE_LIMIT);
        }

        public static ExportOptions redactedDefaults() { return builder().build(); }
        public static ExportOptions fullReport() {
            return builder()
                    .includePackageNames(true)
                    .includeUids(true)
                    .includeSensitiveDetails(true)
                    .recentEventLimit(DiagnosticsRetentionPolicy.DEFAULT_RECENT_EVENT_LIMIT)
                    .aggregateLimit(DiagnosticsRetentionPolicy.DEFAULT_AGGREGATE_ROW_LIMIT)
                    .rawHookLimit(DiagnosticsRetentionPolicy.DEFAULT_RAW_AGGREGATE_ROW_LIMIT)
                    .hookStateLimit(DiagnosticsRetentionPolicy.DEFAULT_HOOK_STATE_LIMIT)
                    .build();
        }
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private boolean includePackageNames;
            private boolean includeUids;
            private boolean includeSensitiveDetails;
            private int recentEventLimit = 500;
            private int aggregateLimit = 1_000;
            private int rawHookLimit = 256;
            private int hookStateLimit = 256;

            public Builder includePackageNames(boolean value) {
                includePackageNames = value;
                return this;
            }
            public Builder includeUids(boolean value) { includeUids = value; return this; }
            public Builder includeSensitiveDetails(boolean value) {
                includeSensitiveDetails = value;
                return this;
            }
            public Builder recentEventLimit(int value) { recentEventLimit = value; return this; }
            public Builder aggregateLimit(int value) { aggregateLimit = value; return this; }
            public Builder rawHookLimit(int value) { rawHookLimit = value; return this; }
            public Builder hookStateLimit(int value) { hookStateLimit = value; return this; }
            public ExportOptions build() { return new ExportOptions(this); }
        }
    }

    public static final class ExportCollectionStatus {
        public final int requestedLimit;
        public final int returnedCount;
        public final int pagesRead;
        public final boolean hasMore;
        public final boolean historyTruncated;
        public final boolean truncated;
        public final String errorCode;

        public ExportCollectionStatus(
                int requestedLimit,
                int returnedCount,
                int pagesRead,
                boolean hasMore,
                String errorCode) {
            this(requestedLimit, returnedCount, pagesRead, hasMore, false, errorCode);
        }

        public ExportCollectionStatus(
                int requestedLimit,
                int returnedCount,
                int pagesRead,
                boolean hasMore,
                boolean historyTruncated,
                String errorCode) {
            this.requestedLimit = Math.max(0, requestedLimit);
            this.returnedCount = Math.max(0, returnedCount);
            this.pagesRead = Math.max(0, pagesRead);
            this.hasMore = hasMore;
            this.historyTruncated = historyTruncated;
            truncated = hasMore || historyTruncated;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static ExportCollectionStatus empty(int requestedLimit, String errorCode) {
            return new ExportCollectionStatus(requestedLimit, 0, 0, false, errorCode);
        }
    }

    public static final class ExportBootStatus {
        public final boolean identityAvailable;
        public final boolean identityIncluded;
        public final String currentBootId;
        public final int currentBootHookStateCount;
        public final int otherBootHookStateCount;
        public final int expectedCurrentBootStateCount;
        public final boolean currentBootStateAvailable;
        public final boolean systemServerReported;
        public final boolean athenaReported;
        public final boolean hookStateCoverageComplete;

        public ExportBootStatus(
                boolean identityAvailable,
                boolean identityIncluded,
                String currentBootId,
                int currentBootHookStateCount,
                int otherBootHookStateCount,
                int expectedCurrentBootStateCount,
                boolean systemServerReported,
                boolean athenaReported,
                boolean hookStateCoverageComplete) {
            this.identityAvailable = identityAvailable;
            this.identityIncluded = identityIncluded && identityAvailable;
            this.currentBootId = this.identityIncluded
                    ? DiagnosticsValues.id(currentBootId) : "";
            this.currentBootHookStateCount = Math.max(0, currentBootHookStateCount);
            this.otherBootHookStateCount = Math.max(0, otherBootHookStateCount);
            this.expectedCurrentBootStateCount = Math.max(0, expectedCurrentBootStateCount);
            currentBootStateAvailable = identityAvailable && currentBootHookStateCount > 0;
            this.systemServerReported = systemServerReported;
            this.athenaReported = athenaReported;
            this.hookStateCoverageComplete = hookStateCoverageComplete;
        }

        public ExportBootStatus redacted() {
            return new ExportBootStatus(
                    identityAvailable, false, "", currentBootHookStateCount,
                    otherBootHookStateCount, expectedCurrentBootStateCount,
                    systemServerReported, athenaReported,
                    hookStateCoverageComplete);
        }

        public static ExportBootStatus unavailable() {
            return new ExportBootStatus(
                    false, false, "", 0, 0, 0, false, false, false);
        }
    }

    public static final class ExportStatus {
        public final ExportCollectionStatus recentEvents;
        public final ExportCollectionStatus aggregates;
        public final ExportCollectionStatus rawHooks;
        public final ExportCollectionStatus hookStates;
        public final int featureStatsCount;
        public final ExportBootStatus currentBoot;
        public final BackendHealth backendHealth;
        public final boolean truncated;
        public final boolean hasErrors;

        public ExportStatus(
                ExportCollectionStatus recentEvents,
                ExportCollectionStatus aggregates,
                ExportCollectionStatus rawHooks,
                ExportCollectionStatus hookStates,
                int featureStatsCount,
                ExportBootStatus currentBoot,
                BackendHealth backendHealth) {
            this.recentEvents = nonNull(recentEvents);
            this.aggregates = nonNull(aggregates);
            this.rawHooks = nonNull(rawHooks);
            this.hookStates = nonNull(hookStates);
            this.featureStatsCount = Math.max(0, featureStatsCount);
            this.currentBoot = currentBoot == null
                    ? ExportBootStatus.unavailable() : currentBoot;
            this.backendHealth = backendHealth == null
                    ? new BackendHealth(0L, 0L, 0L, 0L, 0, false)
                    : backendHealth;
            truncated = this.recentEvents.truncated || this.aggregates.truncated
                    || this.rawHooks.truncated || this.hookStates.truncated;
            hasErrors = !this.recentEvents.errorCode.isEmpty()
                    || !this.aggregates.errorCode.isEmpty()
                    || !this.rawHooks.errorCode.isEmpty()
                    || !this.hookStates.errorCode.isEmpty();
        }

        public ExportStatus redacted() {
            return new ExportStatus(
                    recentEvents, aggregates, rawHooks, hookStates, featureStatsCount,
                    currentBoot.redacted(), backendHealth);
        }

        public static ExportStatus empty(String errorCode) {
            ExportCollectionStatus unavailable =
                    ExportCollectionStatus.empty(0, errorCode);
            return new ExportStatus(
                    unavailable, unavailable, unavailable, unavailable,
                    0, ExportBootStatus.unavailable(), null);
        }

        private static ExportCollectionStatus nonNull(ExportCollectionStatus value) {
            return value == null ? ExportCollectionStatus.empty(0, "") : value;
        }
    }

    public static final class ExportSnapshot {
        public final int formatVersion;
        public final int databaseSchemaVersion;
        public final long generatedAt;
        public final long retentionMillis;
        public final boolean packageNamesIncluded;
        public final boolean uidsIncluded;
        public final boolean sensitiveDetailsIncluded;
        public final List<NormalizedDiagnosticsEvent> recentEvents;
        public final List<AggregateRow> aggregates;
        public final List<RawHookSummary> rawHooks;
        public final List<DiagnosticsHookState> hookStates;
        public final HomeStatsSnapshot homeStats;
        public final ExportStatus exportStatus;
        public final String errorCode;

        public ExportSnapshot(
                int formatVersion, int databaseSchemaVersion, long generatedAt,
                long retentionMillis, boolean packageNamesIncluded, boolean uidsIncluded,
                boolean sensitiveDetailsIncluded,
                List<NormalizedDiagnosticsEvent> recentEvents,
                List<AggregateRow> aggregates, List<RawHookSummary> rawHooks,
                List<DiagnosticsHookState> hookStates, HomeStatsSnapshot homeStats,
                String errorCode) {
            this(formatVersion, databaseSchemaVersion, generatedAt, retentionMillis,
                    packageNamesIncluded, uidsIncluded, sensitiveDetailsIncluded,
                    recentEvents, aggregates, rawHooks, hookStates, homeStats,
                    null, errorCode);
        }

        public ExportSnapshot(
                int formatVersion, int databaseSchemaVersion, long generatedAt,
                long retentionMillis, boolean packageNamesIncluded, boolean uidsIncluded,
                boolean sensitiveDetailsIncluded,
                List<NormalizedDiagnosticsEvent> recentEvents,
                List<AggregateRow> aggregates, List<RawHookSummary> rawHooks,
                List<DiagnosticsHookState> hookStates, HomeStatsSnapshot homeStats,
                ExportStatus exportStatus, String errorCode) {
            this.formatVersion = Math.max(1, formatVersion);
            this.databaseSchemaVersion = Math.max(1, databaseSchemaVersion);
            this.generatedAt = DiagnosticsValues.nonNegative(generatedAt);
            this.retentionMillis = DiagnosticsValues.nonNegative(retentionMillis);
            this.packageNamesIncluded = packageNamesIncluded;
            this.uidsIncluded = uidsIncluded;
            this.sensitiveDetailsIncluded = sensitiveDetailsIncluded;
            this.recentEvents = immutable(recentEvents);
            this.aggregates = immutable(aggregates);
            this.rawHooks = immutable(rawHooks);
            this.hookStates = immutable(hookStates);
            this.homeStats = homeStats == null
                    ? HomeStatsSnapshot.empty("") : homeStats;
            this.exportStatus = exportStatus == null
                    ? new ExportStatus(
                            new ExportCollectionStatus(
                                    this.recentEvents.size(), this.recentEvents.size(),
                                    this.recentEvents.isEmpty() ? 0 : 1, false, ""),
                            new ExportCollectionStatus(
                                    this.aggregates.size(), this.aggregates.size(),
                                    this.aggregates.isEmpty() ? 0 : 1, false, ""),
                            new ExportCollectionStatus(
                                    this.rawHooks.size(), this.rawHooks.size(),
                                    this.rawHooks.isEmpty() ? 0 : 1, false, ""),
                            new ExportCollectionStatus(
                                    this.hookStates.size(), this.hookStates.size(),
                                    this.hookStates.isEmpty() ? 0 : 1, false, ""),
                            this.homeStats.features.size(),
                            ExportBootStatus.unavailable(), null)
                    : exportStatus;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        public static ExportSnapshot empty(String errorCode) {
            return new ExportSnapshot(2, 1, System.currentTimeMillis(), 0L,
                    false, false, false, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    HomeStatsSnapshot.empty(errorCode), ExportStatus.empty(errorCode), errorCode);
        }
    }

    public static final class BackendHealth {
        public final long acceptedWrites;
        public final long droppedWrites;
        public final long committedWrites;
        public final long failedBatches;
        public final int queuedWrites;
        public final boolean closed;

        public BackendHealth(long acceptedWrites, long droppedWrites,
                             long committedWrites, long failedBatches,
                             int queuedWrites, boolean closed) {
            this.acceptedWrites = DiagnosticsValues.nonNegative(acceptedWrites);
            this.droppedWrites = DiagnosticsValues.nonNegative(droppedWrites);
            this.committedWrites = DiagnosticsValues.nonNegative(committedWrites);
            this.failedBatches = DiagnosticsValues.nonNegative(failedBatches);
            this.queuedWrites = Math.max(0, queuedWrites);
            this.closed = closed;
        }
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
