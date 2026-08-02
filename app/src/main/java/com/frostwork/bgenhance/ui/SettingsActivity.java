package com.frostwork.bgenhance.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsStore;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.residency.ResidencyAppsActivity;
import com.frostwork.bgenhance.ui.residency.ResidencyRuleStore;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
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
    private TextView effectNotice;
    private View customAdvancedSection;
    private StatsOverviewView statsOverview;
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
        presetGroup = findViewById(R.id.preset_group);
        statusTitle = findViewById(R.id.text_module_status);
        statusSummary = findViewById(R.id.text_module_summary);
        dryRunBanner = findViewById(R.id.banner_dry_run);
        effectNotice = findViewById(R.id.text_effect_notice);
        customAdvancedSection = findViewById(R.id.custom_advanced_section);
        statsOverview = findViewById(R.id.home_stats_overview);
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
            if (checkedId == R.id.button_preset_balanced) applyPreset(ModuleSettings.PRESET_BALANCED);
            else if (checkedId == R.id.button_preset_strong) applyPreset(ModuleSettings.PRESET_STRONG);
            else if (checkedId == R.id.button_preset_custom) {
                ModuleSettings.setString(this, ModuleSettings.KEY_ACTIVE_PRESET,
                        ModuleSettings.PRESET_CUSTOM);
                refreshAll();
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

    private void applyPreset(String preset) {
        binding = true;
        boolean strong = ModuleSettings.PRESET_STRONG.equals(preset);
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String key : ModuleSettings.defaults().keySet()) {
            boolean value = ModuleSettings.defaultFor(key);
            if (ModuleSettings.KEY_FAST_CAMERA_START.equals(key)
                    || ModuleSettings.KEY_FAST_GAME_START.equals(key)
                    || ModuleSettings.KEY_TOURNAMENT.equals(key)
                    || ModuleSettings.KEY_MEMORY_FORCE.equals(key)) {
                value = strong;
            }
            if (ModuleSettings.KEY_DRY_RUN.equals(key)
                    || ModuleSettings.KEY_VERBOSE_LOG.equals(key)) {
                value = ModuleSettings.getLocalBoolean(this, key);
            }
            values.put(key, value);
        }
        boolean saved = ModuleSettings.setValues(this, values, preset);
        binding = false;
        refreshAll();
        int message = saved ? R.string.preset_applied : R.string.settings_save_failed;
        String text = saved
                ? getString(message, getString(strong ? R.string.preset_strong : R.string.preset_balanced))
                : getString(message);
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_SHORT).show();
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
        periodicSwitch.setEnabled(master);
        oguardKillSwitch.setEnabled(master);
        oguardStopSwitch.setEnabled(master);
        findViewById(R.id.row_periodic).setAlpha(master ? 1f : 0.55f);
        findViewById(R.id.row_oguard_kill).setAlpha(master ? 1f : 0.55f);
        findViewById(R.id.row_oguard_stop).setAlpha(master ? 1f : 0.55f);
        dryRunBanner.setVisibility(dryRunSwitch.isChecked() ? View.VISIBLE : View.GONE);

        refreshHookStatus();
        refreshNavigationCounts();
        refreshResidencySummary();
    }

    private void refreshHookStatus() {
        String ogKill = DiagnosticsStore.getSupportStatus(this, HookTargetIds.OGUARD_KILL);
        String ogStop = DiagnosticsStore.getSupportStatus(
                this, HookTargetIds.OGUARD_FORCE_STOP);
        String periodic = DiagnosticsStore.getSupportStatus(
                this, HookTargetIds.ATHENA_PERIODIC);
        String fast = DiagnosticsStore.getSupportStatus(
                this, HookTargetIds.FAST_CLEAR_DISPATCH);
        int installed = 0;
        int known = 0;
        for (String value : Arrays.asList(ogKill, ogStop, periodic, fast)) {
            if (!"UNKNOWN".equals(value)) known++;
            if (value.startsWith("INSTALLED")) installed++;
        }
        if (installed >= 3) {
            statusTitle.setText(R.string.status_active);
            statusSummary.setText(installed == 4
                    ? R.string.status_active_summary : R.string.status_partial_summary);
        } else if (known > 0) {
            statusTitle.setText(R.string.status_partial);
            statusSummary.setText(R.string.status_partial_summary);
        } else {
            statusTitle.setText(R.string.status_inactive);
            statusSummary.setText(R.string.status_inactive_summary);
        }
        refreshEffectNotice(installed);
    }

    private void refreshEffectNotice(int installedHooks) {
        DiagnosticsStore.ProcessSnapshot system = DiagnosticsStore.getProcess(
                this, BridgeContract.PROCESS_SYSTEM_SERVER);
        DiagnosticsStore.ProcessSnapshot athena = DiagnosticsStore.getProcess(
                this, BridgeContract.PROCESS_ATHENA);
        boolean systemCurrent = system.isCurrentBoot(this);
        boolean athenaCurrent = athena.isCurrentBoot(this);
        int revision = ModuleSettings.getConfigRevision(this);
        boolean systemSynced = systemCurrent && system.configRevision >= revision;
        boolean athenaSynced = athenaCurrent && athena.configRevision >= revision;

        if (!ModuleApplication.isServiceConnected()) {
            showEffectWarning(R.string.effect_config_service_waiting);
        } else if (!ModuleApplication.isConfigSynced(this)) {
            showEffectWarning(R.string.effect_config_pending);
        } else if (systemCurrent && athenaCurrent && systemSynced && athenaSynced
                && installedHooks >= 3) {
            effectNotice.setVisibility(View.GONE);
        } else if ((systemCurrent && !systemSynced) || (athenaCurrent && !athenaSynced)) {
            showEffectWarning(R.string.effect_config_pending);
        } else {
            showEffectWarning(R.string.effect_restart_required);
        }
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
                ModuleSettings.KEY_HANS, ModuleSettings.KEY_SUPER_FREEZE,
                ModuleSettings.KEY_ANDROID_FREEZE));
        int observed = 0;
        int blocked = 0;
        int failOpen = 0;
        for (String feature : FeatureIds.all()) {
            observed += DiagnosticsStore.getStatInt(this, feature, "observed");
            blocked += DiagnosticsStore.getStatInt(this, feature, "blocked");
            failOpen += DiagnosticsStore.getStatInt(this, feature, "fail_open");
        }
        statsOverview.bind(observed, blocked, failOpen,
                getString(R.string.home_stats_action), true);
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
