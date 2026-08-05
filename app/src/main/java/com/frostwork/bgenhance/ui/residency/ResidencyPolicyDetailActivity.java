package com.frostwork.bgenhance.ui.residency;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencySectionPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import com.frostwork.bgenhance.ui.UiUtils;
import com.frostwork.bgenhance.ui.HookStatusViewModel;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.EnumSet;
import java.util.Set;

/** Edits one custom policy section without exposing unrelated low-level controls. */
public final class ResidencyPolicyDetailActivity extends AppCompatActivity {
    private String packageName;
    private ResidencyPolicyEditorContract.Section section;
    private ResidencyPolicyDraft draft;
    private HookStatusViewModel hookStatusViewModel;
    private HookStatusViewModel.State hookStatus;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ResidencyPolicyEditorContract.Request request;
        try {
            request = ResidencyPolicyEditorContract.readRequest(getIntent());
        } catch (IllegalArgumentException exception) {
            finish();
            return;
        }
        packageName = request.packageName();
        section = request.section();
        draft = request.draft();

        setContentView(R.layout.activity_residency_policy_detail);
        UiUtils.applyWindowStyle(this);
        bindInteractions();
        showSection();
        bindDraft();
        hookStatusViewModel = HookStatusViewModel.create(this);
        hookStatusViewModel.setListener(state -> {
            hookStatus = state;
            applyHookAvailability();
        });
        hookStatus = hookStatusViewModel.state();
        applyHookAvailability();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hookStatusViewModel != null) hookStatusViewModel.refresh();
    }

    @Override
    protected void onDestroy() {
        if (hookStatusViewModel != null) hookStatusViewModel.close();
        super.onDestroy();
    }

    private void bindInteractions() {
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        findViewById(R.id.residency_detail_done).setOnClickListener(view -> finishWithResult());

        ((MaterialSwitch) findViewById(R.id.residency_freeze_custom_timing))
                .setOnCheckedChangeListener((button, checked) -> {
                    if (!binding) updateFreezeControlState();
                });
        ((MaterialSwitch) findViewById(R.id.residency_freeze_full_exemption))
                .setOnCheckedChangeListener((button, checked) -> {
                    if (!binding) updateFreezeControlState();
                });

        int[] availabilitySwitches = {
            R.id.residency_kill_power,
            R.id.residency_kill_force_stop,
            R.id.residency_kill_periodic,
            R.id.residency_kill_fast_clear,
            R.id.residency_kill_tournament,
            R.id.residency_kill_memory,
            R.id.residency_kill_grouped,
            R.id.residency_kill_process,
            R.id.residency_freeze_normal,
            R.id.residency_freeze_fast,
            R.id.residency_freeze_super,
            R.id.residency_freeze_preload,
            R.id.residency_freeze_core_exemption,
            R.id.residency_freeze_lcd_exemption,
            R.id.residency_wake_async,
            R.id.residency_wake_transaction,
            R.id.residency_wake_signal,
            R.id.residency_wake_service,
            R.id.residency_wake_broadcast,
            R.id.residency_wake_job,
            R.id.residency_wake_wakelock,
            R.id.residency_wake_audio,
            R.id.residency_wake_connectivity,
            R.id.residency_resource_network,
            R.id.residency_resource_service,
            R.id.residency_resource_broadcast,
            R.id.residency_resource_job,
            R.id.residency_resource_sensor,
            R.id.residency_resource_binder,
            R.id.residency_resource_alarm,
            R.id.residency_resource_wakelock,
            R.id.residency_resource_gps,
            R.id.residency_resource_audio,
            R.id.residency_resource_bluetooth
        };
        for (int id : availabilitySwitches) {
            ((MaterialSwitch) findViewById(id)).setOnCheckedChangeListener(
                    (button, checked) -> {
                        if (!binding) applyHookAvailability();
                    });
        }

        ((MaterialButtonToggleGroup) findViewById(R.id.residency_packet_group))
                .addOnButtonCheckedListener((group, checkedId, checked) -> {
                    if (checked && !binding) {
                        updateWakeCooldownVisibility();
                        applyHookAvailability();
                    }
                });
        ((MaterialButtonToggleGroup) findViewById(R.id.residency_alarm_group))
                .addOnButtonCheckedListener((group, checkedId, checked) -> {
                    if (checked && !binding) {
                        updateWakeCooldownVisibility();
                        applyHookAvailability();
                    }
                });
        ((RadioGroup) findViewById(R.id.residency_refreeze_group))
                .setOnCheckedChangeListener((group, checkedId) -> {
                    if (checkedId == R.id.residency_refreeze_after_wake && !binding) {
                        fillDefaultRefreezeDelaysIfBlank();
                    }
                    findViewById(R.id.residency_refreeze_fields).setVisibility(
                            checkedId == R.id.residency_refreeze_after_wake
                                    ? View.VISIBLE : View.GONE);
                    if (!binding) applyHookAvailability();
                });
    }

    private void showSection() {
        int visibleId;
        int title;
        switch (section) {
            case KILL:
                visibleId = R.id.residency_detail_kill;
                title = R.string.residency_kill_title;
                break;
            case FREEZE:
                visibleId = R.id.residency_detail_freeze;
                title = R.string.residency_freeze_title;
                break;
            case WAKE:
                visibleId = R.id.residency_detail_wake;
                title = R.string.residency_wake_title;
                break;
            case RESOURCE:
                visibleId = R.id.residency_detail_resource;
                title = R.string.residency_resource_title;
                break;
            case REFREEZE:
                visibleId = R.id.residency_detail_refreeze;
                title = R.string.residency_refreeze_title;
                break;
            default:
                throw new AssertionError(section);
        }
        findViewById(visibleId).setVisibility(View.VISIBLE);
        ((android.widget.TextView) findViewById(R.id.residency_detail_title)).setText(title);
    }

    private void bindDraft() {
        binding = true;
        bindKill(draft.customKillPolicy());
        bindFreeze(draft.customFreezePolicy());
        bindWake(draft.customWakePolicy());
        bindResources(draft.customResourcePolicy());
        bindRefreeze(draft.customRefreezePolicy());
        binding = false;
        updateFreezeControlState();
        updateWakeCooldownVisibility();
    }

    private void bindKill(KillPolicy policy) {
        setChecked(R.id.residency_kill_power, policy.protectOguardKill());
        setChecked(R.id.residency_kill_force_stop, policy.protectOguardForceStop());
        setChecked(R.id.residency_kill_periodic, policy.protectAthenaPeriodic());
        setChecked(R.id.residency_kill_fast_clear, policy.protectFastClear());
        setChecked(R.id.residency_kill_tournament, policy.protectTournament());
        setChecked(R.id.residency_kill_memory, policy.protectMemoryGuard());
        setChecked(R.id.residency_kill_grouped, policy.protectNirvana());
        setChecked(R.id.residency_kill_process, policy.protectOsensePerProcess());
    }

    private void bindFreeze(FreezePolicy policy) {
        setChecked(R.id.residency_freeze_normal, policy.allowNormalFreeze());
        setChecked(R.id.residency_freeze_fast, policy.allowFastFreeze());
        setChecked(R.id.residency_freeze_super, policy.allowSuperFreeze());
        setChecked(R.id.residency_freeze_preload, policy.allowPreloadFreeze());
        setChecked(R.id.residency_freeze_custom_timing, policy.customTiming());
        setDelay(R.id.residency_freeze_prepare_value, policy.rToMDelayMs());
        setDelay(R.id.residency_freeze_execute_value, policy.mToFDelayMs());
        setChecked(R.id.residency_freeze_full_exemption, policy.fullExemption());
        setChecked(R.id.residency_freeze_core_exemption, policy.coreExemption());
        setChecked(R.id.residency_freeze_lcd_exemption, policy.lcdOnExemption());
    }

    private void bindWake(WakePolicy policy) {
        setWakeMode(R.id.residency_packet_group, policy.packet().mode(), true);
        setWakeMode(R.id.residency_alarm_group, policy.alarm().mode(), false);
        setDelay(R.id.residency_packet_cooldown_value, policy.packet().cooldownMs());
        setDelay(R.id.residency_alarm_cooldown_value, policy.alarm().cooldownMs());
        Set<WakeSource> blocked = policy.blockedSources();
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
    }

    private void bindResources(ResourcePolicy policy) {
        setChecked(R.id.residency_resource_network, policy.keepNetwork());
        setChecked(R.id.residency_resource_service, policy.keeps(ResourceType.SERVICE));
        setChecked(R.id.residency_resource_broadcast, policy.keeps(ResourceType.BROADCAST));
        setChecked(R.id.residency_resource_job, policy.keeps(ResourceType.JOB));
        setChecked(R.id.residency_resource_sensor, policy.keeps(ResourceType.SENSOR));
        setChecked(R.id.residency_resource_binder, policy.keeps(ResourceType.BINDER));
        setChecked(R.id.residency_resource_alarm, policy.keeps(ResourceType.ALARM));
        setChecked(R.id.residency_resource_wakelock, policy.keeps(ResourceType.WAKE_LOCK));
        setChecked(R.id.residency_resource_gps, policy.keeps(ResourceType.GPS));
        setChecked(R.id.residency_resource_audio, policy.keeps(ResourceType.AUDIO));
        setChecked(R.id.residency_resource_bluetooth,
                policy.keeps(ResourceType.BLUETOOTH_SCAN));
    }

    private void bindRefreeze(RefreezePolicy policy) {
        int checkedId = policy.mode() == RefreezeMode.AFTER_WAKE
                ? R.id.residency_refreeze_after_wake
                : policy.mode() == RefreezeMode.DISABLED
                        ? R.id.residency_refreeze_disabled
                        : R.id.residency_refreeze_system;
        ((RadioGroup) findViewById(R.id.residency_refreeze_group)).check(checkedId);
        setDelay(R.id.residency_refreeze_default_value, policy.defaultDelayMs());
        setDelay(R.id.residency_refreeze_packet_value, policy.packetDelayMs());
        setDelay(R.id.residency_refreeze_alarm_value, policy.alarmDelayMs());
        setDelay(R.id.residency_refreeze_binder_value, policy.binderDelayMs());
        setDelay(R.id.residency_refreeze_service_value, policy.serviceDelayMs());
        setDelay(R.id.residency_refreeze_broadcast_value, policy.broadcastDelayMs());
        setDelay(R.id.residency_refreeze_job_value, policy.jobDelayMs());
        findViewById(R.id.residency_refreeze_fields).setVisibility(
                policy.mode() == RefreezeMode.AFTER_WAKE ? View.VISIBLE : View.GONE);
    }

    private void finishWithResult() {
        try {
            ResidencyPolicyDraft updated;
            switch (section) {
                case KILL:
                    updated = draft.withKillPolicy(collectKill());
                    break;
                case FREEZE:
                    updated = draft.withFreezePolicy(collectFreeze());
                    break;
                case WAKE:
                    updated = draft.withWakePolicy(collectWake());
                    break;
                case RESOURCE:
                    updated = draft.withResourcePolicy(collectResources());
                    break;
                case REFREEZE:
                    updated = draft.withRefreezePolicy(collectRefreeze());
                    break;
                default:
                    throw new AssertionError(section);
            }
            if (!ResidencyPolicyFormValidator.isValid(packageName, updated)) {
                throw new InvalidFormException();
            }
            setResult(RESULT_OK,
                    ResidencyPolicyEditorContract.createResult(packageName, section, updated));
            finish();
        } catch (InvalidFormException exception) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.residency_invalid_policy,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    private KillPolicy collectKill() {
        return new KillPolicy(
                isChecked(R.id.residency_kill_power),
                isChecked(R.id.residency_kill_force_stop),
                isChecked(R.id.residency_kill_periodic),
                isChecked(R.id.residency_kill_fast_clear),
                isChecked(R.id.residency_kill_tournament),
                isChecked(R.id.residency_kill_memory),
                isChecked(R.id.residency_kill_grouped),
                isChecked(R.id.residency_kill_process));
    }

    private FreezePolicy collectFreeze() throws InvalidFormException {
        if (isChecked(R.id.residency_freeze_full_exemption)) {
            return ResidencySectionPresets.freezePolicy(
                    ResidencySectionPresets.FreezePreset.NEVER_FREEZE);
        }
        boolean customTiming = isChecked(R.id.residency_freeze_custom_timing);
        return new FreezePolicy(
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
                false,
                isChecked(R.id.residency_freeze_core_exemption),
                isChecked(R.id.residency_freeze_lcd_exemption));
    }

    private WakePolicy collectWake() throws InvalidFormException {
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
        EnumSet<WakeSource> blocked = preserveUnmanagedWakeSources(
                draft.customWakePolicy().blockedSources());
        addIfChecked(blocked, R.id.residency_wake_async, WakeSource.ASYNC_BINDER);
        addIfChecked(blocked, R.id.residency_wake_transaction, WakeSource.TRANSACTION_BINDER);
        addIfChecked(blocked, R.id.residency_wake_signal, WakeSource.SIGNAL);
        addIfChecked(blocked, R.id.residency_wake_service, WakeSource.SERVICE);
        addIfChecked(blocked, R.id.residency_wake_broadcast, WakeSource.BROADCAST);
        addIfChecked(blocked, R.id.residency_wake_job, WakeSource.JOB_SYNC);
        addIfChecked(blocked, R.id.residency_wake_wakelock, WakeSource.WAKE_LOCK);
        addIfChecked(blocked, R.id.residency_wake_audio, WakeSource.AUDIO_MEDIA);
        addIfChecked(blocked, R.id.residency_wake_connectivity, WakeSource.CONNECTIVITY);
        return new WakePolicy(packet, alarm, blocked);
    }

    private ResourcePolicy collectResources() {
        EnumSet<ResourceType> resources = EnumSet.noneOf(ResourceType.class);
        addIfChecked(resources, R.id.residency_resource_service, ResourceType.SERVICE);
        addIfChecked(resources, R.id.residency_resource_broadcast, ResourceType.BROADCAST);
        addIfChecked(resources, R.id.residency_resource_job, ResourceType.JOB);
        addIfChecked(resources, R.id.residency_resource_sensor, ResourceType.SENSOR);
        addIfChecked(resources, R.id.residency_resource_binder, ResourceType.BINDER);
        addIfChecked(resources, R.id.residency_resource_alarm, ResourceType.ALARM);
        addIfChecked(resources, R.id.residency_resource_wakelock, ResourceType.WAKE_LOCK);
        addIfChecked(resources, R.id.residency_resource_gps, ResourceType.GPS);
        addIfChecked(resources, R.id.residency_resource_audio, ResourceType.AUDIO);
        addIfChecked(resources, R.id.residency_resource_bluetooth, ResourceType.BLUETOOTH_SCAN);
        return new ResourcePolicy(isChecked(R.id.residency_resource_network), resources);
    }

    private RefreezePolicy collectRefreeze() throws InvalidFormException {
        RefreezeMode mode = readRefreezeMode();
        boolean required = mode == RefreezeMode.AFTER_WAKE;
        return new RefreezePolicy(
                mode,
                readDelay(R.id.residency_refreeze_default_layout,
                        R.id.residency_refreeze_default_value, required),
                readDelay(R.id.residency_refreeze_packet_layout,
                        R.id.residency_refreeze_packet_value, required),
                readDelay(R.id.residency_refreeze_alarm_layout,
                        R.id.residency_refreeze_alarm_value, required),
                readDelay(R.id.residency_refreeze_binder_layout,
                        R.id.residency_refreeze_binder_value, required),
                readDelay(R.id.residency_refreeze_service_layout,
                        R.id.residency_refreeze_service_value, required),
                readDelay(R.id.residency_refreeze_broadcast_layout,
                        R.id.residency_refreeze_broadcast_value, required),
                readDelay(R.id.residency_refreeze_job_layout,
                        R.id.residency_refreeze_job_value, required));
    }

    private void updateFreezeControlState() {
        boolean exempt = isChecked(R.id.residency_freeze_full_exemption);
        int[] controlled = {
            R.id.residency_freeze_normal,
            R.id.residency_freeze_fast,
            R.id.residency_freeze_super,
            R.id.residency_freeze_preload,
            R.id.residency_freeze_custom_timing,
            R.id.residency_freeze_lcd_exemption
        };
        for (int id : controlled) findViewById(id).setEnabled(!exempt);
        boolean timing = !exempt && isChecked(R.id.residency_freeze_custom_timing);
        findViewById(R.id.residency_freeze_timing_fields).setVisibility(
                timing ? View.VISIBLE : View.GONE);
        applyHookAvailability();
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

    private void applyHookAvailability() {
        if (section == null) return;
        boolean partial;
        switch (section) {
            case KILL:
                partial = applyKillAvailability();
                break;
            case FREEZE:
                partial = applyFreezeAvailability();
                break;
            case WAKE:
                partial = applyWakeAvailability();
                break;
            case RESOURCE:
                partial = applyResourceAvailability();
                break;
            case REFREEZE:
                partial = applyRefreezeAvailability();
                break;
            default:
                throw new AssertionError(section);
        }
        findViewById(R.id.residency_detail_support_notice).setVisibility(
                partial ? View.VISIBLE : View.GONE);
    }

    private boolean applyKillAvailability() {
        boolean partial = false;
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_power, HookTargetIds.OGUARD_KILL, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_force_stop, HookTargetIds.OGUARD_FORCE_STOP, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_periodic, HookTargetIds.ATHENA_PERIODIC, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_fast_clear, HookTargetIds.FAST_CLEAR_DISPATCH, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_tournament, HookTargetIds.TOURNAMENT, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_memory, HookTargetIds.MEMORY_GUARD_DISPATCH, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_grouped, HookTargetIds.OSENSE_GROUP_CLEAN, true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_kill_process, HookTargetIds.OSENSE_PROCESS_KILL, true);
        return partial;
    }

    private boolean applyFreezeAvailability() {
        boolean exempt = isChecked(R.id.residency_freeze_full_exemption);
        boolean timing = !exempt && isChecked(R.id.residency_freeze_custom_timing);
        boolean rToMUnavailable = isHookUnavailable(HookTargetIds.HANS_R_TO_M_TIMING);
        boolean mToFUnavailable = isHookUnavailable(HookTargetIds.HANS_M_TO_F_TIMING);
        boolean partial = false;
        partial |= setHookSwitchEnabled(
                R.id.residency_freeze_normal,
                HookTargetIds.HANS_FREEZE_DISPATCH,
                !exempt,
                false);
        partial |= setHookSwitchEnabled(
                R.id.residency_freeze_fast,
                HookTargetIds.HANS_FAST_FREEZE_DISPATCH,
                !exempt,
                false);
        partial |= setHookSwitchEnabled(
                R.id.residency_freeze_super,
                HookTargetIds.HANS_FREEZE_DISPATCH,
                !exempt,
                false);
        partial |= setHookSwitchEnabled(
                R.id.residency_freeze_preload,
                HookTargetIds.HANS_FREEZE_DISPATCH,
                !exempt,
                false);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_freeze_full_exemption,
                HookTargetIds.HANS_FREEZE_DISPATCH,
                true);
        partial |= setProtectionSwitchEnabled(
                R.id.residency_freeze_core_exemption,
                HookTargetIds.HANS_CORE_EXEMPTION,
                !exempt);
        boolean timingUnavailable = rToMUnavailable || mToFUnavailable;
        setSwitchEnabledForAvailability(
                R.id.residency_freeze_custom_timing,
                !exempt,
                timingUnavailable,
                true);
        setEnabledRecursively(
                findViewById(R.id.residency_freeze_prepare_layout),
                timing && !rToMUnavailable);
        setEnabledRecursively(
                findViewById(R.id.residency_freeze_execute_layout),
                timing && !mToFUnavailable);
        partial |= rToMUnavailable || mToFUnavailable;
        partial |= setProtectionSwitchEnabled(
                R.id.residency_freeze_lcd_exemption,
                HookTargetIds.HANS_LCD_ON_EXEMPTION,
                !exempt);
        return partial;
    }

    private boolean applyWakeAvailability() {
        boolean partial = false;
        partial |= setWakeChannelEnabled(
                R.id.residency_packet_group,
                R.id.residency_packet_allow,
                R.id.residency_packet_throttle,
                R.id.residency_packet_block,
                HookTargetIds.HANS_WAKE_DISPATCH);
        partial |= setHookEnabled(
                R.id.residency_packet_cooldown_layout,
                HookTargetIds.HANS_WAKE_DISPATCH,
                true);
        partial |= setWakeChannelEnabled(
                R.id.residency_alarm_group,
                R.id.residency_alarm_allow,
                R.id.residency_alarm_throttle,
                R.id.residency_alarm_block,
                HookTargetIds.ALARM_WAKE_DELIVERY);
        partial |= setHookEnabled(
                R.id.residency_alarm_cooldown_layout,
                HookTargetIds.ALARM_WAKE_DELIVERY,
                true);
        int[] sourceControls = {
            R.id.residency_wake_async,
            R.id.residency_wake_transaction,
            R.id.residency_wake_signal,
            R.id.residency_wake_service,
            R.id.residency_wake_broadcast,
            R.id.residency_wake_job,
            R.id.residency_wake_wakelock,
            R.id.residency_wake_audio,
            R.id.residency_wake_connectivity
        };
        for (int id : sourceControls) {
            partial |= setProtectionSwitchEnabled(
                    id, HookTargetIds.HANS_WAKE_DISPATCH, true);
        }
        return partial;
    }

    private boolean applyResourceAvailability() {
        int[] controls = {
            R.id.residency_resource_network,
            R.id.residency_resource_service,
            R.id.residency_resource_broadcast,
            R.id.residency_resource_job,
            R.id.residency_resource_sensor,
            R.id.residency_resource_binder,
            R.id.residency_resource_alarm,
            R.id.residency_resource_wakelock,
            R.id.residency_resource_gps,
            R.id.residency_resource_audio,
            R.id.residency_resource_bluetooth
        };
        boolean partial = false;
        for (int id : controls) {
            partial |= setProtectionSwitchEnabled(
                    id, HookTargetIds.HANS_RESOURCE_DISPATCH, true);
        }
        return partial;
    }

    private boolean applyRefreezeAvailability() {
        boolean unavailable = isHookUnavailable(HookTargetIds.HANS_M_TO_F_TIMING);
        RadioGroup group = findViewById(R.id.residency_refreeze_group);
        group.setEnabled(true);
        findViewById(R.id.residency_refreeze_system).setEnabled(true);
        findViewById(R.id.residency_refreeze_after_wake).setEnabled(!unavailable);
        findViewById(R.id.residency_refreeze_disabled).setEnabled(!unavailable);
        boolean afterWake = group.getCheckedRadioButtonId()
                == R.id.residency_refreeze_after_wake;
        setEnabledRecursively(
                findViewById(R.id.residency_refreeze_fields),
                afterWake && !unavailable);
        return unavailable;
    }

    private boolean setProtectionSwitchEnabled(
            int viewId, String hookTargetId, boolean baseEnabled) {
        return setHookSwitchEnabled(viewId, hookTargetId, baseEnabled, true);
    }

    private boolean setHookSwitchEnabled(
            int viewId,
            String hookTargetId,
            boolean baseEnabled,
            boolean activeWhenChecked) {
        boolean unavailable = isHookUnavailable(hookTargetId);
        setSwitchEnabledForAvailability(
                viewId, baseEnabled, unavailable, activeWhenChecked);
        return unavailable;
    }

    private void setSwitchEnabledForAvailability(
            int viewId,
            boolean baseEnabled,
            boolean unavailable,
            boolean activeWhenChecked) {
        MaterialSwitch switchView = findViewById(viewId);
        boolean protectionActive = activeWhenChecked
                ? switchView.isChecked() : !switchView.isChecked();
        switchView.setEnabled(isUnsupportedSwitchEditable(
                baseEnabled, unavailable, protectionActive));
    }

    static boolean isUnsupportedSwitchEditable(
            boolean baseEnabled, boolean unavailable, boolean protectionActive) {
        return baseEnabled && (!unavailable || protectionActive);
    }

    private boolean setWakeChannelEnabled(
            int groupId,
            int allowId,
            int throttleId,
            int blockId,
            String hookTargetId) {
        boolean unavailable = isHookUnavailable(hookTargetId);
        MaterialButtonToggleGroup group = findViewById(groupId);
        group.setEnabled(true);
        findViewById(allowId).setEnabled(true);
        findViewById(throttleId).setEnabled(!unavailable);
        findViewById(blockId).setEnabled(!unavailable);
        return unavailable;
    }

    private boolean setHookEnabled(int viewId, String hookTargetId, boolean baseEnabled) {
        boolean unavailable = isHookUnavailable(hookTargetId);
        setEnabledRecursively(findViewById(viewId), baseEnabled && !unavailable);
        return unavailable;
    }

    private boolean isHookUnavailable(String hookTargetId) {
        return hookStatus != null
                && hookStatus.isExplicitlyUnavailable(hookTargetId);
    }

    private static void setEnabledRecursively(View view, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursively(group.getChildAt(index), enabled);
        }
    }

    private void fillDefaultRefreezeDelaysIfBlank() {
        RefreezePolicy defaults = ResidencySectionPresets.refreezePolicy(
                ResidencySectionPresets.RefreezePreset.AFTER_WAKE);
        setDelayIfBlank(R.id.residency_refreeze_default_value, defaults.defaultDelayMs());
        setDelayIfBlank(R.id.residency_refreeze_packet_value, defaults.packetDelayMs());
        setDelayIfBlank(R.id.residency_refreeze_alarm_value, defaults.alarmDelayMs());
        setDelayIfBlank(R.id.residency_refreeze_binder_value, defaults.binderDelayMs());
        setDelayIfBlank(R.id.residency_refreeze_service_value, defaults.serviceDelayMs());
        setDelayIfBlank(R.id.residency_refreeze_broadcast_value, defaults.broadcastDelayMs());
        setDelayIfBlank(R.id.residency_refreeze_job_value, defaults.jobDelayMs());
    }

    private long readDelay(int layoutId, int valueId, boolean required)
            throws InvalidFormException {
        TextInputLayout layout = findViewById(layoutId);
        TextInputEditText input = findViewById(valueId);
        layout.setError(null);
        try {
            return ResidencyPolicyFormValidator.parseSecondsWhenActive(
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

    private void setDelayIfBlank(int id, long milliseconds) {
        TextInputEditText input = findViewById(id);
        if (input.getText() == null || input.getText().length() == 0) {
            input.setText(formatSeconds(milliseconds));
        }
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
