package com.frostwork.bgenhance.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsStore;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public final class DiagnosticsActivity extends AppCompatActivity {
    private LinearLayout container;
    private final ModuleApplication.StateListener moduleStateListener =
            () -> runOnUiThread(this::render);

    private static final class SupportItem {
        final int titleRes;
        final String hookTargetId;
        SupportItem(int titleRes, String hookTargetId) {
            this.titleRes = titleRes;
            this.hookTargetId = hookTargetId;
        }
    }

    private static final class StatItem {
        final int titleRes;
        final String featureId;
        final boolean diagnosticOnly;

        StatItem(int titleRes, String featureId) {
            this(titleRes, featureId, false);
        }

        StatItem(int titleRes, String featureId, boolean diagnosticOnly) {
            this.titleRes = titleRes;
            this.featureId = featureId;
            this.diagnosticOnly = diagnosticOnly;
        }
    }

    private static final class StatGroup {
        final int titleRes;
        final List<StatItem> items;

        StatGroup(int titleRes, StatItem... items) {
            this.titleRes = titleRes;
            this.items = Arrays.asList(items);
        }
    }

    private static final List<SupportItem> SUPPORTS = Arrays.asList(
            new SupportItem(R.string.periodic_title, HookTargetIds.ATHENA_PERIODIC),
            new SupportItem(R.string.oguard_kill_title, HookTargetIds.OGUARD_KILL),
            new SupportItem(R.string.oguard_stop_title, HookTargetIds.OGUARD_FORCE_STOP),
            new SupportItem(R.string.fast_clear_nav_title, HookTargetIds.FAST_CLEAR_DISPATCH),
            new SupportItem(R.string.tournament_title, HookTargetIds.TOURNAMENT),
            new SupportItem(R.string.memory_nav_title, HookTargetIds.MEMORY_GUARD_DISPATCH),
            new SupportItem(R.string.nirvana_nav_title, HookTargetIds.OSENSE_GROUP_CLEAN),
            new SupportItem(R.string.osense_per_process_title,
                    HookTargetIds.OSENSE_PROCESS_KILL),
            new SupportItem(R.string.hans_r_to_m_timing_support_title,
                    HookTargetIds.HANS_R_TO_M_TIMING),
            new SupportItem(R.string.hans_m_to_f_timing_support_title,
                    HookTargetIds.HANS_M_TO_F_TIMING),
            new SupportItem(R.string.freeze_nav_title, HookTargetIds.HANS_FREEZE_DISPATCH)
    );


    private static final List<StatGroup> STAT_GROUPS = Arrays.asList(
            new StatGroup(R.string.stats_group_basic,
                    new StatItem(R.string.periodic_title, FeatureIds.ATHENA_PERIODIC),
                    new StatItem(R.string.oguard_kill_title, FeatureIds.OGUARD_KILL),
                    new StatItem(R.string.oguard_stop_title, FeatureIds.OGUARD_FORCE_STOP)),
            new StatGroup(R.string.stats_group_scene,
                    new StatItem(R.string.fast_camera_start_title, FeatureIds.FAST_CAMERA_START),
                    new StatItem(R.string.fast_camera_operation_title, FeatureIds.FAST_CAMERA_OPERATION),
                    new StatItem(R.string.fast_game_start_title, FeatureIds.FAST_GAME_START),
                    new StatItem(R.string.fast_ai_title, FeatureIds.FAST_AI),
                    new StatItem(R.string.tournament_title, FeatureIds.TOURNAMENT)),
            new StatGroup(R.string.stats_group_memory,
                    new StatItem(R.string.memory_force_title, FeatureIds.MEMORY_FORCE),
                    new StatItem(R.string.memory_over_title, FeatureIds.MEMORY_OVER)),
            new StatGroup(R.string.stats_group_nirvana,
                    new StatItem(R.string.nirvana_title, FeatureIds.NIRVANA),
                    new StatItem(R.string.camera_low_free_title, FeatureIds.CAMERA_LOW_FREE),
                    new StatItem(R.string.game_low_free_title, FeatureIds.GAME_LOW_FREE),
                    new StatItem(R.string.osense_per_process_title,
                            FeatureIds.OSENSE_PER_PROCESS)),
            new StatGroup(R.string.stats_group_freeze,
                    new StatItem(R.string.hans_title, FeatureIds.HANS),
                    new StatItem(R.string.super_freeze_title, FeatureIds.SUPER_FREEZE),
                    new StatItem(R.string.android_freeze_title, FeatureIds.ANDROID_FREEZE, true))
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        UiUtils.applyWindowStyle(this);
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        container = findViewById(R.id.diagnostics_container);
        findViewById(R.id.button_refresh_diagnostics).setOnClickListener(v -> refreshDiagnostics());
        findViewById(R.id.button_copy_debug).setOnClickListener(v -> copyDebug());
        findViewById(R.id.button_reset_stats).setOnClickListener(v -> confirmReset());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addStateListener(moduleStateListener);
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeStateListener(moduleStateListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void refreshDiagnostics() {
        ModuleSettings.notifyConfigChanged(this);
        Toast.makeText(this, R.string.diagnostics_refresh_sent, Toast.LENGTH_SHORT).show();
        container.postDelayed(this::render, 1_200L);
    }

    private void render() {
        if (container == null) return;
        container.removeAllViews();
        int configRevision = ModuleSettings.getConfigRevision(this);

        addSectionTitle(R.string.diagnostics_chain_title);
        MaterialCardView chainCard = newCard();
        LinearLayout chainRows = vertical();
        chainCard.addView(chainRows);
        addStatusRow(chainRows, getString(R.string.config_sync_title), localConfigStatus());
        addDivider(chainRows);
        addStatusRow(chainRows, getString(R.string.diagnostics_bridge_title), diagnosticsBridgeStatus());
        addDivider(chainRows);
        DiagnosticsStore.ProcessSnapshot system = DiagnosticsStore.getProcess(
                this, BridgeContract.PROCESS_SYSTEM_SERVER);
        addStatusRow(chainRows, getString(R.string.diagnostics_system_process),
                friendlyProcessStatus(system, configRevision, R.string.diagnostics_system_missing));
        addDivider(chainRows);
        DiagnosticsStore.ProcessSnapshot athena = DiagnosticsStore.getProcess(
                this, BridgeContract.PROCESS_ATHENA);
        addStatusRow(chainRows, getString(R.string.diagnostics_athena_process),
                friendlyProcessStatus(athena, configRevision, R.string.diagnostics_athena_missing));
        container.addView(chainCard, cardParams());

        addSectionTitle(R.string.support_status_title);
        MaterialCardView supportCard = newCard();
        LinearLayout supportRows = vertical();
        supportCard.addView(supportRows);
        for (int i = 0; i < SUPPORTS.size(); i++) {
            SupportItem item = SUPPORTS.get(i);
            String status = DiagnosticsStore.getSupportStatus(this, item.hookTargetId);
            long updatedAt = DiagnosticsStore.getSupportUpdatedAt(this, item.hookTargetId);
            addStatusRow(supportRows, getString(item.titleRes),
                    friendlySupportStatus(status, updatedAt));
            if (i < SUPPORTS.size() - 1) addDivider(supportRows);
        }
        container.addView(supportCard, cardParams());

        renderStats();

        addSectionTitle(R.string.verbose_title);
        MaterialCardView advancedCard = newCard();
        LinearLayout advanced = vertical();
        advancedCard.addView(advanced);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiUtils.dp(this, 20), UiUtils.dp(this, 14),
                UiUtils.dp(this, 16), UiUtils.dp(this, 14));
        row.setMinimumHeight(UiUtils.dp(this, 88));
        LinearLayout text = vertical();
        TextView title = title(getString(R.string.verbose_title));
        TextView summary = summary(getString(R.string.verbose_summary));
        text.addView(title);
        text.addView(summary);
        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch verbose = new MaterialSwitch(this);
        verbose.setChecked(ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_VERBOSE_LOG));
        verbose.setOnCheckedChangeListener((button, checked) ->
                ModuleSettings.setBoolean(this, ModuleSettings.KEY_VERBOSE_LOG, checked));
        row.setOnClickListener(v -> verbose.toggle());
        row.addView(verbose);
        advanced.addView(row);
        container.addView(advancedCard, cardParams());
    }

    private void renderStats() {
        for (StatGroup group : STAT_GROUPS) {
            addStatsGroup(group);
        }
    }

    private void addStatsGroup(StatGroup group) {
        int groupObserved = 0;
        int groupBlocked = 0;
        int groupFailOpen = 0;
        for (StatItem item : group.items) {
            groupObserved += DiagnosticsStore.getStatInt(this, item.featureId, "observed");
            groupBlocked += DiagnosticsStore.getStatInt(this, item.featureId, "blocked");
            groupFailOpen += DiagnosticsStore.getStatInt(this, item.featureId, "fail_open");
        }

        MaterialCardView card = newCard();
        LinearLayout content = vertical();
        card.addView(content);
        addStatsGroupHeader(content, getString(group.titleRes),
                groupObserved, groupBlocked, groupFailOpen);
        addDivider(content);
        for (int i = 0; i < group.items.size(); i++) {
            addStatRow(content, group.items.get(i));
            if (i < group.items.size() - 1) addDivider(content);
        }

        LinearLayout.LayoutParams params = cardParams();
        params.topMargin = UiUtils.dp(this, 12);
        container.addView(card, params);
    }

    private void addStatsGroupHeader(LinearLayout parent, String groupTitle,
                                     int observed, int blocked, int failOpen) {
        LinearLayout header = vertical();
        header.setPadding(UiUtils.dp(this, 20), UiUtils.dp(this, 14),
                UiUtils.dp(this, 20), UiUtils.dp(this, 12));

        TextView title = new TextView(this);
        title.setText(groupTitle);
        title.setTextColor(getColor(R.color.item_title));
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title);

        String counts = getString(R.string.stats_count_summary, observed, blocked);
        if (failOpen > 0) {
            counts += " · " + getString(R.string.stats_fail_open_short, failOpen);
        }
        TextView summary = summary(counts);
        summary.setTextSize(12.5f);
        header.addView(summary);
        parent.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addStatRow(LinearLayout parent, StatItem item) {
        LinearLayout row = vertical();
        row.setPadding(UiUtils.dp(this, 20), UiUtils.dp(this, 12),
                UiUtils.dp(this, 20), UiUtils.dp(this, 12));
        row.setMinimumHeight(UiUtils.dp(this, 64));

        TextView title = new TextView(this);
        title.setText(item.titleRes);
        title.setTextColor(getColor(R.color.item_title));
        title.setTextSize(15.5f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(title);

        int observed = DiagnosticsStore.getStatInt(this, item.featureId, "observed");
        int blocked = DiagnosticsStore.getStatInt(this, item.featureId, "blocked");
        int failOpen = DiagnosticsStore.getStatInt(this, item.featureId, "fail_open");
        StringBuilder details = new StringBuilder(
                getString(R.string.stats_count_summary, observed, blocked));
        if (failOpen > 0) {
            details.append(" · ").append(getString(R.string.stats_fail_open_short, failOpen));
        }
        if (item.diagnosticOnly) {
            details.append('\n').append(getString(R.string.stats_diagnostic_only));
        } else {
            long lastAt = DiagnosticsStore.getStatLong(this, item.featureId, "last_at");
            String lastResult = DiagnosticsStore.getStatString(
                    this, item.featureId, "last_result");
            String lastPkg = DiagnosticsStore.getStatString(
                    this, item.featureId, "last_pkg");
            if (lastAt > 0) {
                details.append('\n').append(getString(R.string.stats_recent,
                        formatTime(lastAt), friendlyResult(lastResult)));
            }
            if (!TextUtils.isEmpty(lastPkg)) {
                details.append('\n').append(getString(R.string.stats_last_app, lastPkg));
            }
        }

        TextView summary = summary(details.toString());
        summary.setTextSize(12.5f);
        row.addView(summary);
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private String localConfigStatus() {
        int localRevision = ModuleSettings.getConfigRevision(this);
        if (!ModuleApplication.isServiceConnected()) {
            return getString(R.string.config_sync_waiting_service, localRevision);
        }
        int syncedRevision = ModuleApplication.syncedRevision();
        if (ModuleApplication.isConfigSynced(this)) {
            return getString(R.string.config_sync_ready, localRevision);
        }
        if (!TextUtils.isEmpty(ModuleApplication.syncError())) {
            return getString(R.string.config_sync_failed, localRevision);
        }
        return getString(R.string.config_sync_pending, localRevision, syncedRevision);
    }

    private String diagnosticsBridgeStatus() {
        return getString(ModuleSettings.isDiagnosticsBridgeAvailable(this)
                ? R.string.diagnostics_bridge_ready : R.string.diagnostics_bridge_unavailable);
    }

    private String friendlyProcessStatus(DiagnosticsStore.ProcessSnapshot snapshot,
                                         int configRevision, int missingRes) {
        if (!snapshot.isCurrentBoot(this)) return getString(missingRes);
        if ("BOOTSTRAP_FAILED".equals(snapshot.stage)) {
            return getString(R.string.diagnostics_process_failed,
                    TextUtils.isEmpty(snapshot.error) ? getString(R.string.result_unknown_short)
                            : snapshot.error);
        }
        if ("CONFIG_ERROR".equals(snapshot.stage)) {
            return getString(R.string.diagnostics_process_config_error,
                    TextUtils.isEmpty(snapshot.error) ? getString(R.string.result_unknown_short)
                            : snapshot.error);
        }
        String value;
        if (snapshot.configRevision < 0) {
            value = getString(R.string.diagnostics_process_waiting_config);
        } else if (snapshot.configRevision < configRevision) {
            value = getString(R.string.diagnostics_process_config_pending,
                    snapshot.configRevision, configRevision);
        } else {
            value = getString(R.string.diagnostics_process_ready, snapshot.configRevision);
        }
        long time = snapshot.configLoadedAt > 0 ? snapshot.configLoadedAt : snapshot.loadedAt;
        if (time > 0) value += "\n" + getString(R.string.support_last_updated, formatTime(time));
        return value;
    }

    private String friendlySupportStatus(String status, long updatedAt) {
        String value;
        if (status == null || "UNKNOWN".equals(status)) {
            value = getString(R.string.support_unknown);
        } else if (status.startsWith("INSTALLED")) {
            value = getString(R.string.support_available);
        } else if (status.startsWith("UNSUPPORTED")) {
            value = getString(R.string.support_unavailable);
        } else if (status.startsWith("FAILED")) {
            value = getString(R.string.support_failed);
        } else {
            value = getString(R.string.support_unknown);
        }
        if (updatedAt > 0 && status != null && !"UNKNOWN".equals(status)) {
            value += "\n" + getString(R.string.support_last_updated, formatTime(updatedAt));
        }
        return value;
    }

    private String friendlyResult(String result) {
        if ("blocked".equals(result)) return getString(R.string.result_blocked);
        if ("observed".equals(result)) return getString(R.string.result_observed);
        if ("passed".equals(result)) return getString(R.string.result_passed);
        if ("fail_open".equals(result)) return getString(R.string.result_fail_open);
        if ("config_unavailable".equals(result)) return getString(R.string.result_config_unavailable);
        return getString(R.string.result_unknown, result);
    }

    private void copyDebug() {
        StringBuilder out = new StringBuilder();
        out.append("ColorOS后台增强 Hook 诊断\n");
        out.append("package=").append(getPackageName()).append('\n');
        out.append("version=").append(appVersion()).append('\n');
        out.append("android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        out.append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        out.append("hookApi=modern-libxposed-api-102 config=remote_preferences minApi=101\n");
        out.append("bootCount=").append(DiagnosticsStore.currentBootCount(this)).append('\n');
        out.append("xposedServiceConnected=").append(ModuleApplication.isServiceConnected()).append('\n');
        out.append("xposedServiceApi=").append(ModuleApplication.serviceApiVersion()).append('\n');
        out.append("remoteConfigRevision=").append(ModuleApplication.syncedRevision()).append('\n');
        out.append("remoteConfigError=").append(ModuleApplication.syncError()).append('\n');
        out.append("diagnosticsChannelAvailable=").append(ModuleSettings.isDiagnosticsBridgeAvailable(this)).append('\n');
        out.append("bridgeVersion=").append(BridgeContract.BRIDGE_VERSION).append('\n');
        out.append("configRevision=").append(ModuleSettings.getConfigRevision(this)).append('\n');
        out.append("activePreset=").append(ModuleSettings.getLocalString(
                this, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED)).append('\n');

        out.append("\n[process]\n");
        appendProcess(out, BridgeContract.PROCESS_SYSTEM_SERVER);
        appendProcess(out, BridgeContract.PROCESS_ATHENA);

        out.append("\n[settings]\n");
        for (String key : ModuleSettings.defaults().keySet()) {
            out.append(key).append('=').append(ModuleSettings.getLocalBoolean(this, key)).append('\n');
        }

        out.append("\n[support]\n");
        for (SupportItem item : SUPPORTS) {
            out.append(item.hookTargetId).append('=').append(
                    DiagnosticsStore.getSupportStatus(this, item.hookTargetId))
                    .append(" updatedAt=")
                    .append(DiagnosticsStore.getSupportUpdatedAt(
                            this, item.hookTargetId)).append('\n');
        }

        out.append("\n[stats]\n");
        for (String feature : FeatureIds.all()) {
            out.append(feature)
                    .append(" observed=").append(DiagnosticsStore.getStatInt(this, feature, "observed"))
                    .append(" blocked=").append(DiagnosticsStore.getStatInt(this, feature, "blocked"))
                    .append(" failOpen=").append(DiagnosticsStore.getStatInt(this, feature, "fail_open"))
                    .append(" lastPkg=").append(DiagnosticsStore.getStatString(this, feature, "last_pkg"))
                    .append(" lastScene=").append(DiagnosticsStore.getStatString(this, feature, "last_scene"))
                    .append(" lastReason=").append(DiagnosticsStore.getStatString(this, feature, "last_reason"))
                    .append(" lastResult=").append(DiagnosticsStore.getStatString(this, feature, "last_result"))
                    .append(" lastAt=").append(DiagnosticsStore.getStatLong(this, feature, "last_at"))
                    .append('\n');
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                "ColorOS后台增强诊断信息", out.toString()));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void appendProcess(StringBuilder out, String processId) {
        DiagnosticsStore.ProcessSnapshot process = DiagnosticsStore.getProcess(this, processId);
        out.append(processId)
                .append(" currentBoot=").append(process.isCurrentBoot(this))
                .append(" package=").append(process.packageName)
                .append(" process=").append(process.processName)
                .append(" stage=").append(process.stage)
                .append(" configRevision=").append(process.configRevision)
                .append(" configSource=").append(process.configSource)
                .append(" configPath=").append(process.configPath)
                .append(" reloadLatencyMs=").append(process.reloadLatencyMs)
                .append(" loadedAt=").append(process.loadedAt)
                .append(" configAt=").append(process.configLoadedAt)
                .append(" boot=").append(process.bootCount)
                .append(" error=").append(process.error)
                .append('\n');
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_stats_title)
                .setMessage(R.string.reset_stats_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset, (dialog, which) -> {
                    DiagnosticsStore.resetStats(this, FeatureIds.all());
                    Toast.makeText(this, R.string.stats_reset_done, Toast.LENGTH_SHORT).show();
                    render();
                }).show();
    }

    private String formatTime(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    private void addSectionTitle(int textRes) {
        TextView title = new TextView(this);
        title.setText(textRes);
        title.setTextColor(getColor(R.color.item_summary));
        title.setTextSize(14f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = container.getChildCount() == 0 ? 0 : UiUtils.dp(this, 24);
        p.bottomMargin = UiUtils.dp(this, 10);
        container.addView(title, p);
    }

    private MaterialCardView newCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.card_bg));
        card.setRadius(UiUtils.dp(this, 28));
        card.setCardElevation(0f);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void addStatusRow(LinearLayout parent, String titleText, String summaryText) {
        LinearLayout row = vertical();
        row.setPadding(UiUtils.dp(this, 20), UiUtils.dp(this, 16),
                UiUtils.dp(this, 20), UiUtils.dp(this, 16));
        row.setMinimumHeight(UiUtils.dp(this, 72));
        row.addView(title(titleText));
        row.addView(summary(summaryText));
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private TextView title(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_SettingTitle);
        return view;
    }

    private TextView summary(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_Summary);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = UiUtils.dp(this, 4);
        view.setLayoutParams(p);
        return view;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtils.dp(this, 1));
        p.setMarginStart(UiUtils.dp(this, 20));
        p.setMarginEnd(UiUtils.dp(this, 20));
        parent.addView(divider, p);
    }
}
