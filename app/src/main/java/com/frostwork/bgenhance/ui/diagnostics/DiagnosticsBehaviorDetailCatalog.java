package com.frostwork.bgenhance.ui.diagnostics;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.diagnostics.DiagnosticsBehaviorClassifier;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered user-visible rows shown after opening a diagnostics behavior family. */
public final class DiagnosticsBehaviorDetailCatalog {
    public static final class Entry {
        public final String stableId;
        public final DiagnosticsBehavior behavior;
        public final int titleRes;
        public final int titleFormatRes;
        private final Set<String> featureIds;
        private final String source;
        private final String result;

        private Entry(
                String stableId,
                DiagnosticsBehavior behavior,
                int titleRes,
                int titleFormatRes,
                String source,
                String result,
                String... featureIds) {
            this.stableId = stableId;
            this.behavior = behavior;
            this.titleRes = titleRes;
            this.titleFormatRes = titleFormatRes;
            this.source = source;
            this.result = result;
            LinkedHashSet<String> features = new LinkedHashSet<>();
            if (featureIds != null) {
                Collections.addAll(features, featureIds);
            }
            this.featureIds = Collections.unmodifiableSet(features);
        }

        public Set<String> featureIds() {
            return featureIds;
        }

        boolean matches(DiagnosticsQuery.AggregateRow row) {
            if (row == null || !featureIds.contains(row.featureId)) return false;
            if (source != null && !source.equals(row.source)) return false;
            return result == null || result.equals(row.result);
        }
    }

    private static final EnumMap<DiagnosticsBehavior, List<Entry>> ENTRIES =
            new EnumMap<>(DiagnosticsBehavior.class);

    static {
        ENTRIES.put(DiagnosticsBehavior.KILL, immutable(killEntries()));
        ENTRIES.put(DiagnosticsBehavior.FREEZE, immutable(freezeEntries()));
        ENTRIES.put(DiagnosticsBehavior.WAKE, immutable(wakeEntries()));
        ENTRIES.put(DiagnosticsBehavior.RESOURCE, immutable(resourceEntries()));
        ENTRIES.put(DiagnosticsBehavior.REFREEZE, immutable(refreezeEntries()));
        ENTRIES.put(DiagnosticsBehavior.RECOVERY, immutable(recoveryEntries()));
        ENTRIES.put(DiagnosticsBehavior.UNCLASSIFIED, Collections.emptyList());
        validateCatalog();
    }

    private DiagnosticsBehaviorDetailCatalog() {}

    public static List<Entry> entriesFor(DiagnosticsBehavior behavior) {
        List<Entry> entries = ENTRIES.get(behavior);
        return entries == null ? Collections.emptyList() : entries;
    }

    public static int visibleEntryCount(DiagnosticsBehavior behavior) {
        return Math.max(1, entriesFor(behavior).size());
    }

    public static int fallbackTitleRes(DiagnosticsBehavior behavior) {
        switch (behavior) {
            case KILL: return R.string.diagnostics_detail_other_kill;
            case FREEZE: return R.string.diagnostics_detail_other_freeze;
            case WAKE: return R.string.diagnostics_detail_other_wake;
            case RESOURCE: return R.string.diagnostics_detail_other_resource;
            case REFREEZE: return R.string.diagnostics_detail_other_refreeze;
            case RECOVERY: return R.string.diagnostics_detail_other_recovery;
            case UNCLASSIFIED:
            default:
                return R.string.diagnostics_detail_unclassified;
        }
    }

