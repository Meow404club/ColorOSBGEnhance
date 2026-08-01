package com.frostwork.bgenhance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import com.frostwork.bgenhance.residency.config.ModuleConfigRepository;
import com.frostwork.bgenhance.residency.config.RuntimeConfigDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared setting keys and the module-owned configuration store. */
public final class ModuleSettings {
    private ModuleSettings() {}

    public static final String TAG = "ColorOSBgEnhance";
    public static final String PREFS_NAME = "settings";
    public static final String KEY_CONFIG_REVISION = "internal.config_revision";
    public static final String KEY_CONFIG_UPDATED_AT = "internal.config_updated_at";
    public static final String KEY_RESIDENCY_POLICY_JSON = "residency.policy_json";
    public static final String KEY_RUNTIME_CONFIG_JSON = "runtime.config_json";

    public static final String KEY_MASTER_ENABLE = "master_enable";
    public static final String KEY_DRY_RUN = "dry_run";
    public static final String KEY_VERBOSE_LOG = "verbose_log";
    public static final String KEY_ACTIVE_PRESET = "active_preset";

    public static final String KEY_PERIODIC = "block_athena_periodic_deep_clear";
    public static final String KEY_OGUARD_KILL = "block_oguard_kill";
    public static final String KEY_OGUARD_FORCE_STOP = "block_oguard_force_stop";
    public static final String KEY_FAST_CAMERA_START = "block_fast_clear_camera_startup";
    public static final String KEY_FAST_CAMERA_OPERATION = "block_fast_clear_camera_operation";
    public static final String KEY_FAST_GAME_START = "block_fast_clear_game_startup";
    public static final String KEY_FAST_AI = "block_fast_clear_ai_scene";
    public static final String KEY_TOURNAMENT = "block_game_tournament_clear";
    public static final String KEY_MEMORY_FORCE = "block_memory_guard_force_speed";
    public static final String KEY_MEMORY_OVER = "block_memory_guard_over_speed";
    public static final String KEY_NIRVANA = "block_nirvana_group_clean";
    public static final String KEY_CAMERA_LOW_FREE = "block_camera_low_free_group_clean";
    public static final String KEY_GAME_LOW_FREE = "block_game_low_free_group_clean";
    public static final String KEY_HANS = "block_hans_background_freeze";
    public static final String KEY_SUPER_FREEZE = "block_super_freeze";
    public static final String KEY_ANDROID_FREEZE = "block_android_freeze";

    public static final String PRESET_BALANCED = "balanced";
    public static final String PRESET_STRONG = "strong";
    public static final String PRESET_CUSTOM = "custom";

    private static final Map<String, Boolean> DEFAULTS;
    static {
        LinkedHashMap<String, Boolean> map = new LinkedHashMap<>();
        map.put(KEY_MASTER_ENABLE, true);
        map.put(KEY_DRY_RUN, false);
        map.put(KEY_VERBOSE_LOG, false);
        map.put(KEY_PERIODIC, true);
        map.put(KEY_OGUARD_KILL, true);
        map.put(KEY_OGUARD_FORCE_STOP, true);
        map.put(KEY_FAST_CAMERA_START, false);
        map.put(KEY_FAST_CAMERA_OPERATION, false);
        map.put(KEY_FAST_GAME_START, false);
        map.put(KEY_FAST_AI, false);
        map.put(KEY_TOURNAMENT, false);
        map.put(KEY_MEMORY_FORCE, false);
        map.put(KEY_MEMORY_OVER, false);
        map.put(KEY_NIRVANA, false);
        map.put(KEY_CAMERA_LOW_FREE, false);
        map.put(KEY_GAME_LOW_FREE, false);
        map.put(KEY_HANS, false);
        map.put(KEY_SUPER_FREEZE, false);
        map.put(KEY_ANDROID_FREEZE, false);
        DEFAULTS = Collections.unmodifiableMap(map);
    }

    public static Map<String, Boolean> defaults() {
        return DEFAULTS;
    }

