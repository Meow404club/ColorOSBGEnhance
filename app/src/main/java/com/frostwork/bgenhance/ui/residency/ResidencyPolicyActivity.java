package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.ui.HookStatusViewModel;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import java.util.EnumSet;
import java.util.Set;

/** Edits one application policy using compact presets and focused custom editors. */
public final class ResidencyPolicyActivity extends AppCompatActivity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String STATE_SELECTED_MODE = "selected_mode";
    private static final String STATE_FORM_READY = "form_ready";
    private static final String STATE_DRAFT = "draft";
    private static final String STATE_PENDING_SECTION = "pending_section";

    private String packageName;
    private String restoredDraft;
    private ResidencyPolicyEditorContract.Section pendingSection;
    private ResidencyPolicyViewModel viewModel;
    private HookStatusViewModel hookStatusViewModel;
    private HookStatusViewModel.State hookStatus;
    private ResidencyAppIconLoader iconLoader;
    private ResidencyPolicyDraft loadedDraft;
    private ResidencyPolicyDraft workingDraft;
    private ResidencyAppItem app;
    private boolean existingRule;
    private boolean bound;
    private ResidencyMode selectedMode = ResidencyMode.BALANCED;

    private final ActivityResultLauncher<Intent> sectionEditor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                ResidencyPolicyEditorContract.Section expected = pendingSection;
                pendingSection = null;
                if (result.getResultCode() != RESULT_OK
                        || expected == null
                        || workingDraft == null) {
                    return;
                }
                try {
                    workingDraft = ResidencyPolicyEditorContract.readResult(
                            result.getData(), packageName, expected);
                    updateSectionRows();
                } catch (IllegalArgumentException exception) {
                    Snackbar.make(
                            findViewById(android.R.id.content),
                            R.string.residency_invalid_policy,
                            Snackbar.LENGTH_LONG).show();
                }
            });

    private View content;
    private View errorState;
    private View progress;
    private View customSection;
    private View riskNotices;
    private View highRiskRow;
    private View experimentalRiskRow;
    private TextView highRiskSummary;
    private TextView experimentalRiskSummary;
    private TextView applyNotice;
    private TextView modeSummary;
    private MaterialAutoCompleteTextView modeSelector;
    private MaterialSwitch enabledSwitch;
    private MaterialButton saveButton;
    private MaterialButton deleteButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }
        if (savedInstanceState != null) {
            selectedMode = parseMode(savedInstanceState.getString(STATE_SELECTED_MODE));
            restoredDraft = savedInstanceState.getString(STATE_DRAFT);
            pendingSection = parseSection(savedInstanceState.getString(STATE_PENDING_SECTION));
        }
        setContentView(R.layout.activity_residency_policy);
        UiUtils.applyWindowStyle(this);
        iconLoader = new ResidencyAppIconLoader(this);
        bindViews();
        hookStatusViewModel = HookStatusViewModel.create(this);
        hookStatusViewModel.setListener(state -> {
            hookStatus = state;
            updateApplyNotice();
        });
        hookStatus = hookStatusViewModel.state();
        updateApplyNotice();
        viewModel = new ResidencyPolicyViewModel(getApplicationContext(), packageName);
        viewModel.setListener(this::render);
        bindInteractions();
        viewModel.load();
    }

    @Override
    protected void onDestroy() {
        if (viewModel != null) viewModel.close();
        if (hookStatusViewModel != null) hookStatusViewModel.close();
        if (iconLoader != null) iconLoader.close();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hookStatusViewModel != null) {
            hookStatusViewModel.refresh();
            hookStatus = hookStatusViewModel.state();
        }
        updateApplyNotice();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_SELECTED_MODE, selectedMode.name());
        outState.putBoolean(STATE_FORM_READY, bound && app != null && workingDraft != null);
        if (workingDraft != null) {
            ResidencyPolicyDraft snapshot = snapshotDraft();
            outState.putString(
                    STATE_DRAFT,
                    ResidencyPolicyEditorContract.encodeDraft(packageName, snapshot));
        }
        if (pendingSection != null) {
            outState.putString(STATE_PENDING_SECTION, pendingSection.name());
        }
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        content = findViewById(R.id.residency_policy_content);
        errorState = findViewById(R.id.residency_policy_error_state);
        progress = findViewById(R.id.residency_policy_progress);
        customSection = findViewById(R.id.residency_custom_section);
        riskNotices = findViewById(R.id.residency_risk_notices);
        highRiskRow = findViewById(R.id.residency_risk_high_row);
        experimentalRiskRow = findViewById(R.id.residency_risk_experimental_row);
        highRiskSummary = findViewById(R.id.residency_risk_high_summary);
        experimentalRiskSummary = findViewById(R.id.residency_risk_experimental_summary);
        applyNotice = findViewById(R.id.residency_policy_apply_notice);
        modeSummary = findViewById(R.id.residency_mode_selected_summary);
        modeSelector = findViewById(R.id.residency_mode_selector);
        enabledSwitch = findViewById(R.id.residency_policy_enabled);
        saveButton = findViewById(R.id.residency_policy_save);
        deleteButton = findViewById(R.id.residency_policy_delete);
    }

    private void bindInteractions() {
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(view -> finish());
        findViewById(R.id.residency_policy_retry).setOnClickListener(view -> viewModel.load());
        saveButton.setOnClickListener(view -> save());
        deleteButton.setOnClickListener(view -> confirmDelete());
        findViewById(R.id.residency_policy_enabled_row).setOnClickListener(view -> {
            if (enabledSwitch.isEnabled()) enabledSwitch.toggle();
        });

        String[] modes = getResources().getStringArray(R.array.residency_policy_modes);
        ResidencyDropdowns.setItems(modeSelector, modes);
        modeSelector.setOnItemClickListener((parent, view, position, id) ->
                selectMode(ResidencyMode.values()[position]));

        bindSectionRow(R.id.residency_kill_section_row,
                ResidencyPolicyEditorContract.Section.KILL);
        bindSectionRow(R.id.residency_freeze_section_row,
                ResidencyPolicyEditorContract.Section.FREEZE);
        bindSectionRow(R.id.residency_wake_section_row,
                ResidencyPolicyEditorContract.Section.WAKE);
        bindSectionRow(R.id.residency_resource_section_row,
                ResidencyPolicyEditorContract.Section.RESOURCE);
        bindSectionRow(R.id.residency_refreeze_section_row,
                ResidencyPolicyEditorContract.Section.REFREEZE);
    }

    private void bindSectionRow(int id, ResidencyPolicyEditorContract.Section section) {
        findViewById(id).setOnClickListener(view -> showPresetDialog(section));
    }

    private void render(ResidencyPolicyViewModel.State state) {
        switch (state.status()) {
            case LOADING:
                progress.setVisibility(View.VISIBLE);
                content.setVisibility(View.GONE);
                errorState.setVisibility(View.GONE);
                return;
            case READY:
                progress.setVisibility(View.GONE);
                errorState.setVisibility(View.GONE);
                content.setVisibility(View.VISIBLE);
                bindLoadedState(state);
                setBusy(false);
                return;
            case SAVING:
                progress.setVisibility(View.VISIBLE);
                content.setVisibility(View.VISIBLE);
                errorState.setVisibility(View.GONE);
                setBusy(true);
                return;
            case SAVED:
                Toast.makeText(this, R.string.residency_saved, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PACKAGE_NAME, packageName));
                finish();
                return;
            case DELETED:
                Toast.makeText(this, R.string.residency_deleted, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PACKAGE_NAME, packageName));
                finish();
                return;
            case ERROR:
                progress.setVisibility(View.GONE);
                if (state.app() == null) {
                    content.setVisibility(View.GONE);
                    errorState.setVisibility(View.VISIBLE);
                } else {
                    content.setVisibility(View.VISIBLE);
                    errorState.setVisibility(View.GONE);
                    setBusy(false);
                    Snackbar.make(
                            findViewById(android.R.id.content),
                            ResidencyUiText.failure(state.failure()),
                            Snackbar.LENGTH_LONG).show();
                }
                return;
            default:
                throw new AssertionError(state.status());
        }
    }

    private void bindLoadedState(ResidencyPolicyViewModel.State state) {
        app = state.app();
        existingRule = state.existingRule();
        loadedDraft = state.draft();
        if (!bound) {
            bound = true;
            ((TextView) findViewById(R.id.residency_policy_app_name)).setText(app.label());
            ((TextView) findViewById(R.id.residency_policy_package)).setText(app.packageName());
            iconLoader.bind(findViewById(R.id.residency_policy_icon), app.packageName());
            workingDraft = restoreDraftOrDefault(loadedDraft);
            enabledSwitch.setChecked(workingDraft.enabled());
            if (restoredDraft == null) selectedMode = loadedDraft.mode();
            selectMode(selectedMode);
            updateSectionRows();
            restoredDraft = null;
        }
        deleteButton.setVisibility(existingRule ? View.VISIBLE : View.GONE);
        updateApplyNotice();
        TextView restriction = findViewById(R.id.residency_policy_restriction);
        if (app.safety().isRestricted()) {
            restriction.setText(ResidencyUiText.restriction(app.safety().restriction()));
            restriction.setVisibility(View.VISIBLE);
        } else {
            restriction.setVisibility(View.GONE);
        }
    }

    private ResidencyPolicyDraft restoreDraftOrDefault(ResidencyPolicyDraft fallback) {
        if (restoredDraft == null) return fallback;
        try {
            return ResidencyPolicyEditorContract.decodeDraft(packageName, restoredDraft);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private void selectMode(ResidencyMode mode) {
        selectedMode = mode;
        String[] labels = getResources().getStringArray(R.array.residency_policy_modes);
        modeSelector.setText(labels[mode.ordinal()], false);
        int summary = modeSummaryResource(mode);
        modeSummary.setText(summary);
        modeSelector.setContentDescription(getString(
                R.string.residency_mode_accessibility,
                labels[mode.ordinal()],
                getString(summary),
                getString(R.string.residency_mode_selected)));
        customSection.setVisibility(mode == ResidencyMode.CUSTOM ? View.VISIBLE : View.GONE);
        updateRiskNotices(mode);
    }

    private static int modeSummaryResource(ResidencyMode mode) {
        switch (mode) {
            case SYSTEM: return R.string.residency_mode_system_summary;
            case BALANCED: return R.string.residency_mode_balanced_summary;
            case LOW_POWER: return R.string.residency_mode_low_power_summary;
            case ACTIVE: return R.string.residency_mode_active_summary;
            case CUSTOM: return R.string.residency_mode_custom_summary;
            default: throw new AssertionError(mode);
        }
    }

    private void updateRiskNotices(ResidencyMode mode) {
        boolean active = mode == ResidencyMode.ACTIVE;
        boolean custom = mode == ResidencyMode.CUSTOM;
        riskNotices.setVisibility(active || custom ? View.VISIBLE : View.GONE);
        highRiskRow.setVisibility(active || custom ? View.VISIBLE : View.GONE);
        experimentalRiskRow.setVisibility(custom ? View.VISIBLE : View.GONE);
        highRiskSummary.setText(active
                ? R.string.residency_risk_active_summary
                : R.string.residency_risk_high_summary);
        experimentalRiskSummary.setText(R.string.residency_risk_experimental_summary);
    }

    private void showPresetDialog(ResidencyPolicyEditorContract.Section section) {
        if (workingDraft == null) return;
        String[] labels = presetLabels(section);
        int current = currentPresetIndex(section);
        new MaterialAlertDialogBuilder(this)
                .setTitle(sectionTitle(section))
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == labels.length - 1) {
                        launchSectionEditor(section);
                    } else {
                        applyPreset(section, which);
                        updateSectionRows();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchSectionEditor(ResidencyPolicyEditorContract.Section section) {
        pendingSection = section;
        sectionEditor.launch(ResidencyPolicyEditorContract.createIntent(
                this, packageName, section, workingDraft));
    }

    private void applyPreset(ResidencyPolicyEditorContract.Section section, int index) {
        switch (section) {
            case KILL:
                workingDraft = workingDraft.withKillPolicy(
                        ResidencySectionPresets.killPolicy(
                                ResidencySectionPresets.KillPreset.values()[index]));
                return;
            case FREEZE:
                workingDraft = workingDraft.withFreezePolicy(
                        ResidencySectionPresets.freezePolicy(
                                ResidencySectionPresets.FreezePreset.values()[index]));
                return;
            case WAKE:
                workingDraft = workingDraft.withWakePolicy(
                        ResidencySectionPresets.wakePolicy(
                                ResidencySectionPresets.WakePreset.values()[index]));
                return;
            case RESOURCE:
                workingDraft = workingDraft.withResourcePolicy(
                        ResidencySectionPresets.resourcePolicy(
                                ResidencySectionPresets.ResourcePreset.values()[index]));
                return;
            case REFREEZE:
                workingDraft = workingDraft.withRefreezePolicy(
                        ResidencySectionPresets.refreezePolicy(
                                ResidencySectionPresets.RefreezePreset.values()[index]));
                return;
            default:
                throw new AssertionError(section);
        }
    }

    private void updateSectionRows() {
        if (workingDraft == null) return;
        setPresetValue(R.id.residency_kill_preset_value,
                ResidencyPolicyEditorContract.Section.KILL);
        setPresetValue(R.id.residency_freeze_preset_value,
                ResidencyPolicyEditorContract.Section.FREEZE);
        setPresetValue(R.id.residency_wake_preset_value,
                ResidencyPolicyEditorContract.Section.WAKE);
        setPresetValue(R.id.residency_resource_preset_value,
                ResidencyPolicyEditorContract.Section.RESOURCE);
        setPresetValue(R.id.residency_refreeze_preset_value,
                ResidencyPolicyEditorContract.Section.REFREEZE);
    }

    private void setPresetValue(int viewId, ResidencyPolicyEditorContract.Section section) {
        String label = presetLabels(section)[currentPresetIndex(section)];
        ((TextView) findViewById(viewId)).setText(
                getString(R.string.residency_section_preset_value, label));
    }

    private int currentPresetIndex(ResidencyPolicyEditorContract.Section section) {
        switch (section) {
            case KILL:
                return ResidencySectionPresets.matchKillPolicy(
                        workingDraft.customKillPolicy()).ordinal();
            case FREEZE:
                return ResidencySectionPresets.matchFreezePolicy(
                        workingDraft.customFreezePolicy()).ordinal();
            case WAKE:
                return ResidencySectionPresets.matchWakePolicy(
                        workingDraft.customWakePolicy()).ordinal();
            case RESOURCE:
                return ResidencySectionPresets.matchResourcePolicy(
                        workingDraft.customResourcePolicy()).ordinal();
            case REFREEZE:
                return ResidencySectionPresets.matchRefreezePolicy(
                        workingDraft.customRefreezePolicy()).ordinal();
            default:
                throw new AssertionError(section);
        }
    }

    private String[] presetLabels(ResidencyPolicyEditorContract.Section section) {
        switch (section) {
            case KILL:
                return getResources().getStringArray(R.array.residency_kill_section_presets);
            case FREEZE:
                return getResources().getStringArray(R.array.residency_freeze_section_presets);
            case WAKE:
                return getResources().getStringArray(R.array.residency_wake_section_presets);
            case RESOURCE:
                return getResources().getStringArray(R.array.residency_resource_section_presets);
            case REFREEZE:
                return getResources().getStringArray(R.array.residency_refreeze_section_presets);
            default:
                throw new AssertionError(section);
        }
    }

    private static int sectionTitle(ResidencyPolicyEditorContract.Section section) {
        switch (section) {
            case KILL: return R.string.residency_kill_title;
            case FREEZE: return R.string.residency_freeze_title;
            case WAKE: return R.string.residency_wake_title;
            case RESOURCE: return R.string.residency_resource_title;
            case REFREEZE: return R.string.residency_refreeze_title;
            default: throw new AssertionError(section);
        }
    }

    private void updateApplyNotice() {
        if (applyNotice == null) return;
        applyNotice.setText(ResidencyApplyStatus.message(
                hookStatus,
                ModuleApplication.isServiceConnected(),
                ModuleSettings.getConfigRevision(this)));
    }

    private void save() {
        ResidencyPolicyDraft draft = snapshotDraft();
        if (!ResidencyPolicyFormValidator.isValid(packageName, draft)) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.residency_invalid_policy,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        viewModel.save(draft);
    }

    private ResidencyPolicyDraft snapshotDraft() {
        ResidencyPolicyDraft source = workingDraft == null ? loadedDraft : workingDraft;
        return new ResidencyPolicyDraft(
                enabledSwitch.isChecked(),
                selectedMode,
                source.customKillPolicy(),
                source.customFreezePolicy(),
                source.customWakePolicy(),
                source.customResourcePolicy(),
                source.customRefreezePolicy());
    }

    private void confirmDelete() {
        if (!existingRule || app == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.residency_delete_title, app.label()))
                .setMessage(R.string.residency_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.residency_delete, (dialog, which) -> viewModel.delete())
                .show();
    }

    private void setBusy(boolean busy) {
        boolean editable = app != null && !app.safety().isRestricted() && !busy;
        enabledSwitch.setEnabled(editable);
        modeSelector.setEnabled(editable);
        setEnabledRecursively(customSection, editable);
        saveButton.setEnabled(editable);
        deleteButton.setEnabled(existingRule && !busy);
        if (!busy) progress.setVisibility(View.GONE);
    }

    private static void setEnabledRecursively(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursively(group.getChildAt(index), enabled);
        }
    }

    static EnumSet<WakeSource> preserveUnmanagedWakeSources(Set<WakeSource> original) {
        return ResidencyPolicyDetailActivity.preserveUnmanagedWakeSources(original);
    }

    static boolean shouldRestoreFormState(Bundle state) {
        return state != null && state.getBoolean(STATE_FORM_READY, false);
    }

    static String formatSeconds(long milliseconds) {
        return ResidencyPolicyDetailActivity.formatSeconds(milliseconds);
    }

    private static ResidencyMode parseMode(String modeName) {
        if (modeName == null) return ResidencyMode.BALANCED;
        try {
            return ResidencyMode.valueOf(modeName);
        } catch (IllegalArgumentException ignored) {
            return ResidencyMode.BALANCED;
        }
    }

    private static ResidencyPolicyEditorContract.Section parseSection(String sectionName) {
        if (sectionName == null) return null;
        try {
            return ResidencyPolicyEditorContract.Section.valueOf(sectionName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
