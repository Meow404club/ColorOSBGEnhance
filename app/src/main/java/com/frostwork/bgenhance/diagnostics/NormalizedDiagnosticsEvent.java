package com.frostwork.bgenhance.diagnostics;

/** Stable DTO for one normalized user-visible behavior event. */
public final class NormalizedDiagnosticsEvent {
    public final long id;
    public final long timestampMillis;
    public final String bootId;
    public final String processId;
    public final String featureId;
    public final String hookTargetId;
    public final String behavior;
    public final String source;
    public final String packageName;
    public final int uid;
    public final String scene;
    public final int reasonCode;
    public final int configRevision;
    public final int policyRevision;
    public final String mode;
    public final String decision;
    public final String result;
    public final String failOpenReason;
    public final long latencyMicros;
    public final int rawHitCount;
    public final boolean identityResolved;
    public final String eventToken;

    private NormalizedDiagnosticsEvent(Builder builder) {
        id = Math.max(0L, builder.id);
        timestampMillis = DiagnosticsValues.timestamp(
                builder.timestampMillis, System.currentTimeMillis());
        bootId = DiagnosticsValues.id(builder.bootId);
        processId = DiagnosticsValues.id(builder.processId);
        featureId = DiagnosticsValues.id(builder.featureId);
        hookTargetId = DiagnosticsValues.id(builder.hookTargetId);
        behavior = DiagnosticsValues.id(builder.behavior);
        source = DiagnosticsValues.id(builder.source);
        packageName = DiagnosticsValues.packageName(builder.packageName);
        uid = Math.max(-1, builder.uid);
        scene = DiagnosticsValues.text(builder.scene);
        reasonCode = builder.reasonCode;
        configRevision = DiagnosticsValues.revision(builder.configRevision);
        policyRevision = DiagnosticsValues.revision(builder.policyRevision);
        mode = DiagnosticsValues.id(builder.mode);
        decision = DiagnosticsValues.id(builder.decision);
        result = DiagnosticsValues.id(builder.result);
        failOpenReason = DiagnosticsValues.error(builder.failOpenReason);
        latencyMicros = DiagnosticsValues.nonNegative(builder.latencyMicros);
        rawHitCount = Math.max(1, builder.rawHitCount);
        identityResolved = builder.identityResolved;
        eventToken = DiagnosticsValues.text(builder.eventToken, 192);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder copyBuilder() {
        return new Builder()
                .id(id)
                .timestampMillis(timestampMillis)
                .bootId(bootId)
                .processId(processId)
                .featureId(featureId)
                .hookTargetId(hookTargetId)
                .behavior(behavior)
                .source(source)
                .packageName(packageName)
                .uid(uid)
                .scene(scene)
                .reasonCode(reasonCode)
                .configRevision(configRevision)
                .policyRevision(policyRevision)
                .mode(mode)
                .decision(decision)
                .result(result)
                .failOpenReason(failOpenReason)
                .latencyMicros(latencyMicros)
                .rawHitCount(rawHitCount)
                .identityResolved(identityResolved)
                .eventToken(eventToken);
    }

    public boolean isStorable() {
        return !processId.isEmpty()
                && !featureId.isEmpty()
                && !hookTargetId.isEmpty()
                && !behavior.isEmpty()
                && !decision.isEmpty()
                && !result.isEmpty();
    }

    public static final class Builder {
        private long id;
        private long timestampMillis;
        private String bootId = "";
        private String processId = "";
        private String featureId = "";
        private String hookTargetId = "";
        private String behavior = "";
        private String source = "";
        private String packageName = "";
        private int uid = -1;
        private String scene = "";
        private int reasonCode;
        private int configRevision = -1;
        private int policyRevision = -1;
        private String mode = "";
        private String decision = "";
        private String result = "";
        private String failOpenReason = "";
        private long latencyMicros;
        private int rawHitCount = 1;
        private boolean identityResolved;
        private String eventToken = "";

        public Builder id(long value) { id = value; return this; }
        public Builder timestampMillis(long value) { timestampMillis = value; return this; }
        public Builder bootId(String value) { bootId = value; return this; }
        public Builder processId(String value) { processId = value; return this; }
        public Builder featureId(String value) { featureId = value; return this; }
        public Builder hookTargetId(String value) { hookTargetId = value; return this; }
        public Builder behavior(String value) { behavior = value; return this; }
        public Builder source(String value) { source = value; return this; }
        public Builder packageName(String value) { packageName = value; return this; }
        public Builder uid(int value) { uid = value; return this; }
        public Builder scene(String value) { scene = value; return this; }
        public Builder reasonCode(int value) { reasonCode = value; return this; }
        public Builder configRevision(int value) { configRevision = value; return this; }
        public Builder policyRevision(int value) { policyRevision = value; return this; }
        public Builder mode(String value) { mode = value; return this; }
        public Builder decision(String value) { decision = value; return this; }
        public Builder result(String value) { result = value; return this; }
        public Builder failOpenReason(String value) { failOpenReason = value; return this; }
        public Builder latencyMicros(long value) { latencyMicros = value; return this; }
        public Builder rawHitCount(int value) { rawHitCount = value; return this; }
        public Builder identityResolved(boolean value) { identityResolved = value; return this; }
        public Builder eventToken(String value) { eventToken = value; return this; }

        public NormalizedDiagnosticsEvent build() {
            return new NormalizedDiagnosticsEvent(this);
        }
    }
}
