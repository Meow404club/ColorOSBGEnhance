package com.frostwork.bgenhance.diagnostics;

/** One raw Hook invocation, kept separate from user-visible normalized behavior events. */
public final class RawHookHit {
    public final long timestampMillis;
    public final String bootId;
    public final String processId;
    public final String featureId;
    public final String hookTargetId;
    public final int configRevision;
    public final int policyRevision;
    public final boolean parsed;
    public final boolean identityResolved;
    public final String errorCode;

    private RawHookHit(Builder builder) {
        timestampMillis = DiagnosticsValues.timestamp(
                builder.timestampMillis, System.currentTimeMillis());
        bootId = DiagnosticsValues.id(builder.bootId);
        processId = DiagnosticsValues.id(builder.processId);
        featureId = DiagnosticsValues.id(builder.featureId);
        hookTargetId = DiagnosticsValues.id(builder.hookTargetId);
        configRevision = DiagnosticsValues.revision(builder.configRevision);
        policyRevision = DiagnosticsValues.revision(builder.policyRevision);
        parsed = builder.parsed;
        identityResolved = builder.identityResolved;
        errorCode = DiagnosticsValues.error(builder.errorCode);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isStorable() {
        return !processId.isEmpty() && !hookTargetId.isEmpty();
    }

    public static final class Builder {
        private long timestampMillis;
        private String bootId = "";
        private String processId = "";
        private String featureId = "";
        private String hookTargetId = "";
        private int configRevision = -1;
        private int policyRevision = -1;
        private boolean parsed;
        private boolean identityResolved;
        private String errorCode = "";

        public Builder timestampMillis(long value) { timestampMillis = value; return this; }
        public Builder bootId(String value) { bootId = value; return this; }
        public Builder processId(String value) { processId = value; return this; }
        public Builder featureId(String value) { featureId = value; return this; }
        public Builder hookTargetId(String value) { hookTargetId = value; return this; }
        public Builder configRevision(int value) { configRevision = value; return this; }
        public Builder policyRevision(int value) { policyRevision = value; return this; }
        public Builder parsed(boolean value) { parsed = value; return this; }
        public Builder identityResolved(boolean value) { identityResolved = value; return this; }
        public Builder errorCode(String value) { errorCode = value; return this; }

        public RawHookHit build() { return new RawHookHit(this); }
    }
}
