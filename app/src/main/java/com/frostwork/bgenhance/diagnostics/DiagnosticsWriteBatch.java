package com.frostwork.bgenhance.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable transaction payload accepted by the diagnostics writer. */
public final class DiagnosticsWriteBatch {
    public final BatchIdentity batchIdentity;
    public final CountingMode countingMode;
    public final List<RawHookHit> rawHits;
    public final List<NormalizedDiagnosticsEvent> events;
    public final List<LegacyFeatureDelta> legacyFeatureDeltas;
    public final List<DiagnosticsHookState> hookStates;

    private DiagnosticsWriteBatch(Builder builder) {
        boolean hasTyped = !builder.rawHits.isEmpty()
                || !builder.events.isEmpty()
                || !builder.hookStates.isEmpty();
        boolean hasLegacy = !builder.legacyFeatureDeltas.isEmpty();
        if (hasTyped && hasLegacy) {
            throw new IllegalStateException("typed and legacy diagnostics cannot be mixed");
        }
        CountingMode inferred = hasTyped
                ? CountingMode.TYPED
                : (hasLegacy ? CountingMode.LEGACY : CountingMode.NONE);
        if (builder.countingMode != CountingMode.NONE
                && inferred != CountingMode.NONE
                && builder.countingMode != inferred) {
            throw new IllegalStateException("diagnostics counting mode does not match payload");
        }
        batchIdentity = builder.batchIdentity;
        countingMode = builder.countingMode == CountingMode.NONE
                ? inferred : builder.countingMode;
        rawHits = immutableCopy(builder.rawHits);
        events = immutableCopy(builder.events);
        legacyFeatureDeltas = immutableCopy(builder.legacyFeatureDeltas);
        hookStates = immutableCopy(builder.hookStates);
    }

    public static Builder builder() { return new Builder(); }

    public boolean isEmpty() {
        return rawHits.isEmpty() && events.isEmpty()
                && legacyFeatureDeltas.isEmpty() && hookStates.isEmpty();
    }

    public int size() {
        return rawHits.size() + events.size()
                + legacyFeatureDeltas.size() + hookStates.size();
    }

    public boolean hasBatchIdentity() { return batchIdentity != null; }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public enum CountingMode {
        NONE("none"),
        TYPED("typed"),
        LEGACY("legacy");

        public final String wireValue;

        CountingMode(String wireValue) {
            this.wireValue = wireValue;
        }

        static CountingMode fromWireValue(String value) {
            if (TYPED.wireValue.equals(value)) return TYPED;
            if (LEGACY.wireValue.equals(value)) return LEGACY;
            return null;
        }
    }

    /** Stable identity used by the storage layer to accept a wire batch at most once. */
    public static final class BatchIdentity {
        public final int wireVersion;
        public final String bootId;
        public final String processId;
        public final String senderInstanceId;
        public final long epoch;
        public final long batchSequence;
        public final String payloadHash;

        public BatchIdentity(
                int wireVersion,
                String bootId,
                String processId,
                String senderInstanceId,
                long epoch,
                long batchSequence) {
            this(wireVersion, bootId, processId, senderInstanceId,
                    epoch, batchSequence, "");
        }

        public BatchIdentity(
                int wireVersion,
                String bootId,
                String processId,
                String senderInstanceId,
                long epoch,
                long batchSequence,
                String payloadHash) {
            if (wireVersion <= 0) {
                throw new IllegalArgumentException("wireVersion must be positive");
            }
            if (!DiagnosticsValues.isStrictId(bootId, false)) {
                throw new IllegalArgumentException("bootId is invalid");
            }
            if (!DiagnosticsValues.isStrictId(processId, false)) {
                throw new IllegalArgumentException("processId is invalid");
            }
            if (!DiagnosticsValues.isStrictId(senderInstanceId, false)) {
                throw new IllegalArgumentException("senderInstanceId is invalid");
            }
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            if (batchSequence < 0L) {
                throw new IllegalArgumentException("batchSequence must be non-negative");
            }
            if (!DiagnosticsValues.isSha256(payloadHash, true)) {
                throw new IllegalArgumentException("payloadHash is invalid");
            }
            this.wireVersion = wireVersion;
            this.bootId = bootId;
            this.processId = processId;
            this.senderInstanceId = senderInstanceId;
            this.epoch = epoch;
            this.batchSequence = batchSequence;
            this.payloadHash = payloadHash;
        }