    public static boolean defaultFor(String key) {
        Boolean value = DEFAULTS.get(key);
        return value != null && value;
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized void ensureDefaults(Context context) {
        if (context == null) return;
        ModuleConfigRepository.ensureInitialized(context);
    }

    public static boolean getLocalBoolean(Context context, String key) {
        if (context == null) return defaultFor(key);
        return prefs(context).getBoolean(key, defaultFor(key));
    }

    public static String getLocalString(Context context, String key, String defValue) {
        if (context == null) return defValue;
        String value = prefs(context).getString(key, defValue);
        return TextUtils.isEmpty(value) ? defValue : value;
    }

    public static int getConfigRevision(Context context) {
        if (context == null) return -1;
        return prefs(context).getInt(KEY_CONFIG_REVISION, 1);
    }

    public static synchronized ResidencyPolicyDocument getResidencyPolicy(Context context) {
        if (context == null) return ResidencyPolicyDocument.empty();
        return ModuleConfigRepository.readResidencyPolicy(context);
    }

    public static synchronized RuntimeConfigDocument getRuntimeConfig(Context context) {
        if (context == null) return RuntimeConfigDocument.empty();
        return ModuleConfigRepository.readRuntimeConfig(context);
    }

    public static synchronized String getRuntimeConfigJson(Context context) {
        if (context == null) return "";
        return ModuleConfigRepository.readRuntimeConfigJson(context);
    }

    public static synchronized boolean setResidencyPolicy(
            Context context, ResidencyPolicyDocument policy) {
        if (context == null || policy == null) return false;
        return ModuleConfigRepository.writeResidencyPolicy(context, policy);
    }

    public static synchronized boolean setBoolean(Context context, String key, boolean value) {
        if (context == null || !DEFAULTS.containsKey(key)) return false;
        SharedPreferences sp = prefs(context);
        if (sp.contains(key) && sp.getBoolean(key, defaultFor(key)) == value) return true;
        int nextRevision = ModuleConfigRepository.nextConfigRevision(
                sp.getInt(KEY_CONFIG_REVISION, 1));
        boolean saved = sp.edit().putBoolean(key, value)
                .putInt(KEY_CONFIG_REVISION, nextRevision)
                .putLong(KEY_CONFIG_UPDATED_AT, System.currentTimeMillis()).commit();
        if (saved) notifyConfigChanged(context);
        return saved;
    }

    public static synchronized boolean setString(Context context, String key, String value) {
        if (context == null || !KEY_ACTIVE_PRESET.equals(key)) return false;
        SharedPreferences sp = prefs(context);
        String current = sp.getString(key, PRESET_BALANCED);
        if (String.valueOf(value).equals(current)) return true;
        int nextRevision = ModuleConfigRepository.nextConfigRevision(
                sp.getInt(KEY_CONFIG_REVISION, 1));
        boolean saved = sp.edit().putString(key, value)
                .putInt(KEY_CONFIG_REVISION, nextRevision)
                .putLong(KEY_CONFIG_UPDATED_AT, System.currentTimeMillis()).commit();
        if (saved) notifyConfigChanged(context);
        return saved;
    }

    public static synchronized boolean setValues(Context context, Map<String, Boolean> values,
                                                 String activePreset) {
        if (context == null) return false;
        SharedPreferences sp = prefs(context);
        SharedPreferences.Editor editor = sp.edit();
        if (values != null) {
            for (Map.Entry<String, Boolean> entry : values.entrySet()) {
                if (DEFAULTS.containsKey(entry.getKey()) && entry.getValue() != null) {
                    editor.putBoolean(entry.getKey(), entry.getValue());
                }
            }
        }
        if (!TextUtils.isEmpty(activePreset)) editor.putString(KEY_ACTIVE_PRESET, activePreset);
        editor.putInt(KEY_CONFIG_REVISION, ModuleConfigRepository.nextConfigRevision(
                sp.getInt(KEY_CONFIG_REVISION, 1)));
        editor.putLong(KEY_CONFIG_UPDATED_AT, System.currentTimeMillis());
        boolean saved = editor.commit();
        if (saved) notifyConfigChanged(context);
        return saved;
    }

    public static void notifyConfigChanged(Context context) {
        if (context == null) return;
        ModuleApplication.requestConfigSync(context);
    }

    public static boolean isDiagnosticsBridgeAvailable(Context context) {
        if (context == null) return false;
        try {
            Bundle out = context.getContentResolver().call(
                    BridgeContract.URI, BridgeContract.METHOD_PING, null, null);
            return out != null
                    && out.getBoolean(BridgeContract.EXTRA_OK, false)
                    && out.getInt(BridgeContract.EXTRA_BRIDGE_VERSION, -1)
                    == BridgeContract.BRIDGE_VERSION;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "bridge ping failed", t);
            return false;
        }
    }
}
