package com.frostwork.bgenhance.diagnostics;

/** Stable Hook installation and runtime-health DTO. Count fields are deltas on write. */
public final class DiagnosticsHookState {
    public final String processId;
    public final String featureId;
    public final String hookTargetId;
    public final String installStatus;
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

    private DiagnosticsHookState(Builder builder) {
        processId = DiagnosticsValues.id(builder.processId);
        featureId = DiagnosticsValues.id(builder.featureId);
        hookTargetId = DiagnosticsValues.id(builder.hookTargetId);
        installStatus = DiagnosticsValues.id(builder.installStatus);
        bootId = DiagnosticsValues.id(builder.bootId);
        lastHookAt = DiagnosticsValues.nonNegative(builder.lastHookAt);
        lastEventAt = DiagnosticsValues.nonNegative(builder.lastEventAt);
        rawHitCount = DiagnosticsValues.nonNegative(builder.rawHitCount);
        parsedCount = DiagnosticsValues.nonNegative(builder.parsedCount);
        identityResolvedCount = DiagnosticsValues.nonNegative(builder.identityResolvedCount);
        configRevision = DiagnosticsValues.revision(builder.configRevision);
        policyRevision = DiagnosticsValues.revision(builder.policyRevision);
        lastError = DiagnosticsValues.error(builder.lastError);
        updatedAt = DiagnosticsValues.timestamp(builder.updatedAt, System.currentTimeMillis());
    }

    public static Builder builder() { return new Builder(); }

    public boolean hasConsistentCounts() {
        return parsedCount <= rawHitCount
                && identityResolvedCount <= parsedCount;
    }

    public boolean matchesBatchIdentity(DiagnosticsWriteBatch.BatchIdentity identity) {
        return identity != null
                && processId.equals(identity.processId)
                && bootId.equals(identity.bootId);
    }

    public boolean isStorable() {
        return !processId.isEmpty()
                && !hookTargetId.isEmpty()
                && hasConsistentCounts();
    }

    public static final class Builder {
        private String processId = "";
        private String featureId = "";
        private String hookTargetId = "";
        private String installStatus = "UNKNOWN";
        private String bootId = "";
        private long lastHookAt;
        private long lastEventAt;
        private long rawHitCount;
        private long parsedCount;
        private long identityResolvedCount;
        private int configRevision = -1;
        private int policyRevision = -1;
        private String lastError = "";
        private long updatedAt;

        public Builder processId(String value) { processId = value; return this; }
        public Builder featureId(String value) { featureId = value; return this; }
        public Builder hookTargetId(String value) { hookTargetId = value; return this; }
        public Builder installStatus(String value) { installStatus = value; return this; }
        public Builder bootId(String value) { bootId = value; return this; }
        public Builder lastHookAt(long value) { lastHookAt = value; return this; }
        public Builder lastEventAt(long value) { lastEventAt = value; return this; }
        public Builder rawHitCount(long value) { rawHitCount = value; return this; }
        public Builder parsedCount(long value) { parsedCount = value; return this; }
        public Builder identityResolvedCount(long value) {
            identityResolvedCount = value;
            return this;
        }
        public Builder configRevision(int value) { configRevision = value; return this; }
        public Builder policyRevision(int value) { policyRevision = value; return this; }
        public Builder lastError(String value) { lastError = value; return this; }
        public Builder updatedAt(long value) { updatedAt = value; return this; }

        public DiagnosticsHookState build() { return new DiagnosticsHookState(this); }
    }
}
