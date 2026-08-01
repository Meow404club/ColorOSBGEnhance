package com.frostwork.bgenhance.residency.config;

import com.frostwork.bgenhance.residency.codec.ResidencyPolicyCodec;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Canonical JSON codec for the single authoritative runtime configuration envelope. */
public final class RuntimeConfigCodec {
    private final RuntimeConfigValidator validator;
    private final ResidencyPolicyCodec residencyCodec;

    public RuntimeConfigCodec() {
        this(new RuntimeConfigValidator(), new ResidencyPolicyCodec());
    }

    public RuntimeConfigCodec(
            RuntimeConfigValidator validator, ResidencyPolicyCodec residencyCodec) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.residencyCodec = Objects.requireNonNull(residencyCodec, "residencyCodec");
    }

    /** Encodes all fields in a stable order and embeds residency as a JSON object. */
    public String encode(RuntimeConfigDocument document) {
        RuntimeConfigDocument canonical = validator.validate(document);
        String residencyJson = residencyCodec.encode(canonical.residency());

        StringBuilder json = new StringBuilder(residencyJson.length() + 256);
        json.append('{');
        appendName(json, "documentType");
        appendQuoted(json, canonical.documentType());
        json.append(',');
        appendName(json, "schemaVersion");
        json.append(canonical.schemaVersion()).append(',');
        appendName(json, "revision");
        json.append(canonical.revision()).append(',');
        appendName(json, "updatedAtEpochMs");
        json.append(canonical.updatedAtEpochMs()).append(',');
        appendName(json, "global");
        writeGlobal(json, canonical.global());
        json.append(',');
        appendName(json, "residency");
        json.append(residencyJson);
        json.append('}');

        String encoded = json.toString();
        validator.validateJsonSize(encoded);
        return encoded;
    }

    /** Decodes all required schema v1 fields while ignoring unknown optional fields. */
    public RuntimeConfigDocument decode(String encoded) {
        try {
            validator.validateJsonSize(encoded);
            JSONTokener tokener = new JSONTokener(encoded);
            Object rootValue = tokener.nextValue();
            if (!(rootValue instanceof JSONObject)) {
                throw malformed("Runtime configuration root must be an object");
            }
            if (tokener.nextClean() != 0) {
                throw malformed("Unexpected content after runtime configuration");
            }

            JSONObject root = (JSONObject) rootValue;
            String documentType = requireString(root, "documentType", "documentType");
            int schemaVersion = requireInt(root, "schemaVersion", "schemaVersion");
            long revision = requireLong(root, "revision", "revision");
            long updatedAtEpochMs = requireLong(
                    root, "updatedAtEpochMs", "updatedAtEpochMs");
            GlobalFeatureConfig global = readGlobal(
                    requireObject(root, "global", "global"));
            JSONObject residencyJson = requireObject(root, "residency", "residency");
            ResidencyPolicyDocument residency;
            try {
                residency = residencyCodec.decode(residencyJson.toString());
            } catch (IllegalArgumentException exception) {
                throw new RuntimeConfigCodecException(
                        "Invalid residency policy: " + exception.getMessage(), exception);
            }

            return validator.validate(new RuntimeConfigDocument(
                    documentType,
                    schemaVersion,
                    revision,
                    updatedAtEpochMs,
                    global,
                    residency));
        } catch (JSONException exception) {
            throw new RuntimeConfigCodecException(
                    "Malformed runtime configuration JSON", exception);
        }
    }

    private static void writeGlobal(StringBuilder json, GlobalFeatureConfig global) {
        json.append('{');
        appendName(json, "masterEnabled");
        json.append(global.masterEnabled()).append(',');
        appendName(json, "dryRun");
        json.append(global.dryRun()).append(',');
        appendName(json, "verboseDiagnostics");
        json.append(global.verboseDiagnostics()).append(',');
        appendName(json, "activePreset");
        appendQuoted(json, global.activePreset());
        json.append(',');
        appendName(json, "legacyFeatures");
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, Boolean> entry : global.legacyFeatures().entrySet()) {
            if (!first) json.append(',');
            appendName(json, entry.getKey());
            json.append(entry.getValue());
            first = false;
        }
        json.append('}').append('}');
    }

    private static GlobalFeatureConfig readGlobal(JSONObject json) {
        boolean masterEnabled = requireBoolean(
                json, "masterEnabled", "global.masterEnabled");
        boolean dryRun = requireBoolean(json, "dryRun", "global.dryRun");
        boolean verboseDiagnostics = requireBoolean(
                json, "verboseDiagnostics", "global.verboseDiagnostics");
        String activePreset = requireString(json, "activePreset", "global.activePreset");
        JSONObject legacyJson = requireObject(
                json, "legacyFeatures", "global.legacyFeatures");
        Map<String, Boolean> legacyFeatures = new TreeMap<>();
        Iterator<String> keys = legacyJson.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = legacyJson.opt(key);
            if (!(value instanceof Boolean)) {
                throw malformed("global.legacyFeatures." + key + " must be a boolean");
            }
            legacyFeatures.put(key, (Boolean) value);
        }
        return new GlobalFeatureConfig(
                masterEnabled,
                dryRun,
                verboseDiagnostics,
                activePreset,
                legacyFeatures);
    }

    private static JSONObject requireObject(JSONObject parent, String name, String path) {
        Object value = requireValue(parent, name, path);
        if (!(value instanceof JSONObject)) {
            throw malformed(path + " must be an object");
        }
        return (JSONObject) value;
    }

    private static String requireString(JSONObject parent, String name, String path) {
        Object value = requireValue(parent, name, path);
        if (!(value instanceof String)) {
            throw malformed(path + " must be a string");
        }
        return (String) value;
    }

    private static boolean requireBoolean(JSONObject parent, String name, String path) {
        Object value = requireValue(parent, name, path);
        if (!(value instanceof Boolean)) {
            throw malformed(path + " must be a boolean");
        }
        return (Boolean) value;
    }

    private static int requireInt(JSONObject parent, String name, String path) {
        long value = requireLong(parent, name, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw malformed(path + " is outside the integer range");
        }
        return (int) value;
    }

    private static long requireLong(JSONObject parent, String name, String path) {
        Object value = requireValue(parent, name, path);
        if (!(value instanceof Number)) {
            throw malformed(path + " must be an integer");
        }
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw malformed(path + " must be an integer in the signed 64-bit range");
        }
    }

    private static Object requireValue(JSONObject parent, String name, String path) {
        if (!parent.has(name)) {
            throw malformed("Missing required field: " + path);
        }
        Object value = parent.opt(name);
        if (value == null || value == JSONObject.NULL) {
            throw malformed(path + " must not be null");
        }
        return value;
    }

    private static void appendName(StringBuilder json, String name) {
        appendQuoted(json, name);
        json.append(':');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append(JSONObject.quote(value));
    }

    private static RuntimeConfigCodecException malformed(String message) {
        return new RuntimeConfigCodecException(message);
    }
}
