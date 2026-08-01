package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import java.util.Objects;

/** One atomic hook-side state; unavailable states never retain an enforceable snapshot. */
public final class HookRuntimeConfigState {
    private final boolean available;
    private final RuntimeConfigDocument document;
    private final RuntimeConfigSnapshot snapshot;
    private final long lastGoodRevision;
    private final String source;
    private final String error;
    private final long loadedAtElapsedMs;

    private HookRuntimeConfigState(
            boolean available,
            RuntimeConfigDocument document,
            RuntimeConfigSnapshot snapshot,
            long lastGoodRevision,
            String source,
            String error,
            long loadedAtElapsedMs) {
        this.available = available;
        this.document = document;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.lastGoodRevision = lastGoodRevision;
        this.source = Objects.requireNonNull(source, "source");
        this.error = Objects.requireNonNull(error, "error");
        this.loadedAtElapsedMs = loadedAtElapsedMs;
    }

    public static HookRuntimeConfigState available(
            RuntimeConfigDocument document, String source, long loadedAtElapsedMs) {
        RuntimeConfigDocument value = Objects.requireNonNull(document, "document");
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                value.revision(),
                value.global().masterEnabled(),
                value.global().dryRun(),
                value.global().legacyFeatures(),
                value.residency());
        return new HookRuntimeConfigState(
                true,
                value,
                snapshot,
                value.revision(),
                source,
                "",
                loadedAtElapsedMs);
    }

    public static HookRuntimeConfigState unavailable(
            String error, long lastGoodRevision, long loadedAtElapsedMs) {
        long safeLastGoodRevision = Math.max(-1L, lastGoodRevision);
        return new HookRuntimeConfigState(
                false,
                null,
                RuntimeConfigSnapshot.unavailable(Math.max(0L, safeLastGoodRevision)),
                safeLastGoodRevision,
                "remote_preferences",
                error == null ? "unknown" : error,
                loadedAtElapsedMs);
    }

    public boolean available() { return available; }
    public RuntimeConfigDocument document() { return document; }
    public RuntimeConfigSnapshot snapshot() { return snapshot; }
    public long revision() { return available ? document.revision() : lastGoodRevision; }
    public long lastGoodRevision() { return lastGoodRevision; }
    public String source() { return source; }
    public String error() { return error; }
    public long loadedAtElapsedMs() { return loadedAtElapsedMs; }
}