    private static List<Entry> killEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(feature("athena_periodic", DiagnosticsBehavior.KILL,
                R.string.periodic_title, FeatureIds.ATHENA_PERIODIC));
        entries.add(feature("oguard_kill", DiagnosticsBehavior.KILL,
                R.string.oguard_kill_title, FeatureIds.OGUARD_KILL));
        entries.add(feature("oguard_force_stop", DiagnosticsBehavior.KILL,
                R.string.oguard_stop_title, FeatureIds.OGUARD_FORCE_STOP));
        entries.add(feature("fast_camera_start", DiagnosticsBehavior.KILL,
                R.string.fast_camera_start_title, FeatureIds.FAST_CAMERA_START));
        entries.add(feature("fast_camera_operation", DiagnosticsBehavior.KILL,
                R.string.fast_camera_operation_title, FeatureIds.FAST_CAMERA_OPERATION));
        entries.add(feature("fast_game_start", DiagnosticsBehavior.KILL,
                R.string.fast_game_start_title, FeatureIds.FAST_GAME_START));
        entries.add(feature("fast_ai", DiagnosticsBehavior.KILL,
                R.string.fast_ai_title, FeatureIds.FAST_AI));
        entries.add(feature("fast_other", DiagnosticsBehavior.KILL,
                R.string.fast_other_title, FeatureIds.FAST_CLEAR_OTHER));
        entries.add(feature("tournament", DiagnosticsBehavior.KILL,
                R.string.tournament_title, FeatureIds.TOURNAMENT));
        entries.add(feature("memory_force", DiagnosticsBehavior.KILL,
                R.string.memory_force_title, FeatureIds.MEMORY_FORCE));
        entries.add(feature("memory_over", DiagnosticsBehavior.KILL,
                R.string.memory_over_title, FeatureIds.MEMORY_OVER));
        entries.add(feature("memory_other", DiagnosticsBehavior.KILL,
                R.string.memory_other_title, FeatureIds.MEMORY_GUARD_OTHER));
        entries.add(feature("athena_other", DiagnosticsBehavior.KILL,
                R.string.athena_other_title, FeatureIds.ATHENA_OTHER));
        entries.add(feature("nirvana", DiagnosticsBehavior.KILL,
                R.string.nirvana_title, FeatureIds.NIRVANA));
        entries.add(feature("camera_low_free", DiagnosticsBehavior.KILL,
                R.string.camera_low_free_title, FeatureIds.CAMERA_LOW_FREE));
        entries.add(feature("game_low_free", DiagnosticsBehavior.KILL,
                R.string.game_low_free_title, FeatureIds.GAME_LOW_FREE));
        entries.add(feature("osense_process", DiagnosticsBehavior.KILL,
                R.string.osense_per_process_title, FeatureIds.OSENSE_PER_PROCESS));
        return entries;
    }

    private static List<Entry> freezeEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(featureSource("freeze_normal", DiagnosticsBehavior.FREEZE,
                R.string.diagnostics_detail_freeze_normal,
                FeatureIds.HANS, FreezeSource.NORMAL.name()));
        entries.add(featureSource("freeze_fast", DiagnosticsBehavior.FREEZE,
                R.string.diagnostics_detail_freeze_fast,
                FeatureIds.HANS, FreezeSource.FAST.name()));
        entries.add(featureSource("freeze_super", DiagnosticsBehavior.FREEZE,
                R.string.diagnostics_detail_freeze_super,
                FeatureIds.SUPER_FREEZE, FreezeSource.SUPER.name()));
        entries.add(featureSource("freeze_preload", DiagnosticsBehavior.FREEZE,
                R.string.diagnostics_detail_freeze_preload,
                FeatureIds.HANS, FreezeSource.PRELOAD.name()));
        entries.add(featureSource("freeze_timing", DiagnosticsBehavior.FREEZE,
                R.string.hans_r_to_m_timing_support_title,
                FeatureIds.HANS_FREEZE_TIMING, "R_TO_M"));
        entries.add(featureSource("freeze_execute_timing", DiagnosticsBehavior.FREEZE,
                R.string.diagnostics_detail_freeze_execute_timing,
                FeatureIds.HANS_FREEZE_TIMING, "M_TO_F"));
        entries.add(featureSource("freeze_lcd_exemption", DiagnosticsBehavior.FREEZE,
                R.string.hans_lcd_on_exemption_support_title,
                FeatureIds.HANS_FREEZE_EXEMPTION, "LCD_ON"));
        return entries;
    }

    private static List<Entry> wakeEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        for (WakeSource source : WakeSource.values()) {
            if (source == WakeSource.INTERNAL_OPERATION) continue;
            entries.add(featureSource(
                    "wake_" + source.name().toLowerCase(java.util.Locale.ROOT),
                    DiagnosticsBehavior.WAKE,
                    wakeSourceTitle(source),
                    FeatureIds.HANS_WAKE,
                    source.name()));
        }
        return entries;
    }

    private static List<Entry> resourceEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(featureSource("resource_network", DiagnosticsBehavior.RESOURCE,
                R.string.diagnostics_detail_resource_network,
                FeatureIds.HANS_RESOURCE, "NETWORK"));
        for (ResourceType resource : ResourceType.values()) {
            entries.add(featureSource(
                    "resource_" + resource.name().toLowerCase(java.util.Locale.ROOT),
                    DiagnosticsBehavior.RESOURCE,
                    resourceTitle(resource),
                    FeatureIds.HANS_RESOURCE,
                    resource.name()));
        }
        return entries;
    }

    private static List<Entry> refreezeEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        for (WakeSource source : WakeSource.values()) {
            if (source == WakeSource.INTERNAL_OPERATION) continue;
            entries.add(featureSource(
                    "refreeze_" + source.name().toLowerCase(java.util.Locale.ROOT),
                    DiagnosticsBehavior.REFREEZE,
                    wakeSourceTitle(source),
                    R.string.diagnostics_detail_refreeze_format,
                    FeatureIds.HANS_REFREEZE,
                    source.name()));
        }
        return entries;
    }

    private static List<Entry> recoveryEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(featureResult("recovery_completed", DiagnosticsBehavior.RECOVERY,
                R.string.diagnostics_detail_recovery_completed,
                FeatureIds.HANS_RECOVERY, DiagnosticsResult.RECOVERED));
        entries.add(featureResult("recovery_not_needed", DiagnosticsBehavior.RECOVERY,
                R.string.diagnostics_detail_recovery_not_needed,
                FeatureIds.HANS_RECOVERY, DiagnosticsResult.NO_ACTION_NEEDED));
        entries.add(featureResult("recovery_safety_aborted", DiagnosticsBehavior.RECOVERY,
                R.string.diagnostics_detail_recovery_safety_aborted,
                FeatureIds.HANS_RECOVERY, DiagnosticsResult.SAFETY_ABORTED));
        entries.add(featureResult("recovery_failed", DiagnosticsBehavior.RECOVERY,
                R.string.diagnostics_detail_recovery_failed,
                FeatureIds.HANS_RECOVERY, DiagnosticsResult.RUNTIME_FAIL_OPEN));
        return entries;
    }

    private static Entry feature(
            String stableId,
            DiagnosticsBehavior behavior,
            int titleRes,
            String... featureIds) {
        return new Entry(stableId, behavior, titleRes, 0, null, null, featureIds);
    }

    private static Entry featureSource(
            String stableId,
            DiagnosticsBehavior behavior,
            int titleRes,
            String featureId,
            String source) {
        return featureSource(stableId, behavior, titleRes, 0, featureId, source);
    }

    private static Entry featureSource(
            String stableId,
            DiagnosticsBehavior behavior,
            int titleRes,
            int titleFormatRes,
            String featureId,
            String source) {
        return new Entry(
                stableId, behavior, titleRes, titleFormatRes, source, null, featureId);
    }

    private static Entry featureResult(
            String stableId,
            DiagnosticsBehavior behavior,
            int titleRes,
            String featureId,
            String result) {
        return new Entry(stableId, behavior, titleRes, 0, null, result, featureId);
    }

    private static int wakeSourceTitle(WakeSource source) {
        switch (source) {
            case PACKET: return R.string.diagnostics_source_packet;
            case ALARM: return R.string.diagnostics_source_alarm;
            case ASYNC_BINDER: return R.string.diagnostics_source_async_binder;
            case SYNC_BINDER: return R.string.diagnostics_source_sync_binder;
            case TRANSACTION_BINDER: return R.string.diagnostics_source_transaction_binder;
            case SIGNAL: return R.string.diagnostics_source_signal;
            case ACTIVITY_INPUT: return R.string.diagnostics_source_activity_input;
            case SERVICE: return R.string.diagnostics_source_service;
            case BROADCAST: return R.string.diagnostics_source_broadcast;
            case PROVIDER: return R.string.diagnostics_source_provider;
            case JOB_SYNC: return R.string.diagnostics_source_job;
            case WAKE_LOCK: return R.string.diagnostics_source_wake_lock;
            case AUDIO_MEDIA: return R.string.diagnostics_source_audio;
            case CONNECTIVITY: return R.string.diagnostics_source_connectivity;
            case SYSTEM_SCENE: return R.string.diagnostics_source_system_scene;
            case INTERNAL_OPERATION: return R.string.diagnostics_source_internal;
            case OTHER:
            default:
                return R.string.diagnostics_source_other;
        }
    }

    private static int resourceTitle(ResourceType resource) {
        switch (resource) {
            case SERVICE: return R.string.diagnostics_detail_resource_service;
            case BROADCAST: return R.string.diagnostics_detail_resource_broadcast;
            case JOB: return R.string.diagnostics_detail_resource_job;
            case SENSOR: return R.string.diagnostics_detail_resource_sensor;
            case BINDER: return R.string.diagnostics_detail_resource_binder;
            case ALARM: return R.string.diagnostics_detail_resource_alarm;
            case WAKE_LOCK: return R.string.diagnostics_detail_resource_wake_lock;
            case GPS: return R.string.diagnostics_detail_resource_gps;
            case AUDIO: return R.string.diagnostics_detail_resource_audio;
            case BLUETOOTH_SCAN:
            default:
                return R.string.diagnostics_detail_resource_bluetooth;
        }
    }

    private static void validateCatalog() {
        LinkedHashSet<String> stableIds = new LinkedHashSet<>();
        LinkedHashSet<String> matchKeys = new LinkedHashSet<>();
        LinkedHashSet<String> coveredFeatures = new LinkedHashSet<>();
        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            for (Entry entry : entriesFor(behavior)) {
                if (!stableIds.add(behavior.wireId + ":" + entry.stableId)) {
                    throw new IllegalStateException("duplicate behavior detail: " + entry.stableId);
                }
                for (String featureId : entry.featureIds) {
                    if (!behavior.wireId.equals(
                            DiagnosticsBehaviorClassifier.forFeatureId(featureId))) {
                        throw new IllegalStateException(
                                "behavior detail family mismatch: " + featureId);
                    }
                    String matchKey = behavior.wireId + '\n' + featureId + '\n'
                            + (entry.source == null ? "*" : entry.source) + '\n'
                            + (entry.result == null ? "*" : entry.result);
                    if (!matchKeys.add(matchKey)) {
                        throw new IllegalStateException(
                                "duplicate behavior detail match: " + matchKey);
                    }
                    coveredFeatures.add(featureId);
                }
            }
        }
        LinkedHashSet<String> expectedFeatures = new LinkedHashSet<>(FeatureIds.all());
        if (!coveredFeatures.equals(expectedFeatures)) {
            throw new IllegalStateException(
                    "behavior detail catalog does not cover every implemented feature");
        }
    }

    private static List<Entry> immutable(List<Entry> entries) {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
