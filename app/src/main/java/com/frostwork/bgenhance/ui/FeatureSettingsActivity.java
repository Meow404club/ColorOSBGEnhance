package com.frostwork.bgenhance.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.DiagnosticsStore;
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
    private boolean masterEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feature_settings);
        UiUtils.applyWindowStyle(this);
        group = getIntent().getStringExtra(EXTRA_GROUP);
        if (group == null) group = FeatureCatalog.GROUP_FAST_CLEAR;
        if (!ensureCustomMode()) return;
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.page_title)).setText(FeatureCatalog.titleForGroup(group));
        ((TextView) findViewById(R.id.page_subtitle)).setText(FeatureCatalog.subtitleForGroup(group));
        container = findViewById(R.id.card_container);
        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureCustomMode()) return;
        ModuleSettings.notifyConfigChanged(this);
        buildRows();
    }

    private void buildRows() {
        if (container == null) return;
        container.removeAllViews();
        List<FeatureCatalog.FeatureSpec> specs = FeatureCatalog.forGroup(group);
        masterEnabled = ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_MASTER_ENABLE);
        ((TextView) findViewById(R.id.banner_dry_run)).setVisibility(
                ModuleSettings.getLocalBoolean(this, ModuleSettings.KEY_DRY_RUN)
                        ? View.VISIBLE : View.GONE);

        List<List<FeatureCatalog.FeatureSpec>> sections = groupByRisk(specs);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            List<FeatureCatalog.FeatureSpec> section = sections.get(sectionIndex);
            addRiskSection(section, sectionIndex < sections.size() - 1);
        }
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
            String status = DiagnosticsStore.getSupportStatus(this, spec.hookTargetId);
            boolean unsupported = status.startsWith("UNSUPPORTED") || status.startsWith("FAILED");
            if (spec.diagnosticOnly) row.showDiagnosticOnly();
            else if (unsupported) row.showUnsupported();
            else row.setRowEnabled(masterEnabled);
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
        if (checked && needsRiskConfirmation(spec)
                && !ModuleSettings.prefs(this).getBoolean("confirmed_risk." + spec.prefKey, false)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.risk_dialog_title, getString(spec.titleRes)))
                    .setMessage(R.string.risk_dialog_message)
                    .setNegativeButton(R.string.cancel, (dialog, which) -> row.setCheckedSilently(false))
                    .setPositiveButton(R.string.enable_anyway, (dialog, which) -> {
                        ModuleSettings.prefs(this).edit()
                                .putBoolean("confirmed_risk." + spec.prefKey, true).commit();
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
        Snackbar.make(findViewById(android.R.id.content),
                saved ? R.string.settings_saved : R.string.settings_save_failed,
                saved ? Snackbar.LENGTH_SHORT : Snackbar.LENGTH_LONG).show();
    }

    private boolean needsRiskConfirmation(FeatureCatalog.FeatureSpec spec) {
        return spec.risk == FeatureCatalog.Risk.HIGH
                || spec.risk == FeatureCatalog.Risk.VERY_HIGH
                || spec.experimental;
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
