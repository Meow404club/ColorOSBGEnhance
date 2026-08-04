package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.residency.ResidencyAppsActivity;
import com.frostwork.bgenhance.ui.residency.ResidencyRuleStore;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SettingsActivity extends AppCompatActivity {
    private MaterialSwitch masterSwitch;
    private MaterialSwitch periodicSwitch;
    private MaterialSwitch oguardKillSwitch;
    private MaterialSwitch oguardStopSwitch;
    private MaterialSwitch dryRunSwitch;
    private MaterialButtonToggleGroup presetGroup;
    private TextView statusTitle;
    private TextView statusSummary;
    private TextView dryRunBanner;
    private TextView masterDisabledBanner;
    private TextView effectNotice;
    private View customAdvancedSection;
    private StatsOverviewView statsOverview;
    private HomeDiagnosticsViewModel homeDiagnostics;
    private HomeDiagnosticsStatus lastHomeDiagnosticsStatus;
    private boolean binding;
    private final ModuleApplication.StateListener moduleStateListener =
            () -> runOnUiThread(this::refreshAll);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        UiUtils.applyWindowStyle(this);
        ModuleSettings.ensureDefaults(this);
        bindViews();
        homeDiagnostics = new HomeDiagnosticsViewModel(
                HomeDiagnosticsViewModel.backendSource(
                        ModuleApplication.initializeDiagnostics(this)),
                this::runOnUiThread);
        homeDiagnostics.setListener(this::renderHomeDiagnostics);
        bindListeners();
        refreshAll();
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
    protected void onDestroy() {
        if (homeDiagnostics != null) homeDiagnostics.close();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ModuleSettings.notifyConfigChanged(this);
        refreshAll();
    }

    private void bindViews() {
        masterSwitch = findViewById(R.id.switch_master);
        periodicSwitch = findViewById(R.id.switch_periodic);
        oguardKillSwitch = findViewById(R.id.switch_oguard_kill);
        oguardStopSwitch = findViewById(R.id.switch_oguard_stop);
        dryRunSwitch = findViewById(R.id.switch_dry_run);
        setSwitchAccessibility(masterSwitch, R.string.master_title, R.string.master_summary);
        setSwitchAccessibility(periodicSwitch, R.string.periodic_title, R.string.periodic_summary);
        setSwitchAccessibility(
                oguardKillSwitch, R.string.oguard_kill_title, R.string.oguard_kill_summary);
        setSwitchAccessibility(
                oguardStopSwitch, R.string.oguard_stop_title, R.string.oguard_stop_summary);
        setSwitchAccessibility(dryRunSwitch, R.string.dry_run_title, R.string.dry_run_summary);
        presetGroup = findViewById(R.id.preset_group);
        statusTitle = findViewById(R.id.text_module_status);
        statusSummary = findViewById(R.id.text_module_summary);
        dryRunBanner = findViewById(R.id.banner_dry_run);
        masterDisabledBanner = findViewById(R.id.banner_master_disabled);
        effectNotice = findViewById(R.id.text_effect_notice);
        customAdvancedSection = findViewById(R.id.custom_advanced_section);
        statsOverview = findViewById(R.id.home_stats_overview);
        HomeResponsiveRows.adaptResidencyRow(
                findViewById(R.id.row_residency),
                getResources().getConfiguration().fontScale);
    }

    private void bindListeners() {
        findViewById(R.id.row_master).setOnClickListener(v -> masterSwitch.toggle());
        findViewById(R.id.row_periodic).setOnClickListener(v -> { if (periodicSwitch.isEnabled()) periodicSwitch.toggle(); });
        findViewById(R.id.row_oguard_kill).setOnClickListener(v -> { if (oguardKillSwitch.isEnabled()) oguardKillSwitch.toggle(); });
        findViewById(R.id.row_oguard_stop).setOnClickListener(v -> { if (oguardStopSwitch.isEnabled()) oguardStopSwitch.toggle(); });
        findViewById(R.id.row_dry_run).setOnClickListener(v -> dryRunSwitch.toggle());

        masterSwitch.setOnCheckedChangeListener((button, checked) ->
                persistBoolean(ModuleSettings.KEY_MASTER_ENABLE, checked, false));
        periodicSwitch.setOnCheckedChangeListener((button, checked) ->
                persistBoolean(ModuleSettings.KEY_PERIODIC, checked, true));
        oguardKillSwitch.setOnCheckedChangeListener((button, checked) ->
                persistBoolean(ModuleSettings.KEY_OGUARD_KILL, checked, true));
        oguardStopSwitch.setOnCheckedChangeListener((button, checked) ->
                persistBoolean(ModuleSettings.KEY_OGUARD_FORCE_STOP, checked, true));
        dryRunSwitch.setOnCheckedChangeListener((button, checked) ->
                persistBoolean(ModuleSettings.KEY_DRY_RUN, checked, false));

        presetGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || binding) return;
            if (checkedId == R.id.button_preset_balanced) {
                requestPreset(ModuleSettings.PRESET_BALANCED);
            } else if (checkedId == R.id.button_preset_strong) {
                requestPreset(ModuleSettings.PRESET_STRONG);
            }
            else if (checkedId == R.id.button_preset_custom) {
                boolean saved = ModuleSettings.setString(
                        this,
                        ModuleSettings.KEY_ACTIVE_PRESET,
                        ModuleSettings.PRESET_CUSTOM);
                refreshAll();
                if (!saved) {
                    Snackbar.make(
                            findViewById(android.R.id.content),
                            R.string.settings_save_failed,
                            Snackbar.LENGTH_LONG).show();
                }
            }
        });

        bindNavigation(R.id.row_fast_clear, FeatureCatalog.GROUP_FAST_CLEAR);
        bindNavigation(R.id.row_memory, FeatureCatalog.GROUP_MEMORY);
        bindNavigation(R.id.row_nirvana, FeatureCatalog.GROUP_NIRVANA);
        bindNavigation(R.id.row_freeze, FeatureCatalog.GROUP_FREEZE);
        findViewById(R.id.card_home_stats).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.row_residency).setOnClickListener(v ->
                startActivity(new Intent(this, ResidencyAppsActivity.class)));
    }

    private void bindNavigation(int viewId, String group) {
        findViewById(viewId).setOnClickListener(v -> {
            if (!v.isEnabled()) return;
            Intent intent = new Intent(this, FeatureSettingsActivity.class);
            intent.putExtra(FeatureSettingsActivity.EXTRA_GROUP, group);
            startActivity(intent);
        });
    }

    private void persistBoolean(String key, boolean checked, boolean customPreset) {
        if (binding) return;
        boolean saved;
        if (customPreset) {
            Map<String, Boolean> values = new LinkedHashMap<>();
            values.put(key, checked);
            saved = ModuleSettings.setValues(
                    this, values, ModuleSettings.PRESET_CUSTOM);
        } else {
            saved = ModuleSettings.setBoolean(this, key, checked);
        }
        if (!saved) {
            Snackbar.make(findViewById(android.R.id.content), R.string.settings_save_failed,
                    Snackbar.LENGTH_LONG).show();
        }
        refreshAll();
    }

    private void requestPreset(String preset) {
        if (!ModuleSettings.PRESET_STRONG.equals(preset)) {
            applyPreset(preset);
            return;
        }
        Map<String, Boolean> values = presetValues(this, true);
        List<FeatureCatalog.FeatureSpec> pending = new ArrayList<>();
        for (FeatureCatalog.FeatureSpec spec : FeatureCatalog.all()) {
            if (Boolean.TRUE.equals(values.get(spec.prefKey))
                    && FeatureCatalog.needsRiskConfirmation(spec)
                    && !ModuleSettings.prefs(this).getBoolean(
                            FeatureCatalog.riskConfirmationKey(spec), false)) {
                pending.add(spec);
            }
        }
        if (pending.isEmpty()) {
            applyPreset(preset);
            return;
        }

        refreshAll();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.preset_risk_dialog_title)
                .setMessage(getString(R.string.preset_risk_dialog_message, pending.size()))
                .setNegativeButton(R.string.cancel, (dialog, which) -> refreshAll())
                .setPositiveButton(R.string.apply_anyway, (dialog, which) -> {
                    if (!applyPreset(ModuleSettings.PRESET_STRONG)) return;
                    android.content.SharedPreferences.Editor editor =
                            ModuleSettings.prefs(this).edit();
                    for (FeatureCatalog.FeatureSpec spec : pending) {
                        editor.putBoolean(FeatureCatalog.riskConfirmationKey(spec), true);
                    }
                    editor.apply();
                })
                .setOnCancelListener(dialog -> refreshAll())
                .show();
    }

    private boolean applyPreset(String preset) {
        binding = true;
        boolean strong = ModuleSettings.PRESET_STRONG.equals(preset);
        Map<String, Boolean> values = presetValues(this, strong);
        boolean saved = ModuleSettings.setValues(this, values, preset);
        binding = false;
        refreshAll();
        int message = saved ? R.string.preset_applied : R.string.settings_save_failed;
        String text = saved
                ? getString(message, getString(strong ? R.string.preset_strong : R.string.preset_balanced))
                : getString(message);
        Snackbar.make(
                findViewById(android.R.id.content),
                text,
                saved ? Snackbar.LENGTH_SHORT : Snackbar.LENGTH_LONG).show();
        return saved;
    }

    static Map<String, Boolean> presetValues(Context context, boolean strong) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String key : ModuleSettings.defaults().keySet()) {
            boolean value = ModuleSettings.defaultFor(key);
            if (ModuleSettings.KEY_FAST_CAMERA_START.equals(key)
                    || ModuleSettings.KEY_FAST_GAME_START.equals(key)
                    || ModuleSettings.KEY_TOURNAMENT.equals(key)
                    || ModuleSettings.KEY_MEMORY_FORCE.equals(key)) {
                value = strong;
            }
            if (ModuleSettings.KEY_MASTER_ENABLE.equals(key)
                    || ModuleSettings.KEY_DRY_RUN.equals(key)
                    || ModuleSettings.KEY_VERBOSE_LOG.equals(key)) {
                value = ModuleSettings.getLocalBoolean(context, key);
            }
            values.put(key, value);
        }
        return values;
    }

    private void refreshAll() {
        binding = true;
        masterSwitch.setChecked(ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_MASTER_ENABLE));
        periodicSwitch.setChecked(ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_PERIODIC));
        oguardKillSwitch.setChecked(ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_OGUARD_KILL));
        oguardStopSwitch.setChecked(ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_OGUARD_FORCE_STOP));
        dryRunSwitch.setChecked(ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_DRY_RUN));
        String preset = ModuleSettings.getLocalString(
                this, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED);
        if (ModuleSettings.PRESET_STRONG.equals(preset)) presetGroup.check(R.id.button_preset_strong);
        else if (ModuleSettings.PRESET_CUSTOM.equals(preset)) presetGroup.check(R.id.button_preset_custom);
        else presetGroup.check(R.id.button_preset_balanced);
        binding = false;

        boolean custom = ModuleSettings.PRESET_CUSTOM.equals(preset);
        customAdvancedSection.setVisibility(custom ? View.VISIBLE : View.GONE);

        boolean master = masterSwitch.isChecked();
        refreshProtectionEditorAvailability(master);
        refreshBasicSwitchAvailability(master, lastHomeDiagnosticsStatus);
        dryRunBanner.setVisibility(master && dryRunSwitch.isChecked() ? View.VISIBLE : View.GONE);
        masterDisabledBanner.setVisibility(master ? View.GONE : View.VISIBLE);

        refreshNavigationCounts();
        refreshResidencySummary();
        homeDiagnostics.load();
    }

    void renderHomeDiagnostics(HomeDiagnosticsViewModel.State state) {
        statusSummary.setVisibility(View.VISIBLE);
        if (state.status == HomeDiagnosticsViewModel.Status.LOADING) {
            statusTitle.setText(R.string.status_checking);
            statusSummary.setText(R.string.status_checking_summary);
            statsOverview.bindLoading(getString(R.string.home_stats_loading), true);
            effectNotice.setVisibility(View.GONE);
            return;
        }
        if (state.status == HomeDiagnosticsViewModel.Status.ERROR) {
            statusTitle.setText(R.string.status_unavailable);
            statusSummary.setText(R.string.status_unavailable_summary);
            statsOverview.bindUnavailable(getString(R.string.home_stats_unavailable), true);
            showEffectWarning(R.string.effect_runtime_unavailable);
            return;
        }

        int localRevision = ModuleSettings.getConfigRevision(this);
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                state.hookStates.items,
                ModuleApplication.diagnosticsBootId(),
                localRevision,
                BuildConfig.VERSION_CODE);
        applyHomeDiagnosticsStatus(status);
        renderActivation(status.activation);
        statsOverview.bind(
                state.stats.observed,
                state.stats.blocked,
                state.stats.failOpen,
                getString(R.string.home_stats_action),
                true,
                state.stats.historyTruncated);
        refreshEffectNotice(status);
    }

    void renderActivation(HomeDiagnosticsStatus.Activation activation) {
        statusSummary.setVisibility(View.VISIBLE);
        if (activation == HomeDiagnosticsStatus.Activation.ACTIVE) {
            statusTitle.setText(R.string.status_active);
            statusSummary.setVisibility(View.GONE);
        } else if (activation == HomeDiagnosticsStatus.Activation.PARTIAL) {
            statusTitle.setText(R.string.status_partial);
            statusSummary.setText(R.string.status_partial_summary);
        } else {
            statusTitle.setText(R.string.status_inactive);
            statusSummary.setText(R.string.status_inactive_summary);
        }
    }

    private void setSwitchAccessibility(
            MaterialSwitch switchView, int titleRes, int summaryRes) {
        switchView.setContentDescription(getString(
                R.string.setting_switch_accessibility_summary,
                getString(titleRes),
                getString(summaryRes)));
    }

    private void refreshEffectNotice(HomeDiagnosticsStatus status) {
        int message = status.effectNotice(
                ModuleApplication.isServiceConnected(),
                ModuleApplication.isConfigSynced(this),
                ModuleApplication.hasConfigSyncError());
        if (message == 0) {
            effectNotice.setVisibility(View.GONE);
        } else {
            showEffectWarning(message);
        }
    }

    void applyHomeDiagnosticsStatus(HomeDiagnosticsStatus status) {
        lastHomeDiagnosticsStatus = status;
        refreshBasicSwitchAvailability(masterSwitch.isChecked(), status);
    }

    private void refreshBasicSwitchAvailability(
            boolean masterEnabled, HomeDiagnosticsStatus status) {
        updateBasicSwitch(
                periodicSwitch, R.id.row_periodic, HookTargetIds.ATHENA_PERIODIC,
                masterEnabled, status);
        updateBasicSwitch(
                oguardKillSwitch, R.id.row_oguard_kill, HookTargetIds.OGUARD_KILL,
                masterEnabled, status);
        updateBasicSwitch(
                oguardStopSwitch, R.id.row_oguard_stop, HookTargetIds.OGUARD_FORCE_STOP,
                masterEnabled, status);
    }

    private void refreshProtectionEditorAvailability(boolean masterEnabled) {
        presetGroup.setEnabled(masterEnabled);
        for (int index = 0; index < presetGroup.getChildCount(); index++) {
            presetGroup.getChildAt(index).setEnabled(masterEnabled);
        }
        updateEditableRow(dryRunSwitch, R.id.row_dry_run, masterEnabled);
        int[] navigationRows = {
                R.id.row_fast_clear, R.id.row_memory, R.id.row_nirvana, R.id.row_freeze
        };
        for (int rowId : navigationRows) {
            View row = findViewById(rowId);
            row.setEnabled(masterEnabled);
        }
        customAdvancedSection.setAlpha(masterEnabled ? 1f : 0.55f);
    }

    private void updateEditableRow(MaterialSwitch switchView, int rowId, boolean editable) {
        switchView.setEnabled(editable);
        findViewById(rowId).setAlpha(editable ? 1f : 0.55f);
    }

    private void updateBasicSwitch(
            MaterialSwitch switchView,
            int rowId,
            String hookTargetId,
            boolean masterEnabled,
            HomeDiagnosticsStatus status) {
        boolean unavailable = status != null
                && status.isPrimaryHookExplicitlyUnavailable(hookTargetId);
        boolean editable = masterEnabled && (switchView.isChecked() || !unavailable);
        switchView.setEnabled(editable);
        findViewById(rowId).setAlpha(editable ? 1f : 0.55f);
    }

    private void showEffectWarning(int textRes) {
        effectNotice.setVisibility(View.VISIBLE);
        effectNotice.setText(textRes);
        effectNotice.setTextColor(getColor(R.color.banner_warning_text));
        effectNotice.setBackgroundResource(R.drawable.bg_notice_warning);
    }

    private void refreshNavigationCounts() {
        setCount(R.id.status_fast_clear, Arrays.asList(
                ModuleSettings.KEY_FAST_CAMERA_START, ModuleSettings.KEY_FAST_CAMERA_OPERATION,
                ModuleSettings.KEY_FAST_GAME_START, ModuleSettings.KEY_FAST_AI,
                ModuleSettings.KEY_TOURNAMENT));
        setCount(R.id.status_memory, Arrays.asList(
                ModuleSettings.KEY_MEMORY_FORCE, ModuleSettings.KEY_MEMORY_OVER));
        setCount(R.id.status_nirvana, Arrays.asList(
                ModuleSettings.KEY_NIRVANA, ModuleSettings.KEY_CAMERA_LOW_FREE,
                ModuleSettings.KEY_GAME_LOW_FREE));
        setCount(R.id.status_freeze, Arrays.asList(
                ModuleSettings.KEY_HANS, ModuleSettings.KEY_SUPER_FREEZE));
    }

    private void setCount(int textId, List<String> keys) {
        int count = 0;
        for (String key : keys) if (ModuleSettings.getLocalBoolean(this, key)) count++;
        ((TextView) findViewById(textId)).setText(count == 0
                ? getString(R.string.none_enabled) : getString(R.string.enabled_count, count));
    }

    private void refreshResidencySummary() {
        TextView status = findViewById(R.id.status_residency);
        try {
            int count = ResidencyRuleStore.read(this).rules().size();
            status.setText(count == 0
                    ? getString(R.string.residency_home_empty)
                    : getString(R.string.residency_home_count, count));
        } catch (RuntimeException exception) {
            status.setText(R.string.residency_home_unavailable);
        }
    }
}
