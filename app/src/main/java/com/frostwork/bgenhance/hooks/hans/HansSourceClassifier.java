package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.residency.model.FreezeSource;
import java.util.Objects;

/** Maps vendor HANS freeze markers to stable policy sources. */
public final class HansSourceClassifier {
    private static final String FAST_FREEZE = "enterFF";
    private static final String PRELOAD_FREEZE = "Preload_F";
    private static final String SUPER_FREEZE = "Super_F";

    private HansSourceClassifier() {}

    public static Resolution classify(String from, boolean superFreezeMode) {
        if (from == null) return Resolution.failed(Failure.NULL_SOURCE);
        if (FAST_FREEZE.equals(from)) {
            return superFreezeMode
                    ? Resolution.failed(Failure.CONFLICTING_SOURCE)
                    : Resolution.resolved(FreezeSource.FAST);
        }
        if (PRELOAD_FREEZE.equals(from)) {
            return superFreezeMode
                    ? Resolution.failed(Failure.CONFLICTING_SOURCE)
                    : Resolution.resolved(FreezeSource.PRELOAD);
        }
        if (SUPER_FREEZE.equals(from)) {
            return superFreezeMode
                    ? Resolution.resolved(FreezeSource.SUPER)
                    : Resolution.failed(Failure.CONFLICTING_SOURCE);
        }
        if (from.isEmpty()) {
            return Resolution.resolved(
                    superFreezeMode ? FreezeSource.SUPER : FreezeSource.NORMAL);
        }
        return Resolution.failed(Failure.UNKNOWN_SOURCE);
    }

    /** Classifies manager fallback markers when no package-object mode flag is available. */
    public static Resolution classifyManager(String from) {
        if (SUPER_FREEZE.equals(from)) {
            return Resolution.resolved(FreezeSource.SUPER);
        }
        return classify(from, false);
    }

    public enum Failure {
        NULL_SOURCE,
        UNKNOWN_SOURCE,
        CONFLICTING_SOURCE
    }

    public static final class Resolution {
        private final FreezeSource source;
        private final Failure failure;

        private Resolution(FreezeSource source, Failure failure) {
            this.source = source;
            this.failure = failure;
        }

        private static Resolution resolved(FreezeSource source) {
            return new Resolution(Objects.requireNonNull(source, "source"), null);
        }

        private static Resolution failed(Failure failure) {
            return new Resolution(null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean isResolved() { return source != null; }
        public FreezeSource sourceOrNull() { return source; }
        public Failure failureOrNull() { return failure; }
    }
}
