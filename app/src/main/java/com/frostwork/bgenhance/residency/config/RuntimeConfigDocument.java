package com.frostwork.bgenhance.residency.config;

import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.ArrayList;
import java.util.Objects;

/** Immutable root document published atomically to hook processes. */
public final class RuntimeConfigDocument {
    public static final String DOCUMENT_TYPE = "bgenhance_runtime_config";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final String documentType;
    private final int schemaVersion;
    private final long revision;
    private final long updatedAtEpochMs;
    private final GlobalFeatureConfig global;
    private final ResidencyPolicyDocument residency;

    public RuntimeConfigDocument(
            String documentType,
            int schemaVersion,
            long revision,
            long updatedAtEpochMs,
            GlobalFeatureConfig global,
            ResidencyPolicyDocument residency) {
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.updatedAtEpochMs = updatedAtEpochMs;
        GlobalFeatureConfig sourceGlobal = Objects.requireNonNull(global, "global");
        this.global = new GlobalFeatureConfig(
                sourceGlobal.masterEnabled(),
                sourceGlobal.dryRun(),
                sourceGlobal.verboseDiagnostics(),
                sourceGlobal.activePreset(),
                sourceGlobal.legacyFeatures());

        ResidencyPolicyDocument sourceResidency =
                Objects.requireNonNull(residency, "residency");
        this.residency = new ResidencyPolicyDocument(
                sourceResidency.schemaVersion(),
                sourceResidency.policyRevision(),
                sourceResidency.enabled(),
                sourceResidency.defaultNewRuleMode(),
                new ArrayList<>(sourceResidency.rules()));
    }

    public static RuntimeConfigDocument empty() {
        return new RuntimeConfigDocument(
                DOCUMENT_TYPE,
                CURRENT_SCHEMA_VERSION,
                0L,
                0L,
                GlobalFeatureConfig.defaults(),
                ResidencyPolicyDocument.empty());
    }

    public String documentType() { return documentType; }
    public int schemaVersion() { return schemaVersion; }
    public long revision() { return revision; }
    public long updatedAtEpochMs() { return updatedAtEpochMs; }
    public GlobalFeatureConfig global() { return global; }

    /** The nested policy revision changes only when residency rules change. */
    public ResidencyPolicyDocument residency() { return residency; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuntimeConfigDocument)) return false;
        RuntimeConfigDocument that = (RuntimeConfigDocument) other;
        return schemaVersion == that.schemaVersion
                && revision == that.revision
                && updatedAtEpochMs == that.updatedAtEpochMs
                && documentType.equals(that.documentType)
                && global.equals(that.global)
                && residency.equals(that.residency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                documentType, schemaVersion, revision, updatedAtEpochMs, global, residency);
    }
}
