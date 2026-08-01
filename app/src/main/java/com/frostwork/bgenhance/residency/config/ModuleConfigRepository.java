package com.frostwork.bgenhance.residency.config;

import android.content.Context;
import android.content.SharedPreferences;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyCodec;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidator;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Module-owned configuration repository used before publishing one runtime envelope. */
public final class ModuleConfigRepository {
    private static final ResidencyPolicyCodec POLICY_CODEC = new ResidencyPolicyCodec();
    private static final ResidencyPolicyValidator POLICY_VALIDATOR =
            new ResidencyPolicyValidator();
    private static final RuntimeConfigCodec RUNTIME_CODEC = new RuntimeConfigCodec();

    private ModuleConfigRepository() {}

    public static void ensureInitialized(Context context) {
        Objects.requireNonNull(context, "context");
        SharedPreferences preferences = ModuleSettings.prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        boolean existingRevision = preferences.contains(ModuleSettings.KEY_CONFIG_REVISION);

        for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
            if (!preferences.contains(entry.getKey())) {
                editor.putBoolean(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!preferences.contains(ModuleSettings.KEY_ACTIVE_PRESET)) {
            editor.putString(ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED);
            changed = true;
        }

        boolean addedResidencyPolicy = false;
        if (!preferences.contains(ModuleSettings.KEY_RESIDENCY_POLICY_JSON)) {
            editor.putString(
                    ModuleSettings.KEY_RESIDENCY_POLICY_JSON,
                    POLICY_CODEC.encode(ResidencyPolicyDocument.empty()));
            changed = true;
            addedResidencyPolicy = true;
        }
        if (!preferences.contains(ModuleSettings.KEY_CONFIG_UPDATED_AT)) {
            editor.putLong(ModuleSettings.KEY_CONFIG_UPDATED_AT, 0L);
            changed = true;
        }

        if (!existingRevision) {
            editor.putInt(ModuleSettings.KEY_CONFIG_REVISION, 1);
            changed = true;
        } else if (addedResidencyPolicy) {
            editor.putInt(
                    ModuleSettings.KEY_CONFIG_REVISION,
                    nextConfigRevision(preferences.getInt(ModuleSettings.KEY_CONFIG_REVISION, 1)));
        }
        if (changed && !editor.commit()) {
            throw new IllegalStateException("module configuration initialization failed");
        }
    }

    public static ResidencyPolicyDocument readResidencyPolicy(Context context) {
        Objects.requireNonNull(context, "context");
        SharedPreferences preferences = ModuleSettings.prefs(context);
        String encoded = preferences.getString(ModuleSettings.KEY_RESIDENCY_POLICY_JSON, null);
        if (encoded == null || encoded.isEmpty()) {
            return ResidencyPolicyDocument.empty();
        }
        return POLICY_CODEC.decode(encoded);
    }

    public static RuntimeConfigDocument readRuntimeConfig(Context context) {
        Objects.requireNonNull(context, "context");
        SharedPreferences preferences = ModuleSettings.prefs(context);
        Map<String, Boolean> featureFlags = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : ModuleSettings.defaults().entrySet()) {
            String key = entry.getKey();
            if (ModuleSettings.KEY_MASTER_ENABLE.equals(key)
                    || ModuleSettings.KEY_DRY_RUN.equals(key)
                    || ModuleSettings.KEY_VERBOSE_LOG.equals(key)) {
                continue;
            }
            featureFlags.put(key, preferences.getBoolean(key, entry.getValue()));
        }
        GlobalFeatureConfig global = new GlobalFeatureConfig(
                preferences.getBoolean(
                        ModuleSettings.KEY_MASTER_ENABLE,
                        ModuleSettings.defaultFor(ModuleSettings.KEY_MASTER_ENABLE)),
                preferences.getBoolean(
                        ModuleSettings.KEY_DRY_RUN,
                        ModuleSettings.defaultFor(ModuleSettings.KEY_DRY_RUN)),
                preferences.getBoolean(
                        ModuleSettings.KEY_VERBOSE_LOG,
                        ModuleSettings.defaultFor(ModuleSettings.KEY_VERBOSE_LOG)),
                preferences.getString(
                        ModuleSettings.KEY_ACTIVE_PRESET,
                        ModuleSettings.PRESET_BALANCED),
                featureFlags);
        return new RuntimeConfigDocument(
                RuntimeConfigDocument.DOCUMENT_TYPE,
                RuntimeConfigDocument.CURRENT_SCHEMA_VERSION,
                preferences.getInt(ModuleSettings.KEY_CONFIG_REVISION, 1),
                preferences.getLong(ModuleSettings.KEY_CONFIG_UPDATED_AT, 0L),
                global,
                readResidencyPolicy(context));
    }

    public static String readRuntimeConfigJson(Context context) {
        return RUNTIME_CODEC.encode(readRuntimeConfig(context));
    }

    public static boolean writeResidencyPolicy(
            Context context, ResidencyPolicyDocument candidate) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidate, "candidate");
        SharedPreferences preferences = ModuleSettings.prefs(context);
        ResidencyPolicyDocument current = readResidencyPolicy(context);
        ResidencyPolicyDocument next = nextPolicy(current, candidate);
        if (next == current) return true;

        int nextRevision = nextConfigRevision(
                preferences.getInt(ModuleSettings.KEY_CONFIG_REVISION, 1));
        boolean saved = preferences.edit()
                .putString(ModuleSettings.KEY_RESIDENCY_POLICY_JSON, POLICY_CODEC.encode(next))
                .putInt(ModuleSettings.KEY_CONFIG_REVISION, nextRevision)
                .putLong(ModuleSettings.KEY_CONFIG_UPDATED_AT, System.currentTimeMillis())
                .commit();
        if (saved) ModuleSettings.notifyConfigChanged(context);
        return saved;
    }

    static ResidencyPolicyDocument nextPolicy(
            ResidencyPolicyDocument current, ResidencyPolicyDocument candidate) {
        ResidencyPolicyDocument normalizedCurrent = POLICY_VALIDATOR.validate(
                Objects.requireNonNull(current, "current"));
        ResidencyPolicyDocument normalizedCandidate = POLICY_VALIDATOR.validate(
                Objects.requireNonNull(candidate, "candidate"));
        if (samePolicyContent(normalizedCurrent, normalizedCandidate)) {
            return current;
        }
        if (normalizedCurrent.policyRevision() == Long.MAX_VALUE) {
            throw new IllegalStateException("residency policy revision exhausted");
        }
        return new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                normalizedCurrent.policyRevision() + 1L,
                normalizedCandidate.enabled(),
                normalizedCandidate.defaultNewRuleMode(),
                normalizedCandidate.rules());
    }

    private static boolean samePolicyContent(
            ResidencyPolicyDocument first, ResidencyPolicyDocument second) {
        if (first.enabled() != second.enabled()
                || first.defaultNewRuleMode() != second.defaultNewRuleMode()) {
            return false;
        }
        List<AppResidencyPolicy> firstRules = first.rules();
        List<AppResidencyPolicy> secondRules = second.rules();
        return firstRules.equals(secondRules);
    }

    public static int nextConfigRevision(int current) {
        if (current >= Integer.MAX_VALUE) {
            throw new IllegalStateException("configuration revision exhausted");
        }
        return Math.max(1, current + 1);
    }
}