        public String deduplicationKey() {
            return wireVersion + ":" + epoch + ":" + batchSequence + ":"
                    + bootId.length() + ":" + bootId
                    + processId.length() + ":" + processId
                    + senderInstanceId.length() + ":" + senderInstanceId
                    + payloadHash.length() + ":" + payloadHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BatchIdentity)) return false;
            BatchIdentity that = (BatchIdentity) other;
            return wireVersion == that.wireVersion
                    && epoch == that.epoch
                    && batchSequence == that.batchSequence
                    && bootId.equals(that.bootId)
                    && processId.equals(that.processId)
                    && senderInstanceId.equals(that.senderInstanceId)
                    && payloadHash.equals(that.payloadHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    wireVersion, bootId, processId, senderInstanceId,
                    epoch, batchSequence, payloadHash);
        }
    }

    public static final class Builder {
        private BatchIdentity batchIdentity;
        private CountingMode countingMode = CountingMode.NONE;
        private final List<RawHookHit> rawHits = new ArrayList<>();
        private final List<NormalizedDiagnosticsEvent> events = new ArrayList<>();
        private final List<LegacyFeatureDelta> legacyFeatureDeltas = new ArrayList<>();
        private final List<DiagnosticsHookState> hookStates = new ArrayList<>();

        public Builder batchIdentity(BatchIdentity value) {
            if (value == null) throw new NullPointerException("batchIdentity");
            if (batchIdentity != null && !batchIdentity.equals(value)) {
                throw new IllegalStateException("conflicting diagnostics batch identity");
            }
            batchIdentity = value;
            return this;
        }

        public Builder wireIdentity(
                int wireVersion,
                String bootId,
                String processId,
                String senderInstanceId,
                long epoch,
                long batchSequence) {
            return batchIdentity(new BatchIdentity(
                    wireVersion, bootId, processId, senderInstanceId, epoch, batchSequence));
        }

        public Builder wireIdentity(
                int wireVersion,
                String bootId,
                String processId,
                String senderInstanceId,
                long epoch,
                long batchSequence,
                String payloadHash) {
            return batchIdentity(new BatchIdentity(
                    wireVersion, bootId, processId, senderInstanceId,
                    epoch, batchSequence, payloadHash));
        }

        public Builder countingMode(CountingMode value) {
            if (value == null) throw new NullPointerException("countingMode");
            claimMode(value);
            return this;
        }

        public Builder addRawHit(RawHookHit value) {
            if (value != null) {
                claimMode(CountingMode.TYPED);
                rawHits.add(value);
            }
            return this;
        }

        public Builder addEvent(NormalizedDiagnosticsEvent value) {
            if (value != null) {
                claimMode(CountingMode.TYPED);
                events.add(value);
            }
            return this;
        }

        public Builder addLegacyFeatureDelta(LegacyFeatureDelta value) {
            if (value != null) {
                claimMode(CountingMode.LEGACY);
                legacyFeatureDeltas.add(value);
            }
            return this;
        }

        public Builder addHookState(DiagnosticsHookState value) {
            if (value != null) {
                claimMode(CountingMode.TYPED);
                hookStates.add(value);
            }
            return this;
        }

        public Builder addAll(DiagnosticsWriteBatch batch) {
            if (batch == null) return this;
            if (batch.batchIdentity != null) batchIdentity(batch.batchIdentity);
            claimMode(batch.countingMode);
            rawHits.addAll(batch.rawHits);
            events.addAll(batch.events);
            legacyFeatureDeltas.addAll(batch.legacyFeatureDeltas);
            hookStates.addAll(batch.hookStates);
            return this;
        }

        public DiagnosticsWriteBatch build() { return new DiagnosticsWriteBatch(this); }

        private void claimMode(CountingMode requested) {
            if (requested == CountingMode.NONE) return;
            if (countingMode != CountingMode.NONE && countingMode != requested) {
                throw new IllegalStateException("typed and legacy diagnostics cannot be mixed");
            }
            countingMode = requested;
        }
    }

    /** Compatibility payload for the existing homepage feature counters. */
    public static final class LegacyFeatureDelta {
        public final String featureId;
        public final long observedDelta;
        public final long blockedDelta;
        public final long failOpenDelta;
        public final long lastAt;
        public final String lastPackage;
        public final String lastProcess;
        public final String lastScene;
        public final String lastReason;
        public final String lastResult;

        public LegacyFeatureDelta(
                String featureId,
                long observedDelta,
                long blockedDelta,
                long failOpenDelta,
                long lastAt,
                String lastPackage,
                String lastProcess,
                String lastScene,
                String lastReason,
                String lastResult) {
            this.featureId = DiagnosticsValues.id(featureId);
            this.observedDelta = DiagnosticsValues.nonNegative(observedDelta);
            this.blockedDelta = DiagnosticsValues.nonNegative(blockedDelta);
            this.failOpenDelta = DiagnosticsValues.nonNegative(failOpenDelta);
            this.lastAt = DiagnosticsValues.nonNegative(lastAt);
            this.lastPackage = DiagnosticsValues.packageName(lastPackage);
            this.lastProcess = DiagnosticsValues.id(lastProcess);
            this.lastScene = DiagnosticsValues.text(lastScene);
            this.lastReason = DiagnosticsValues.text(lastReason);
            this.lastResult = DiagnosticsValues.id(lastResult);
        }

        public boolean hasConsistentCounts() {
            return blockedDelta <= observedDelta
                    && failOpenDelta <= observedDelta - blockedDelta;
        }

        public boolean isStorable() {
            return !featureId.isEmpty()
                    && hasConsistentCounts()
                    && (observedDelta > 0L || blockedDelta > 0L || failOpenDelta > 0L);
        }
    }
}
