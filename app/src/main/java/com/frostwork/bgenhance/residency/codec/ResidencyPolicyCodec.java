package com.frostwork.bgenhance.residency.codec;

import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Versioned canonical JSON codec for complete application residency policies. */
public final class ResidencyPolicyCodec {
    private final ResidencyPolicyValidator validator;

    public ResidencyPolicyCodec() {
        this(new ResidencyPolicyValidator());
    }

    public ResidencyPolicyCodec(ResidencyPolicyValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /** Encodes a validated document with stable field, rule and enum ordering. */
    public String encode(ResidencyPolicyDocument document) {
        ResidencyPolicyDocument canonical = validator.validate(document);
        StringBuilder json = new StringBuilder();
        writeDocument(json, canonical);
        String encoded = json.toString();
        validator.validateJsonSize(encoded);
        return encoded;
    }

    /** Decodes, validates and canonicalizes one complete schema v1 document. */
    public ResidencyPolicyDocument decode(String encoded) {
        try {
            validator.validateJsonSize(encoded);
            JSONTokener tokener = new JSONTokener(encoded);
            Object rootValue = tokener.nextValue();
            if (!(rootValue instanceof JSONObject)) {
                throw malformed("Policy root must be an object");
            }
            if (tokener.nextClean() != 0) {
                throw malformed("Unexpected content after policy document");
            }

            JSONObject root = (JSONObject) rootValue;
            int schemaVersion = requireInt(root, "schemaVersion", "schemaVersion");
            long policyRevision = requireLong(root, "policyRevision", "policyRevision");
            boolean enabled = requireBoolean(root, "enabled", "enabled");
            ResidencyMode defaultMode = requireEnum(
                    root, "defaultNewRuleMode", "defaultNewRuleMode", ResidencyMode.class);
            JSONArray rulesJson = requireArray(root, "rules", "rules");

            List<AppResidencyPolicy> rules = new ArrayList<>(rulesJson.length());
            for (int index = 0; index < rulesJson.length(); index++) {
                String path = "rules[" + index + "]";
                Object value = rulesJson.opt(index);
                if (!(value instanceof JSONObject)) {
                    throw malformed(path + " must be an object");
                }
                rules.add(readRule((JSONObject) value, path, schemaVersion));
            }

            int decodedSchema = schemaVersion == ResidencyPolicyDocument.LEGACY_SCHEMA_VERSION
                    ? ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION
                    : schemaVersion;
            ResidencyPolicyDocument document = new ResidencyPolicyDocument(
                    decodedSchema, policyRevision, enabled, defaultMode, rules);
            return validator.validate(document);
        } catch (JSONException exception) {
            throw new ResidencyPolicyCodecException("Malformed residency policy JSON", exception);
        }
    }

    private static void writeDocument(StringBuilder json, ResidencyPolicyDocument document) {
        json.append('{');
        appendName(json, "schemaVersion");
        json.append(document.schemaVersion()).append(',');
        appendName(json, "policyRevision");
        json.append(document.policyRevision()).append(',');
        appendName(json, "enabled");
        json.append(document.enabled()).append(',');
        appendName(json, "defaultNewRuleMode");
        appendQuoted(json, document.defaultNewRuleMode().name());
        json.append(',');
        appendName(json, "rules");
        json.append('[');
        for (int index = 0; index < document.rules().size(); index++) {
            if (index > 0) json.append(',');
            AppResidencyPolicy rule = document.rules().get(index);
            writeRule(json, rule);
        }
        json.append(']').append('}');
    }

    private static void writeRule(StringBuilder json, AppResidencyPolicy rule) {
        json.append('{');
        appendName(json, "packageName");
        appendQuoted(json, rule.packageName());
        json.append(',');
        appendName(json, "userId");
        json.append(rule.userId());
        json.append(',');
        appendName(json, "enabled");
        json.append(rule.enabled()).append(',');
        appendName(json, "mode");
        appendQuoted(json, rule.mode().name());
        writeKillPolicy(json, rule.killPolicy());
        writeFreezePolicy(json, rule.freezePolicy());
        writeWakePolicy(json, rule.wakePolicy());
        writeResourcePolicy(json, rule.resourcePolicy());
        writeRefreezePolicy(json, rule.refreezePolicy());
        json.append('}');
    }

    private static void writeKillPolicy(StringBuilder json, KillPolicy policy) {
        json.append(',');
        appendName(json, "killPolicy");
        json.append('{');
        appendBooleanField(json, "protectOguardKill", policy.protectOguardKill(), false);
        appendBooleanField(
                json, "protectOguardForceStop", policy.protectOguardForceStop(), true);
        appendBooleanField(
                json, "protectAthenaPeriodic", policy.protectAthenaPeriodic(), true);
        appendBooleanField(json, "protectFastClear", policy.protectFastClear(), true);
        appendBooleanField(json, "protectTournament", policy.protectTournament(), true);
        appendBooleanField(json, "protectMemoryGuard", policy.protectMemoryGuard(), true);
        appendBooleanField(json, "protectNirvana", policy.protectNirvana(), true);
        appendBooleanField(
                json, "protectOsensePerProcess", policy.protectOsensePerProcess(), true);
        json.append('}');
    }

    private static void writeFreezePolicy(StringBuilder json, FreezePolicy policy) {
        json.append(',');
        appendName(json, "freezePolicy");
        json.append('{');
        appendBooleanField(json, "allowNormalFreeze", policy.allowNormalFreeze(), false);
        appendBooleanField(json, "allowFastFreeze", policy.allowFastFreeze(), true);
        appendBooleanField(json, "allowSuperFreeze", policy.allowSuperFreeze(), true);
        appendBooleanField(json, "allowPreloadFreeze", policy.allowPreloadFreeze(), true);
        appendBooleanField(json, "customTiming", policy.customTiming(), true);
        appendLongField(json, "rToMDelayMs", policy.rToMDelayMs(), true);
        appendLongField(json, "mToFDelayMs", policy.mToFDelayMs(), true);
        appendBooleanField(json, "fullExemption", policy.fullExemption(), true);
        appendBooleanField(json, "coreExemption", policy.coreExemption(), true);
        appendBooleanField(json, "lcdOnExemption", policy.lcdOnExemption(), true);
        json.append('}');
    }

    private static void writeWakePolicy(StringBuilder json, WakePolicy policy) {
        json.append(',');
        appendName(json, "wakePolicy");
        json.append('{');
        writeWakeChannel(json, "packet", policy.packet());
        json.append(',');
        writeWakeChannel(json, "alarm", policy.alarm());
        json.append(',');
        appendName(json, "blockedSources");
        json.append('[');
        List<WakeSource> sources = sortedEnums(policy.blockedSources());
        for (int index = 0; index < sources.size(); index++) {
            if (index > 0) json.append(',');
            appendQuoted(json, sources.get(index).name());
        }
        json.append(']').append('}');
    }

    private static void writeWakeChannel(
            StringBuilder json, String name, WakeChannelPolicy policy) {
        appendName(json, name);
        json.append('{');
        appendName(json, "mode");
        appendQuoted(json, policy.mode().name());
        appendLongField(json, "cooldownMs", policy.cooldownMs(), true);
        json.append('}');
    }

    private static void writeResourcePolicy(StringBuilder json, ResourcePolicy policy) {
        json.append(',');
        appendName(json, "resourcePolicy");
        json.append('{');
        appendBooleanField(json, "keepNetwork", policy.keepNetwork(), false);
        json.append(',');
        appendName(json, "keptResources");
        json.append('[');
        List<ResourceType> resources = sortedEnums(policy.keptResources());
        for (int index = 0; index < resources.size(); index++) {
            if (index > 0) json.append(',');
            appendQuoted(json, resources.get(index).name());
        }
        json.append(']').append('}');
    }

    private static void writeRefreezePolicy(StringBuilder json, RefreezePolicy policy) {
        json.append(',');
        appendName(json, "refreezePolicy");
        json.append('{');
        appendName(json, "mode");
        appendQuoted(json, policy.mode().name());
        appendLongField(json, "defaultDelayMs", policy.defaultDelayMs(), true);
        appendLongField(json, "packetDelayMs", policy.packetDelayMs(), true);
        appendLongField(json, "alarmDelayMs", policy.alarmDelayMs(), true);
        appendLongField(json, "binderDelayMs", policy.binderDelayMs(), true);
        appendLongField(json, "serviceDelayMs", policy.serviceDelayMs(), true);
        appendLongField(json, "broadcastDelayMs", policy.broadcastDelayMs(), true);
        appendLongField(json, "jobDelayMs", policy.jobDelayMs(), true);
        json.append('}');
    }

    private static void appendBooleanField(
            StringBuilder json, String name, boolean value, boolean prependComma) {
        if (prependComma) json.append(',');
        appendName(json, name);
        json.append(value);
    }

    private static void appendLongField(
            StringBuilder json, String name, long value, boolean prependComma) {
        if (prependComma) json.append(',');
        appendName(json, name);
        json.append(value);
    }

    private static void appendName(StringBuilder json, String name) {
        appendQuoted(json, name);
        json.append(':');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append(JSONObject.quote(value));
    }

    private static AppResidencyPolicy readRule(JSONObject json, String path, int schemaVersion) {
        String packageName = requireString(json, "packageName", path + ".packageName");
        int userId;
        if (schemaVersion == ResidencyPolicyDocument.LEGACY_SCHEMA_VERSION) {
            if (json.has("userId")) {
                throw malformed(path + ".userId is not valid in schemaVersion "
                        + ResidencyPolicyDocument.LEGACY_SCHEMA_VERSION);
            }
            userId = AppResidencyPolicy.PRIMARY_USER_ID;
        } else {
            userId = requireInt(json, "userId", path + ".userId");
            if (!AppInstanceKey.isRepresentableUserId(userId)) {
                throw malformed(path + ".userId cannot form a non-negative UID");
            }
        }
        boolean enabled = requireBoolean(json, "enabled", path + ".enabled");
        ResidencyMode mode = requireEnum(json, "mode", path + ".mode", ResidencyMode.class);
        KillPolicy kill = readKillPolicy(
                requireObject(json, "killPolicy", path + ".killPolicy"), path + ".killPolicy");
        FreezePolicy freeze = readFreezePolicy(
                requireObject(json, "freezePolicy", path + ".freezePolicy"),
                path + ".freezePolicy");
        WakePolicy wake = readWakePolicy(
                requireObject(json, "wakePolicy", path + ".wakePolicy"), path + ".wakePolicy");
        ResourcePolicy resources = readResourcePolicy(
                requireObject(json, "resourcePolicy", path + ".resourcePolicy"),
                path + ".resourcePolicy");
        RefreezePolicy refreeze = readRefreezePolicy(
                requireObject(json, "refreezePolicy", path + ".refreezePolicy"),
                path + ".refreezePolicy");
        return new AppResidencyPolicy(
                packageName, userId, enabled, mode, kill, freeze, wake, resources, refreeze);
    }

    private static KillPolicy readKillPolicy(JSONObject json, String path) {
        return new KillPolicy(
                requireBoolean(json, "protectOguardKill", path + ".protectOguardKill"),
                requireBoolean(json, "protectOguardForceStop", path + ".protectOguardForceStop"),
                requireBoolean(json, "protectAthenaPeriodic", path + ".protectAthenaPeriodic"),
                requireBoolean(json, "protectFastClear", path + ".protectFastClear"),
                requireBoolean(json, "protectTournament", path + ".protectTournament"),
                requireBoolean(json, "protectMemoryGuard", path + ".protectMemoryGuard"),
                requireBoolean(json, "protectNirvana", path + ".protectNirvana"),
                requireBoolean(
                        json, "protectOsensePerProcess", path + ".protectOsensePerProcess"));
    }

    private static FreezePolicy readFreezePolicy(JSONObject json, String path) {
        return new FreezePolicy(
                requireBoolean(json, "allowNormalFreeze", path + ".allowNormalFreeze"),
                requireBoolean(json, "allowFastFreeze", path + ".allowFastFreeze"),
                requireBoolean(json, "allowSuperFreeze", path + ".allowSuperFreeze"),
                requireBoolean(json, "allowPreloadFreeze", path + ".allowPreloadFreeze"),
                requireBoolean(json, "customTiming", path + ".customTiming"),
                requireLong(json, "rToMDelayMs", path + ".rToMDelayMs"),
                requireLong(json, "mToFDelayMs", path + ".mToFDelayMs"),
                requireBoolean(json, "fullExemption", path + ".fullExemption"),
                requireBoolean(json, "coreExemption", path + ".coreExemption"),
                requireBoolean(json, "lcdOnExemption", path + ".lcdOnExemption"));
    }

    private static WakePolicy readWakePolicy(JSONObject json, String path) {
        WakeChannelPolicy packet = readWakeChannel(
                requireObject(json, "packet", path + ".packet"), path + ".packet");
        WakeChannelPolicy alarm = readWakeChannel(
                requireObject(json, "alarm", path + ".alarm"), path + ".alarm");
        Set<WakeSource> blocked = requireEnumSet(
                json, "blockedSources", path + ".blockedSources", WakeSource.class);
        return new WakePolicy(packet, alarm, blocked);
    }

    private static WakeChannelPolicy readWakeChannel(JSONObject json, String path) {
        return new WakeChannelPolicy(
                requireEnum(json, "mode", path + ".mode", WakeMode.class),
                requireLong(json, "cooldownMs", path + ".cooldownMs"));
    }

    private static ResourcePolicy readResourcePolicy(JSONObject json, String path) {
        return new ResourcePolicy(
                requireBoolean(json, "keepNetwork", path + ".keepNetwork"),
                requireEnumSet(
                        json, "keptResources", path + ".keptResources", ResourceType.class));
    }

    private static RefreezePolicy readRefreezePolicy(JSONObject json, String path) {
        return new RefreezePolicy(
                requireEnum(json, "mode", path + ".mode", RefreezeMode.class),
                requireLong(json, "defaultDelayMs", path + ".defaultDelayMs"),
                requireLong(json, "packetDelayMs", path + ".packetDelayMs"),
                requireLong(json, "alarmDelayMs", path + ".alarmDelayMs"),
                requireLong(json, "binderDelayMs", path + ".binderDelayMs"),
                requireLong(json, "serviceDelayMs", path + ".serviceDelayMs"),
                requireLong(json, "broadcastDelayMs", path + ".broadcastDelayMs"),
                requireLong(json, "jobDelayMs", path + ".jobDelayMs"));
    }

    private static JSONObject requireObject(JSONObject parent, String name, String path) {
        Object value = requireValue(parent, name, path);
        if (!(value instanceof JSONObject)) {
            throw malformed(path + " must be an object");
        }
        return (JSONObject) value;
    }

    private static JSONArray requireArray(JSONObject parent, String name, String path) {
        Object value = requireValue(parent, name, path);
        if (!(value instanceof JSONArray)) {
            throw malformed(path + " must be an array");
        }
        return (JSONArray) value;
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

    private static <E extends Enum<E>> E requireEnum(
            JSONObject parent, String name, String path, Class<E> enumType) {
        String value = requireString(parent, name, path);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw malformed(path + " contains unknown " + enumType.getSimpleName()
                    + " value: " + value);
        }
    }

    private static <E extends Enum<E>> Set<E> requireEnumSet(
            JSONObject parent, String name, String path, Class<E> enumType) {
        JSONArray values = requireArray(parent, name, path);
        EnumSet<E> result = EnumSet.noneOf(enumType);
        for (int index = 0; index < values.length(); index++) {
            Object raw = values.opt(index);
            if (!(raw instanceof String)) {
                throw malformed(path + "[" + index + "] must be a string");
            }
            E value;
            try {
                value = Enum.valueOf(enumType, (String) raw);
            } catch (IllegalArgumentException exception) {
                throw malformed(path + "[" + index + "] contains unknown "
                        + enumType.getSimpleName() + " value: " + raw);
            }
            if (!result.add(value)) {
                throw malformed(path + " contains duplicate value: " + value.name());
            }
        }
        return result;
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

    private static <E extends Enum<E>> List<E> sortedEnums(Set<E> values) {
        List<E> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparing(Enum::name));
        return sorted;
    }

    private static ResidencyPolicyCodecException malformed(String message) {
        return new ResidencyPolicyCodecException(message);
    }
}
