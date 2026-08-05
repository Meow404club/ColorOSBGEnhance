package com.frostwork.bgenhance.diagnostics;

/** Pure normalization boundary between a raw Hook hit and one behavioral event. */
public final class DiagnosticsEventNormalizer {
    private DiagnosticsEventNormalizer() {}

    public static Result normalize(RawHookHit rawHit, DecisionData decision) {
        try {
            if (rawHit == null) return Result.rejected("missing_raw_hit");
            if (!rawHit.isStorable()) return Result.rejected("invalid_raw_hit");
            if (!rawHit.parsed) return Result.rejected("hook_arguments_unparsed");
            if (decision == null) return Result.rejected("missing_decision");
            if (DiagnosticsValues.id(decision.behavior).isEmpty()
                    || DiagnosticsValues.id(decision.decision).isEmpty()
                    || DiagnosticsValues.id(decision.result).isEmpty()) {
                return Result.rejected("invalid_decision");
            }
            NormalizedDiagnosticsEvent event = NormalizedDiagnosticsEvent.builder()
                    .timestampMillis(rawHit.timestampMillis)
                    .bootId(rawHit.bootId)
                    .processId(rawHit.processId)
                    .featureId(rawHit.featureId)
                    .hookTargetId(rawHit.hookTargetId)
                    .behavior(decision.behavior)
                    .source(decision.source)
                    .packageName(decision.packageName)
                    .uid(decision.uid)
                    .scene(decision.scene)
                    .reasonCode(decision.reasonCode)
                    .configRevision(rawHit.configRevision)
                    .policyRevision(rawHit.policyRevision)
                    .mode(decision.mode)
                    .decision(decision.decision)
                    .result(decision.result)
                    .failOpenReason(decision.failOpenReason)
                    .latencyMicros(decision.latencyMicros)
                    .rawHitCount(Math.max(1, decision.rawHitCount))
                    .identityResolved(rawHit.identityResolved)
                    .eventToken(decision.eventToken)
                    .build();
            return event.isStorable() ? Result.accepted(event)
                    : Result.rejected("invalid_normalized_event");
        } catch (Throwable ignored) {
            return Result.rejected("normalization_failed");
        }
    }

    public static final class DecisionData {
        public String behavior = "";
        public String source = "";
        public String packageName = "";
        public int uid = -1;
        public String scene = "";
        public int reasonCode;
        public String mode = "";
        public String decision = "";
        public String result = "";
        public String failOpenReason = "";
        public long latencyMicros;
        public int rawHitCount = 1;
        public String eventToken = "";
    }

    public static final class Result {
        public final NormalizedDiagnosticsEvent event;
        public final String rejectionReason;

        private Result(NormalizedDiagnosticsEvent event, String rejectionReason) {
            this.event = event;
            this.rejectionReason = rejectionReason;
        }

        static Result accepted(NormalizedDiagnosticsEvent event) {
            return new Result(event, "");
        }

        static Result rejected(String reason) {
            return new Result(null, DiagnosticsValues.id(reason));
        }

        public boolean accepted() { return event != null; }
    }
}
