package com.frostwork.bgenhance.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeatureSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_GROUP = "group";
    private String group;
    private LinearLayout container;
    private TextView masterDisabledBanner;
    private TextView effectNotice;
    private boolean masterEnabled;
    private HookStatusViewModel hookStatusViewModel;
    private HookStatusViewModel.State hookStatus;
    private final Runnable settledHookRefresh = () -> {
        if (!isFinishing() && !isDestroyed() && hookStatusViewModel != null) {
            hookStatusViewModel.refresh();
        }
    };
    private final ModuleApplication.StateListener moduleStateListener = () ->
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateEffectNotice(FeatureCatalog.forGroup(group));
                if (container != null) {
                    container.removeCallbacks(settledHookRefresh);
                    container.postDelayed(settledHookRefresh, 1_200L);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feature_settings);
        UiUtils.applyWindowStyle(this);
        group = getIntent().getStringExtra(EXTRA_GROUP);
        if (FeatureCatalog.forGroup(group).isEmpty()) {
            group = FeatureCatalog.GROUP_FAST_CLEAR;
        }
        if (!ensureCustomMode()) return;
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.page_title)).setText(FeatureCatalog.titleForGroup(group));
        ((TextView) findViewById(R.id.page_subtitle)).setText(FeatureCatalog.subtitleForGroup(group));
        masterDisabledBanner = findViewById(R.id.banner_master_disabled);
        effectNotice = findViewById(R.id.feature_effect_notice);
        container = findViewById(R.id.card_container);
        hookStatusViewModel = HookStatusViewModel.create(this);
        hookStatusViewModel.setListener(state -> {
            hookStatus = state;
            buildRows();
        });
        buildRows();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addStateListener(moduleStateListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureCustomMode()) return;
        ModuleSettings.notifyConfigChanged(this);
        if (hookStatusViewModel != null) {
            hookStatusViewModel.refresh();
            hookStatus = hookStatusViewModel.state();
        }
        buildRows();
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeStateListener(moduleStateListener);
        if (container != null) container.removeCallbacks(settledHookRefresh);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ModuleApplication.removeStateListener(moduleStateListener);
        if (container != null) container.removeCallbacks(settledHookRefresh);
        if (hookStatusViewModel != null) hookStatusViewModel.close();
        super.onDestroy();
    }

    private void buildRows() {
        if (container == null) return;
        container.removeAllViews();
        List<FeatureCatalog.FeatureSpec> specs = FeatureCatalog.forGroup(group);
        masterEnabled = ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_MASTER_ENABLE);
        masterDisabledBanner.setVisibility(masterEnabled ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.banner_dry_run)).setVisibility(
                masterEnabled && ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_DRY_RUN)
                        ? View.VISIBLE : View.GONE);

        List<List<FeatureCatalog.FeatureSpec>> sections = groupByRisk(specs);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            List<FeatureCatalog.FeatureSpec> section = sections.get(sectionIndex);
            addRiskSection(section, sectionIndex < sections.size() - 1);
        }
        updateEffectNotice(specs);
    }

    private List<List<FeatureCatalog.FeatureSpec>> groupByRisk(
            List<FeatureCatalog.FeatureSpec> specs) {
        Map<String, List<FeatureCatalog.FeatureSpec>> grouped = new LinkedHashMap<>();
        for (FeatureCatalog.FeatureSpec spec : specs) {
            String key = spec.risk.name() + ":" + spec.experimental;
            List<FeatureCatalog.FeatureSpec> section = grouped.get(key);
            if (section == null) {
                section = new ArrayList<>();
                grouped.put(key, section);
            }
            section.add(spec);
        }
        return new ArrayList<>(grouped.values());
    }

    private void addRiskSection(List<FeatureCatalog.FeatureSpec> specs, boolean addBottomMargin) {
        if (specs.isEmpty()) return;

        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.card_bg));
        card.setRadius(UiUtils.dp(this, 28));
        card.setCardElevation(0f);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        card.addView(content, new MaterialCardView.LayoutParams(
                MaterialCardView.LayoutParams.MATCH_PARENT,
                MaterialCardView.LayoutParams.WRAP_CONTENT));

        FeatureCatalog.FeatureSpec first = specs.get(0);
        RiskNoticeView header = new RiskNoticeView(this);
        header.bind(first.risk, first.experimental);
        content.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addDivider(content);

        for (int i = 0; i < specs.size(); i++) {
            FeatureCatalog.FeatureSpec spec = specs.get(i);
            SettingSwitchRow row = new SettingSwitchRow(this);
            row.bind(spec, ModuleSettings.getLocalBoolean(this, spec.prefKey));
            boolean unsupported = isExplicitlyUnsupported(spec);
            if (spec.diagnosticOnly) row.showDiagnosticOnly();
            else if (unsupported) {
                row.showUnsupported();
                if (!masterEnabled) row.setRowEnabled(false);
            } else {
                row.setRowEnabled(masterEnabled);
            }
            row.setListener((changedRow, checked) -> onSwitchRequested(spec, changedRow, checked));
            content.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (i < specs.size() - 1) addDivider(content);
        }

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (addBottomMargin) cardParams.bottomMargin = UiUtils.dp(this, 16);
        container.addView(card, cardParams);
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtils.dp(this, 1));
        params.setMarginStart(UiUtils.dp(this, 20));
        params.setMarginEnd(UiUtils.dp(this, 20));
        parent.addView(divider, params);
    }

    private void onSwitchRequested(FeatureCatalog.FeatureSpec spec,
                                   SettingSwitchRow row,
                                   boolean checked) {
        if (checked && FeatureCatalog.needsRiskConfirmation(spec)
                && !ModuleSettings.prefs(this).getBoolean(
                        FeatureCatalog.riskConfirmationKey(spec), false)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.risk_dialog_title, getString(spec.titleRes)))
                    .setMessage(R.string.risk_dialog_message)
                    .setNegativeButton(R.string.cancel, (dialog, which) -> row.setCheckedSilently(false))
                    .setPositiveButton(R.string.enable_anyway, (dialog, which) -> {
                        ModuleSettings.prefs(this).edit()
                                .putBoolean(FeatureCatalog.riskConfirmationKey(spec), true).apply();
                        persist(spec, row, true);
                    })
                    .setOnCancelListener(dialog -> row.setCheckedSilently(false))
                    .show();
            return;
        }
        persist(spec, row, checked);
    }

    private void persist(FeatureCatalog.FeatureSpec spec, SettingSwitchRow row, boolean checked) {
        row.setCheckedSilently(checked);
        if (!isCustomMode()) {
            row.setCheckedSilently(!checked);
            Toast.makeText(this, R.string.custom_mode_required, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        boolean saved = ModuleSettings.setBoolean(this, spec.prefKey, checked);
        row.setCheckedSilently(ModuleSettings.getLocalBoolean(this, spec.prefKey));
        if (isExplicitlyUnsupported(spec)) row.showUnsupported(row.isChecked());
        Snackbar.make(findViewById(android.R.id.content),
                saved ? R.string.settings_saved : R.string.settings_save_failed,
                saved ? Snackbar.LENGTH_SHORT : Snackbar.LENGTH_LONG).show();
        updateEffectNotice(FeatureCatalog.forGroup(group));
    }

    private void updateEffectNotice(List<FeatureCatalog.FeatureSpec> specs) {
        if (effectNotice == null) return;
        List<FeatureCatalog.FeatureSpec> enabledSpecs = new ArrayList<>();
        if (masterEnabled && specs != null) {
            for (FeatureCatalog.FeatureSpec spec : specs) {
                if (spec != null && !spec.diagnosticOnly
                        && ModuleSettings.getLocalBoolean(this, spec.prefKey)) {
                    enabledSpecs.add(spec);
                }
            }
        }
        int message = FeatureApplyStatus.message(
                hookStatus,
                ModuleApplication.isServiceConnected(),
                ModuleApplication.hasConfigSyncError(),
                ModuleSettings.getConfigRevision(this),
                enabledSpecs);
        if (message == 0) {
            effectNotice.setVisibility(View.GONE);
            return;
        }
        effectNotice.setText(message);
        effectNotice.setVisibility(View.VISIBLE);
        if (FeatureApplyStatus.tone(message) == FeatureApplyStatus.Tone.ERROR) {
            effectNotice.setBackgroundResource(R.drawable.bg_notice_error);
            effectNotice.setTextColor(getColor(R.color.status_error_text));
        } else {
            effectNotice.setBackgroundResource(R.drawable.bg_notice_warning);
            effectNotice.setTextColor(getColor(R.color.banner_warning_text));
        }
    }

    private boolean isExplicitlyUnsupported(FeatureCatalog.FeatureSpec spec) {
        return hookStatus != null
                && hookStatus.isExplicitlyUnavailable(spec.hookTargetId);
    }

    private boolean isCustomMode() {
        return ModuleSettings.PRESET_CUSTOM.equals(ModuleSettings.getLocalString(
                this, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED));
    }

    private boolean ensureCustomMode() {
        if (isCustomMode()) return true;
        if (!isFinishing()) {
            Toast.makeText(this, R.string.custom_mode_required, Toast.LENGTH_SHORT).show();
            finish();
        }
        return false;
    }
}
