package com.frostwork.bgenhance.residency.legacy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Decoder that preserves the reference module's version 1 through 5 migration behavior. */
final class LegacyHansPolicyCodec {
    static final int MAX_JSON_BYTES = 512 * 1024;
    static final int MAX_RULES = 512;
    static final long MIN_DELAY_MS = 1_000L;
    static final long MAX_DELAY_MS = 86_400_000L;

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");

    private LegacyHansPolicyCodec() {}

    static LegacyHansPolicyDocument decode(String json) throws LegacyHansImportException {
        if (json == null || json.trim().isEmpty()) {
            throw error("empty_document", "Legacy policy is empty");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw error("document_too_large", "Legacy policy exceeds 512 KiB");
        }
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", -1);
            if (version < LegacyHansWireConstants.MIN_VERSION
                    || version > LegacyHansWireConstants.MAX_VERSION) {
                throw error("unsupported_schema", "Unsupported legacy policy schema");
            }
            boolean enabled = root.optBoolean("enabled", false);
            long revision = Math.max(0L, root.optLong("revision", 0L));
            List<LegacyHansRule> rules = version == 1
                    ? decodeVersionOne(root) : decodeVersionTwoToFive(root);
            return new LegacyHansPolicyDocument(version, enabled, revision, rules);
        } catch (LegacyHansImportException error) {
            throw error;
        } catch (JSONException | IllegalArgumentException error) {
            throw new LegacyHansImportException(
                    "invalid_document", "Legacy policy is invalid", error);
        }
    }

    private static List<LegacyHansRule> decodeVersionOne(JSONObject root)
            throws JSONException, LegacyHansImportException {
        JSONArray array = root.optJSONArray("rules");
        if (array == null) return new ArrayList<>();
        rejectExcessiveRuleCount(array.length());
        Map<String, LegacyHansRule> migrated = new LinkedHashMap<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String packageName = item.getString("package").trim();
            String mode = item.optString("mode", "FOLLOW_SYSTEM");
            boolean enabled = item.optBoolean("enabled", true);
            long rToM = item.optLong("rToMMs", LegacyHansWireConstants.DEFAULT_R_TO_M_MS);
            long mToF = item.optLong("mToFMs", LegacyHansWireConstants.DEFAULT_M_TO_F_MS);
            validate(packageName, rToM, mToF, 0, 0,
                    LegacyHansWireConstants.WAKE_ALLOW,
                    LegacyHansWireConstants.DEFAULT_PACKET_COOLDOWN_MS, 0L,
                    LegacyHansWireConstants.WAKE_ALLOW,
                    LegacyHansWireConstants.DEFAULT_ALARM_COOLDOWN_MS, 0L, 0);

            LegacyHansRule previous = migrated.get(packageName);
            boolean fullExemption = "EXEMPT".equals(mode)
                    || previous != null && previous.fullExemption;
            boolean customTiming = "CUSTOM".equals(mode)
                    || previous != null && previous.customTiming;
            boolean mergedEnabled = enabled || previous != null && previous.enabled;
            long mergedRToM = "CUSTOM".equals(mode) ? rToM
                    : previous == null ? rToM : previous.rToMDelayMs;
            long mergedMToF = "CUSTOM".equals(mode) ? mToF
                    : previous == null ? mToF : previous.mToFDelayMs;
            migrated.put(packageName, createDefaults(
                    packageName, mergedEnabled, fullExemption, customTiming,
                    mergedRToM, mergedMToF));
        }
        return new ArrayList<>(migrated.values());
    }

    private static List<LegacyHansRule> decodeVersionTwoToFive(JSONObject root)
            throws JSONException, LegacyHansImportException {
        JSONArray array = root.optJSONArray("rules");
        if (array == null) return new ArrayList<>();
        rejectExcessiveRuleCount(array.length());
        Map<String, LegacyHansRule> normalized = new LinkedHashMap<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String packageName = item.getString("package").trim();
            boolean enabled = item.optBoolean("enabled", true);
            boolean fullExemption = item.optBoolean("fullExempt", false);
            boolean customTiming = item.optBoolean("customTiming", false);
            long rToM = item.optLong("rToMMs", LegacyHansWireConstants.DEFAULT_R_TO_M_MS);
            long mToF = item.optLong("mToFMs", LegacyHansWireConstants.DEFAULT_M_TO_F_MS);
            int blockedFreeze = item.optInt("blockedFreezeSources", 0);
            int bypassProxy = item.optInt("bypassProxyFlags", 0);
            boolean keepNetwork = item.optBoolean("keepNetwork", false);
            int packetMode = item.optInt("packetWakeMode", LegacyHansWireConstants.WAKE_ALLOW);
            long packetCooldown = item.optLong("packetWakeCooldownMs",
                    LegacyHansWireConstants.DEFAULT_PACKET_COOLDOWN_MS);
            long packetRefreeze = item.optLong("packetRefreezeMs", 0L);
            int alarmMode = item.optInt("alarmWakeMode", LegacyHansWireConstants.WAKE_ALLOW);
            long alarmCooldown = item.optLong("alarmWakeCooldownMs",
                    LegacyHansWireConstants.DEFAULT_ALARM_COOLDOWN_MS);
            long alarmRefreeze = item.optLong("alarmRefreezeMs", 0L);
            int blockedWake = item.optInt("blockedWakeSources", 0);
            validate(packageName, rToM, mToF, blockedFreeze, bypassProxy,
                    packetMode, packetCooldown, packetRefreeze,
                    alarmMode, alarmCooldown, alarmRefreeze, blockedWake);
            normalized.put(packageName, new LegacyHansRule(
                    packageName, enabled, fullExemption, customTiming, rToM, mToF,
                    blockedFreeze, bypassProxy, keepNetwork,
                    packetMode, packetCooldown, packetRefreeze,
                    alarmMode, alarmCooldown, alarmRefreeze, blockedWake));
        }
        return new ArrayList<>(normalized.values());
    }

    private static LegacyHansRule createDefaults(
            String packageName,
            boolean enabled,
            boolean fullExemption,
            boolean customTiming,
            long rToM,
            long mToF) {
        return new LegacyHansRule(
                packageName, enabled, fullExemption, customTiming, rToM, mToF,
                0, 0, false,
                LegacyHansWireConstants.WAKE_ALLOW,
                LegacyHansWireConstants.DEFAULT_PACKET_COOLDOWN_MS, 0L,
                LegacyHansWireConstants.WAKE_ALLOW,
                LegacyHansWireConstants.DEFAULT_ALARM_COOLDOWN_MS, 0L, 0);
    }

    private static void validate(
            String packageName,
            long rToM,
            long mToF,
            int blockedFreeze,
            int bypassProxy,
            int packetMode,
            long packetCooldown,
            long packetRefreeze,
            int alarmMode,
            long alarmCooldown,
            long alarmRefreeze,
            int blockedWake) throws LegacyHansImportException {
        if (packageName == null || !PACKAGE_PATTERN.matcher(packageName).matches()) {
            throw error("invalid_package", "Legacy policy contains an invalid package name");
        }
        requireDelay(rToM, false, "invalid_r_to_m");
        requireDelay(mToF, false, "invalid_m_to_f");
        requireKnownMask(blockedFreeze, LegacyHansWireConstants.ALL_FREEZE_SOURCES,
                "invalid_freeze_sources");
        requireKnownMask(bypassProxy, LegacyHansWireConstants.ALL_PROXY_FLAGS,
                "invalid_proxy_flags");
        requireWakeMode(packetMode, "invalid_packet_mode");
        requireDelay(packetCooldown, false, "invalid_packet_cooldown");
        requireDelay(packetRefreeze, true, "invalid_packet_refreeze");
        requireWakeMode(alarmMode, "invalid_alarm_mode");
        requireDelay(alarmCooldown, false, "invalid_alarm_cooldown");
        requireDelay(alarmRefreeze, true, "invalid_alarm_refreeze");
        requireKnownMask(blockedWake, LegacyHansWireConstants.ALL_WAKE_SOURCES,
                "invalid_wake_sources");
    }

    private static void rejectExcessiveRuleCount(int count) throws LegacyHansImportException {
        if (count > MAX_RULES) {
            throw error("too_many_rules", "Legacy policy contains more than 512 rules");
        }
    }

    private static void requireDelay(long value, boolean allowZero, String code)
            throws LegacyHansImportException {
        if (allowZero && value == 0L) return;
        if (value < MIN_DELAY_MS || value > MAX_DELAY_MS) {
            throw error(code, "Legacy policy contains an out-of-range delay");
        }
    }

    private static void requireWakeMode(int mode, String code)
            throws LegacyHansImportException {
        if (mode < LegacyHansWireConstants.WAKE_ALLOW
                || mode > LegacyHansWireConstants.WAKE_BLOCK) {
            throw error(code, "Legacy policy contains an unknown wake mode");
        }
    }

    private static void requireKnownMask(int value, int knownMask, String code)
            throws LegacyHansImportException {
        if ((value & ~knownMask) != 0) {
            throw error(code, "Legacy policy contains unknown flag bits");
        }
    }

    private static LegacyHansImportException error(String code, String message) {
        return new LegacyHansImportException(code, message);
    }
}
