package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.ui.HookStatusViewModel;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.EnumSet;
import java.util.Set;

/** Edits one complete application residency policy and publishes it atomically. */
public final class ResidencyPolicyActivity extends AppCompatActivity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String STATE_SELECTED_MODE = "selected_mode";
    private static final String STATE_FORM_READY = "form_ready";
    private String packageName;
    private ResidencyPolicyViewModel viewModel;
    private HookStatusViewModel hookStatusViewModel;
    private HookStatusViewModel.State hookStatus;
    private ResidencyAppIconLoader iconLoader;
    private ResidencyPolicyDraft loadedDraft;
    private ResidencyAppItem app;
    private boolean existingRule;
    private boolean bound;
    private boolean restoreFormState;
    private ResidencyMode selectedMode = ResidencyMode.BALANCED;

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
        restoreFormState = shouldRestoreFormState(savedInstanceState);
        if (savedInstanceState != null) {
            String modeName = savedInstanceState.getString(STATE_SELECTED_MODE);
            if (modeName != null) {
                try {
                    selectedMode = ResidencyMode.valueOf(modeName);
                } catch (IllegalArgumentException ignored) {
                    selectedMode = ResidencyMode.BALANCED;
                }
            }
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
        outState.putBoolean(STATE_FORM_READY, bound && app != null && loadedDraft != null);
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
        experimentalRiskSummary = findViewById(
                R.id.residency_risk_experimental_summary);
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

        ((MaterialSwitch) findViewById(R.id.residency_freeze_custom_timing))
                .setOnCheckedChangeListener((button, checked) ->
                        findViewById(R.id.residency_freeze_timing_fields).setVisibility(
                                checked ? View.VISIBLE : View.GONE));

        MaterialButtonToggleGroup packetGroup = findViewById(R.id.residency_packet_group);
        packetGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (checked) updateWakeCooldownVisibility();
        });
        MaterialButtonToggleGroup alarmGroup = findViewById(R.id.residency_alarm_group);
        alarmGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (checked) updateWakeCooldownVisibility();
        });
        ((RadioGroup) findViewById(R.id.residency_refreeze_group))
                .setOnCheckedChangeListener((group, checkedId) ->
                        findViewById(R.id.residency_refreeze_fields).setVisibility(
                                checkedId == R.id.residency_refreeze_after_wake
                                        ? View.VISIBLE : View.GONE));
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
            if (restoreFormState) {
                selectMode(selectedMode);
                restoreFormState = false;
            } else {
                enabledSwitch.setChecked(loadedDraft.enabled());
                selectMode(loadedDraft.mode());
                bindCustomPolicy(loadedDraft);
            }
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

    private void bindCustomPolicy(ResidencyPolicyDraft draft) {
        KillPolicy kill = draft.customKillPolicy();
        setChecked(R.id.residency_kill_power, kill.protectOguardKill());
        setChecked(R.id.residency_kill_force_stop, kill.protectOguardForceStop());
        setChecked(R.id.residency_kill_periodic, kill.protectAthenaPeriodic());
        setChecked(R.id.residency_kill_fast_clear, kill.protectFastClear());
        setChecked(R.id.residency_kill_tournament, kill.protectTournament());
        setChecked(R.id.residency_kill_memory, kill.protectMemoryGuard());
        setChecked(R.id.residency_kill_grouped, kill.protectNirvana());
        setChecked(R.id.residency_kill_process, kill.protectOsensePerProcess());

        FreezePolicy freeze = draft.customFreezePolicy();
        setChecked(R.id.residency_freeze_normal, freeze.allowNormalFreeze());
        setChecked(R.id.residency_freeze_fast, freeze.allowFastFreeze());
        setChecked(R.id.residency_freeze_super, freeze.allowSuperFreeze());
        setChecked(R.id.residency_freeze_preload, freeze.allowPreloadFreeze());
        setChecked(R.id.residency_freeze_custom_timing, freeze.customTiming());
        setDelay(R.id.residency_freeze_prepare_value, freeze.rToMDelayMs());
        setDelay(R.id.residency_freeze_execute_value, freeze.mToFDelayMs());
        setChecked(R.id.residency_freeze_full_exemption, freeze.fullExemption());
        setChecked(R.id.residency_freeze_core_exemption, freeze.coreExemption());
        setChecked(R.id.residency_freeze_lcd_exemption, freeze.lcdOnExemption());
        findViewById(R.id.residency_freeze_timing_fields).setVisibility(
                freeze.customTiming() ? View.VISIBLE : View.GONE);

        WakePolicy wake = draft.customWakePolicy();
        setWakeMode(R.id.residency_packet_group, wake.packet().mode(), true);
        setWakeMode(R.id.residency_alarm_group, wake.alarm().mode(), false);
        setDelay(R.id.residency_packet_cooldown_value, wake.packet().cooldownMs());
        setDelay(R.id.residency_alarm_cooldown_value, wake.alarm().cooldownMs());
        Set<WakeSource> blocked = wake.blockedSources();
        setChecked(R.id.residency_wake_async, blocked.contains(WakeSource.ASYNC_BINDER));
        setChecked(R.id.residency_wake_transaction,
                blocked.contains(WakeSource.TRANSACTION_BINDER));
        setChecked(R.id.residency_wake_signal, blocked.contains(WakeSource.SIGNAL));
        setChecked(R.id.residency_wake_service, blocked.contains(WakeSource.SERVICE));
        setChecked(R.id.residency_wake_broadcast, blocked.contains(WakeSource.BROADCAST));
        setChecked(R.id.residency_wake_job, blocked.contains(WakeSource.JOB_SYNC));
        setChecked(R.id.residency_wake_wakelock, blocked.contains(WakeSource.WAKE_LOCK));
        setChecked(R.id.residency_wake_audio, blocked.contains(WakeSource.AUDIO_MEDIA));
        setChecked(R.id.residency_wake_connectivity,
                blocked.contains(WakeSource.CONNECTIVITY));
        updateWakeCooldownVisibility();

        ResourcePolicy resources = draft.customResourcePolicy();
        setChecked(R.id.residency_resource_network, resources.keepNetwork());
        setChecked(R.id.residency_resource_service, resources.keeps(ResourceType.SERVICE));
        setChecked(R.id.residency_resource_broadcast, resources.keeps(ResourceType.BROADCAST));
        setChecked(R.id.residency_resource_job, resources.keeps(ResourceType.JOB));
        setChecked(R.id.residency_resource_sensor, resources.keeps(ResourceType.SENSOR));
        setChecked(R.id.residency_resource_binder, resources.keeps(ResourceType.BINDER));
        setChecked(R.id.residency_resource_alarm, resources.keeps(ResourceType.ALARM));
        setChecked(R.id.residency_resource_wakelock, resources.keeps(ResourceType.WAKE_LOCK));
        setChecked(R.id.residency_resource_gps, resources.keeps(ResourceType.GPS));
        setChecked(R.id.residency_resource_audio, resources.keeps(ResourceType.AUDIO));
        setChecked(R.id.residency_resource_bluetooth,
                resources.keeps(ResourceType.BLUETOOTH_SCAN));

        RefreezePolicy refreeze = draft.customRefreezePolicy();
        int refreezeId = refreeze.mode() == RefreezeMode.AFTER_WAKE
                ? R.id.residency_refreeze_after_wake
                : refreeze.mode() == RefreezeMode.DISABLED
                        ? R.id.residency_refreeze_disabled
                        : R.id.residency_refreeze_system;
        ((RadioGroup) findViewById(R.id.residency_refreeze_group)).check(refreezeId);
        setDelay(R.id.residency_refreeze_default_value, refreeze.defaultDelayMs());
        setDelay(R.id.residency_refreeze_packet_value, refreeze.packetDelayMs());
        setDelay(R.id.residency_refreeze_alarm_value, refreeze.alarmDelayMs());
        setDelay(R.id.residency_refreeze_binder_value, refreeze.binderDelayMs());
        setDelay(R.id.residency_refreeze_service_value, refreeze.serviceDelayMs());
        setDelay(R.id.residency_refreeze_broadcast_value, refreeze.broadcastDelayMs());
        setDelay(R.id.residency_refreeze_job_value, refreeze.jobDelayMs());
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

    private void updateApplyNotice() {
        if (applyNotice == null) return;
        applyNotice.setText(ResidencyApplyStatus.message(
                hookStatus,
                ModuleApplication.isServiceConnected(),
                ModuleSettings.getConfigRevision(this)));
    }

    private void save() {
        ResidencyPolicyDraft draft;
        try {
            draft = collectDraft();
        } catch (InvalidFormException exception) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.residency_invalid_policy,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        if (!ResidencyPolicyFormValidator.isValid(packageName, draft)) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.residency_invalid_policy,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        viewModel.save(draft);
    }

    private ResidencyPolicyDraft collectDraft() throws InvalidFormException {
        if (selectedMode != ResidencyMode.CUSTOM) {
            return new ResidencyPolicyDraft(
                    enabledSwitch.isChecked(),
                    selectedMode,
                    loadedDraft.customKillPolicy(),
                    loadedDraft.customFreezePolicy(),
                    loadedDraft.customWakePolicy(),
                    loadedDraft.customResourcePolicy(),
                    loadedDraft.customRefreezePolicy());
        }

        KillPolicy kill = new KillPolicy(
                isChecked(R.id.residency_kill_power),
                isChecked(R.id.residency_kill_force_stop),
                isChecked(R.id.residency_kill_periodic),
                isChecked(R.id.residency_kill_fast_clear),
                isChecked(R.id.residency_kill_tournament),
                isChecked(R.id.residency_kill_memory),
                isChecked(R.id.residency_kill_grouped),
                isChecked(R.id.residency_kill_process));

        boolean customTiming = isChecked(R.id.residency_freeze_custom_timing);
        FreezePolicy freeze = new FreezePolicy(
                isChecked(R.id.residency_freeze_normal),
                isChecked(R.id.residency_freeze_fast),
                isChecked(R.id.residency_freeze_super),
                isChecked(R.id.residency_freeze_preload),
                customTiming,
                readDelay(
                        R.id.residency_freeze_prepare_layout,
                        R.id.residency_freeze_prepare_value,
                        customTiming),
                readDelay(
                        R.id.residency_freeze_execute_layout,
                        R.id.residency_freeze_execute_value,
                        customTiming),
                isChecked(R.id.residency_freeze_full_exemption),
                isChecked(R.id.residency_freeze_core_exemption),
                isChecked(R.id.residency_freeze_lcd_exemption));

        WakeMode packetMode = readWakeMode(R.id.residency_packet_group, true);
        WakeMode alarmMode = readWakeMode(R.id.residency_alarm_group, false);
        WakeChannelPolicy packet = new WakeChannelPolicy(
                packetMode,
                readDelay(
                        R.id.residency_packet_cooldown_layout,
                        R.id.residency_packet_cooldown_value,
                        packetMode == WakeMode.THROTTLE));
        WakeChannelPolicy alarm = new WakeChannelPolicy(
                alarmMode,
                readDelay(
                        R.id.residency_alarm_cooldown_layout,
                        R.id.residency_alarm_cooldown_value,
                        alarmMode == WakeMode.THROTTLE));
        EnumSet<WakeSource> blockedSources = preserveUnmanagedWakeSources(
                loadedDraft.customWakePolicy().blockedSources());
        addIfChecked(blockedSources, R.id.residency_wake_async, WakeSource.ASYNC_BINDER);
        addIfChecked(
                blockedSources, R.id.residency_wake_transaction, WakeSource.TRANSACTION_BINDER);
        addIfChecked(blockedSources, R.id.residency_wake_signal, WakeSource.SIGNAL);
        addIfChecked(blockedSources, R.id.residency_wake_service, WakeSource.SERVICE);
        addIfChecked(blockedSources, R.id.residency_wake_broadcast, WakeSource.BROADCAST);
        addIfChecked(blockedSources, R.id.residency_wake_job, WakeSource.JOB_SYNC);
        addIfChecked(blockedSources, R.id.residency_wake_wakelock, WakeSource.WAKE_LOCK);
        addIfChecked(blockedSources, R.id.residency_wake_audio, WakeSource.AUDIO_MEDIA);
        addIfChecked(
                blockedSources, R.id.residency_wake_connectivity, WakeSource.CONNECTIVITY);
        WakePolicy wake = new WakePolicy(packet, alarm, blockedSources);

        EnumSet<ResourceType> keptResources = EnumSet.noneOf(ResourceType.class);
        addIfChecked(keptResources, R.id.residency_resource_service, ResourceType.SERVICE);
        addIfChecked(keptResources, R.id.residency_resource_broadcast, ResourceType.BROADCAST);
        addIfChecked(keptResources, R.id.residency_resource_job, ResourceType.JOB);
        addIfChecked(keptResources, R.id.residency_resource_sensor, ResourceType.SENSOR);
        addIfChecked(keptResources, R.id.residency_resource_binder, ResourceType.BINDER);
        addIfChecked(keptResources, R.id.residency_resource_alarm, ResourceType.ALARM);
        addIfChecked(keptResources, R.id.residency_resource_wakelock, ResourceType.WAKE_LOCK);
        addIfChecked(keptResources, R.id.residency_resource_gps, ResourceType.GPS);
        addIfChecked(keptResources, R.id.residency_resource_audio, ResourceType.AUDIO);
        addIfChecked(
                keptResources, R.id.residency_resource_bluetooth, ResourceType.BLUETOOTH_SCAN);
        ResourcePolicy resources = new ResourcePolicy(
                isChecked(R.id.residency_resource_network), keptResources);

        RefreezeMode refreezeMode = readRefreezeMode();
        boolean requireRefreezeDelays = refreezeMode == RefreezeMode.AFTER_WAKE;
        RefreezePolicy refreeze = new RefreezePolicy(
                refreezeMode,
                readDelay(
                        R.id.residency_refreeze_default_layout,
                        R.id.residency_refreeze_default_value,
                        requireRefreezeDelays),
                readDelay(
                        R.id.residency_refreeze_packet_layout,
                        R.id.residency_refreeze_packet_value,
                        requireRefreezeDelays),
                readDelay(
                        R.id.residency_refreeze_alarm_layout,
                        R.id.residency_refreeze_alarm_value,
                        requireRefreezeDelays),
                readDelay(
                        R.id.residency_refreeze_binder_layout,
                        R.id.residency_refreeze_binder_value,
                        requireRefreezeDelays),
                readDelay(
                        R.id.residency_refreeze_service_layout,
                        R.id.residency_refreeze_service_value,
                        requireRefreezeDelays),
                readDelay(
                        R.id.residency_refreeze_broadcast_layout,
                        R.id.residency_refreeze_broadcast_value,
                        requireRefreezeDelays),
                readDelay(
                        R.id.residency_refreeze_job_layout,
                        R.id.residency_refreeze_job_value,
                        requireRefreezeDelays));

        return new ResidencyPolicyDraft(
                enabledSwitch.isChecked(),
                selectedMode,
                kill,
                freeze,
                wake,
                resources,
                refreeze);
    }

    static EnumSet<WakeSource> preserveUnmanagedWakeSources(Set<WakeSource> original) {
        EnumSet<WakeSource> preserved = EnumSet.noneOf(WakeSource.class);
        preserved.addAll(original);
        preserved.remove(WakeSource.ASYNC_BINDER);
        preserved.remove(WakeSource.TRANSACTION_BINDER);
        preserved.remove(WakeSource.SIGNAL);
        preserved.remove(WakeSource.SERVICE);
        preserved.remove(WakeSource.BROADCAST);
        preserved.remove(WakeSource.JOB_SYNC);
        preserved.remove(WakeSource.WAKE_LOCK);
        preserved.remove(WakeSource.AUDIO_MEDIA);
        preserved.remove(WakeSource.CONNECTIVITY);
        return preserved;
    }

    static boolean shouldRestoreFormState(Bundle state) {
        return state != null && state.getBoolean(STATE_FORM_READY, false);
    }

    private long readDelay(int layoutId, int valueId, boolean required)
            throws InvalidFormException {
        TextInputLayout layout = findViewById(layoutId);
        TextInputEditText input = findViewById(valueId);
        layout.setError(null);
        try {
            return ResidencyPolicyFormValidator.parseSeconds(
                    input.getText() == null ? "" : input.getText().toString(), required);
        } catch (ResidencyPolicyFormValidator.InputException exception) {
            int error = exception.failure() == ResidencyPolicyFormValidator.InputFailure.REQUIRED
                    ? R.string.residency_input_required
                    : exception.failure() == ResidencyPolicyFormValidator.InputFailure.NOT_A_NUMBER
                            ? R.string.residency_input_number
                            : R.string.residency_input_range;
            layout.setError(getString(error));
            input.requestFocus();
            throw new InvalidFormException();
        }
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
        setModeControlsEnabled(editable);
        setEnabledRecursively(customSection, editable);
        saveButton.setEnabled(editable);
        deleteButton.setEnabled(existingRule && !busy);
        if (!busy) progress.setVisibility(View.GONE);
    }

    private void setModeControlsEnabled(boolean enabled) {
        modeSelector.setEnabled(enabled);
    }

    private static void setEnabledRecursively(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursively(group.getChildAt(index), enabled);
        }
    }

    private void updateWakeCooldownVisibility() {
        MaterialButtonToggleGroup packet = findViewById(R.id.residency_packet_group);
        MaterialButtonToggleGroup alarm = findViewById(R.id.residency_alarm_group);
        findViewById(R.id.residency_packet_cooldown_layout).setVisibility(
                packet.getCheckedButtonId() == R.id.residency_packet_throttle
                        ? View.VISIBLE : View.GONE);
        findViewById(R.id.residency_alarm_cooldown_layout).setVisibility(
                alarm.getCheckedButtonId() == R.id.residency_alarm_throttle
                        ? View.VISIBLE : View.GONE);
    }

    private void setWakeMode(int groupId, WakeMode mode, boolean packet) {
        int checkedId;
        if (packet) {
            checkedId = mode == WakeMode.THROTTLE
                    ? R.id.residency_packet_throttle
                    : mode == WakeMode.BLOCK
                            ? R.id.residency_packet_block
                            : R.id.residency_packet_allow;
        } else {
            checkedId = mode == WakeMode.THROTTLE
                    ? R.id.residency_alarm_throttle
                    : mode == WakeMode.BLOCK
                            ? R.id.residency_alarm_block
                            : R.id.residency_alarm_allow;
        }
        ((MaterialButtonToggleGroup) findViewById(groupId)).check(checkedId);
    }

    private WakeMode readWakeMode(int groupId, boolean packet) {
        int checked = ((MaterialButtonToggleGroup) findViewById(groupId)).getCheckedButtonId();
        if (checked == (packet ? R.id.residency_packet_throttle : R.id.residency_alarm_throttle)) {
            return WakeMode.THROTTLE;
        }
        if (checked == (packet ? R.id.residency_packet_block : R.id.residency_alarm_block)) {
            return WakeMode.BLOCK;
        }
        return WakeMode.ALLOW;
    }

    private RefreezeMode readRefreezeMode() {
        int checked = ((RadioGroup) findViewById(R.id.residency_refreeze_group))
                .getCheckedRadioButtonId();
        if (checked == R.id.residency_refreeze_after_wake) return RefreezeMode.AFTER_WAKE;
        if (checked == R.id.residency_refreeze_disabled) return RefreezeMode.DISABLED;
        return RefreezeMode.SYSTEM;
    }

    private void setChecked(int id, boolean checked) {
        ((MaterialSwitch) findViewById(id)).setChecked(checked);
    }

    private boolean isChecked(int id) {
        return ((MaterialSwitch) findViewById(id)).isChecked();
    }

    private void setDelay(int id, long milliseconds) {
        ((TextInputEditText) findViewById(id)).setText(formatSeconds(milliseconds));
    }

    static String formatSeconds(long milliseconds) {
        return milliseconds == 0L ? "" : java.math.BigDecimal.valueOf(milliseconds, 3)
                .stripTrailingZeros()
                .toPlainString();
    }

    private <E extends Enum<E>> void addIfChecked(Set<E> destination, int id, E value) {
        if (isChecked(id)) destination.add(value);
    }

    private static final class InvalidFormException extends Exception {}
}
