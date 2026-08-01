package com.frostwork.bgenhance.residency.config;

import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidationException;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidator;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Validates and canonicalizes the unified runtime configuration envelope. */
public final class RuntimeConfigValidator {
    public static final int MAX_JSON_BYTES = 512 * 1024;

    private final ResidencyPolicyValidator residencyValidator;

    public RuntimeConfigValidator() {
        this(new ResidencyPolicyValidator());
    }

    public RuntimeConfigValidator(ResidencyPolicyValidator residencyValidator) {
        this.residencyValidator = Objects.requireNonNull(
                residencyValidator, "residencyValidator");
    }

    public RuntimeConfigDocument validate(RuntimeConfigDocument document) {
        Objects.requireNonNull(document, "document");
        if (!RuntimeConfigDocument.DOCUMENT_TYPE.equals(document.documentType())) {
            throw invalid("Unsupported documentType: " + document.documentType());
        }
        if (document.schemaVersion() != RuntimeConfigDocument.CURRENT_SCHEMA_VERSION) {
            throw invalid("Unsupported schemaVersion: " + document.schemaVersion());
        }
        if (document.revision() < 0L) {
            throw invalid("revision must be non-negative");
        }
        if (document.updatedAtEpochMs() < 0L) {
            throw invalid("updatedAtEpochMs must be non-negative");
        }

        GlobalFeatureConfig global = document.global();
        if (global.activePreset().isEmpty()) {
            throw invalid("global.activePreset must not be empty");
        }
        for (Map.Entry<String, Boolean> entry : global.legacyFeatures().entrySet()) {
            if (entry.getKey().isEmpty()) {
                throw invalid("global.legacyFeatures contains an empty key");
            }
        }

        ResidencyPolicyDocument residency;
        try {
            residency = residencyValidator.validate(document.residency());
        } catch (ResidencyPolicyValidationException exception) {
            throw new RuntimeConfigValidationException(
                    "Invalid residency policy: " + exception.getMessage(), exception);
        }
        return new RuntimeConfigDocument(
                document.documentType(),
                document.schemaVersion(),
                document.revision(),
                document.updatedAtEpochMs(),
                global,
                residency);
    }

    public void validateJsonSize(String json) {
        if (json == null) {
            throw invalid("JSON must not be null");
        }
        int byteCount = json.getBytes(StandardCharsets.UTF_8).length;
        if (byteCount > MAX_JSON_BYTES) {
            throw invalid("JSON exceeds the limit of " + MAX_JSON_BYTES + " UTF-8 bytes");
        }
    }

    private static RuntimeConfigValidationException invalid(String message) {
        return new RuntimeConfigValidationException(message);
    }
}
