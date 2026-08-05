package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsBehavior;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsDataSource;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import com.frostwork.bgenhance.ui.residency.ResidencyAppIconLoader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders application, behavior and Hook diagnostics with one stable row geometry. */
final class DiagnosticsRowsAdapter
        extends RecyclerView.Adapter<DiagnosticsRowsAdapter.RowViewHolder>
        implements AutoCloseable {
    interface BehaviorClickListener {
        void onBehaviorClicked(DiagnosticsBehavior behavior);
    }

    interface ApplicationClickListener {
        void onApplicationClicked(String packageName);
    }

    private final ResidencyAppIconLoader iconLoader;
    private final ApplicationClickListener applicationClickListener;
    private final BehaviorClickListener behaviorClickListener;
    private List<DiagnosticsUiMapper.UiRow> items = new ArrayList<>();
    private Map<String, Long> stableIds = java.util.Collections.emptyMap();

    DiagnosticsRowsAdapter(Context context) {
        this(context, null, null);
    }

    DiagnosticsRowsAdapter(Context context, BehaviorClickListener behaviorClickListener) {
        this(context, null, behaviorClickListener);
    }

    DiagnosticsRowsAdapter(
            Context context,
            ApplicationClickListener applicationClickListener,
            BehaviorClickListener behaviorClickListener) {
        iconLoader = new ResidencyAppIconLoader(context);
        this.applicationClickListener = applicationClickListener;
        this.behaviorClickListener = behaviorClickListener;
        setHasStableIds(true);
    }

    void submitList(List<DiagnosticsUiMapper.UiRow> nextItems) {
        List<DiagnosticsUiMapper.UiRow> replacement = nextItems == null
                ? new ArrayList<>() : new ArrayList<>(nextItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new RowDiff(items, replacement));
        Map<String, Long> replacementIds = DiagnosticsStableIds.reconcile(stableIds, replacement);
        items = replacement;
        stableIds = replacementIds;
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public long getItemId(int position) {
        Long stableId = stableIds.get(items.get(position).stableId());
        if (stableId == null) throw new IllegalStateException("missing diagnostics stable ID");
        return stableId;
    }

    @NonNull
    @Override
    public RowViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_diagnostics_row, parent, false);
        return new RowViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull RowViewHolder holder) {
        iconLoader.cancel(holder.icon);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void close() {
        stableIds = java.util.Collections.emptyMap();
        iconLoader.close();
    }

    final class RowViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        private final TextView title;
        private final TextView subtitle;
        private final TextView metrics;
        private final TextView detail;
        private final TextView badge;
        private final ImageView chevron;
        private int actionDescriptionRes;

        RowViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.diagnostics_row_icon);
            title = itemView.findViewById(R.id.diagnostics_row_title);
            subtitle = itemView.findViewById(R.id.diagnostics_row_subtitle);
            metrics = itemView.findViewById(R.id.diagnostics_row_metrics);
            detail = itemView.findViewById(R.id.diagnostics_row_detail);
            badge = itemView.findViewById(R.id.diagnostics_row_badge);
            chevron = itemView.findViewById(R.id.diagnostics_row_chevron);
        }

        void bind(DiagnosticsUiMapper.UiRow row) {
            resetOptionalViews();
            if (row instanceof DiagnosticsUiMapper.ApplicationRow) {
                bindApplication((DiagnosticsUiMapper.ApplicationRow) row);
            } else if (row instanceof DiagnosticsUiMapper.BehaviorRow) {
                bindBehavior((DiagnosticsUiMapper.BehaviorRow) row);
            } else if (row instanceof DiagnosticsUiMapper.HookTargetRow) {
                bindHook((DiagnosticsUiMapper.HookTargetRow) row);
            }
            itemView.setContentDescription(buildContentDescription());
        }

        private void bindApplication(DiagnosticsUiMapper.ApplicationRow row) {
            Context context = itemView.getContext();
            if (row.unidentified) {
                DiagnosticsDataSource.ResolvedApplication application =
                        uniqueIdentityApplication(row);
                if (application != null
                        && application.currentUser
                        && application.identityConfirmed) {
                    iconLoader.bind(icon, application.packageName);
                } else {
                    icon.setImageResource(uidIdentityIcon(row));
                }
                icon.setVisibility(View.VISIBLE);
                if (application == null) {
                    title.setText(uidIdentityTitle(row));
                } else {
                    title.setText(application.displayName);
                }
                setSubtitle(uidIdentitySubtitle(context, row));
            } else {
                icon.setVisibility(View.VISIBLE);
                iconLoader.bind(icon, row.packageName);
                title.setText(row.displayName);
                if (!row.displayName.equals(row.packageName)) setSubtitle(row.packageName);
            }
            metrics.setText(metricsSummary(context, row.totals));
            setDetail(applicationDetail(context, row));
            if (row.manageable && applicationClickListener != null) {
                chevron.setVisibility(View.VISIBLE);
                itemView.setClickable(true);
                itemView.setFocusable(true);
                actionDescriptionRes = R.string.diagnostics_manage_application;
                itemView.setOnClickListener(view ->
                        applicationClickListener.onApplicationClicked(row.packageName));
            }
        }

        private void bindBehavior(DiagnosticsUiMapper.BehaviorRow row) {
            Context context = itemView.getContext();
            title.setText(behaviorTitle(row.behavior));
            metrics.setText(metricsSummary(context, row.metrics));
            if (behaviorClickListener != null) {
                chevron.setVisibility(View.VISIBLE);
                itemView.setClickable(true);
                itemView.setFocusable(true);
                actionDescriptionRes = R.string.diagnostics_behavior_detail_action;
                itemView.setOnClickListener(view ->
                        behaviorClickListener.onBehaviorClicked(row.behavior));
            }
            if (row.metrics.isEmpty()) {
                setDetail(context.getString(R.string.diagnostics_no_activity));
            } else {
                String value = resultDetails(context, row.metrics);
                if (row.metrics.lastAt > 0L) {
                    if (!value.isEmpty()) value += "\n";
                    value += context.getString(
                            R.string.diagnostics_recent_activity,
                            formatTime(row.metrics.lastAt));
                }
                setDetail(value);
            }
        }

        private void bindHook(DiagnosticsUiMapper.HookTargetRow row) {
            Context context = itemView.getContext();
            title.setText(hookTitle(row));
            setSubtitle(context.getString(processTitle(row.expectedProcessId)));
            bindBadge(row.status);
            if (row.rawHitCount > 0L || row.parsedCount > 0L
                    || row.identityResolvedCount > 0L) {
                metrics.setText(context.getString(
                        R.string.diagnostics_hook_hits,
                        row.rawHitCount,
                        row.parsedCount,
                        row.identityResolvedCount));
            } else {
                metrics.setText(R.string.diagnostics_no_activity);
            }

            ArrayList<String> details = new ArrayList<>();
            int statusDetail = hookStatusDetailResource(row.status);
            if (statusDetail != 0) details.add(context.getString(statusDetail));
            if (row.configRevision >= 0) {
                details.add(context.getString(
                        R.string.diagnostics_hook_config_revision,
                        row.configRevision));
            }
            if (row.policyRevision >= 0) {
                details.add(context.getString(
                        R.string.diagnostics_hook_policy_revision,
                        row.policyRevision));
            }
            long latest = Math.max(row.updatedAt, Math.max(row.lastHookAt, row.lastEventAt));
            if (latest > 0L) {
                details.add(context.getString(
                        R.string.support_last_updated,
                        formatTime(latest)));
            }
            if (!row.countsConsistent || !row.lastError.isEmpty()) {
                details.add(context.getString(R.string.diagnostics_hook_error_hint));
            }
            setDetail(joinLines(details));
        }

        static int hookStatusDetailResource(DiagnosticsUiMapper.HookStatus status) {
            if (status == null) return 0;
            switch (status) {
                case ENTRY_LOADED:
                    return R.string.diagnostics_status_entry_loaded_detail;
                case BOOTSTRAP_FAILED:
                    return R.string.diagnostics_status_bootstrap_failed_detail;
                case VERSION_MISMATCH:
                    return R.string.diagnostics_status_version_mismatch_detail;
                default:
                    return 0;
            }
        }

        private void resetOptionalViews() {
            iconLoader.cancel(icon);
            icon.setVisibility(View.GONE);
            subtitle.setVisibility(View.GONE);
            detail.setVisibility(View.GONE);
            badge.setVisibility(View.GONE);
            chevron.setVisibility(View.GONE);
            actionDescriptionRes = 0;
            itemView.setOnClickListener(null);
            itemView.setClickable(false);
            itemView.setFocusable(false);
            title.setText("");
            subtitle.setText("");
            metrics.setText("");
            detail.setText("");
            badge.setText("");
        }

        private void setSubtitle(CharSequence value) {
            if (value == null || value.length() == 0) return;
            subtitle.setText(value);
            subtitle.setVisibility(View.VISIBLE);
        }

        private void setDetail(CharSequence value) {
            if (value == null || value.length() == 0) return;
            detail.setText(value);
            detail.setVisibility(View.VISIBLE);
        }

        private void bindBadge(DiagnosticsUiMapper.HookStatus status) {
            int textRes;
            int backgroundRes;
            int colorRes;
            switch (status) {
                case READY:
                    textRes = R.string.diagnostics_status_ready;
                    backgroundRes = R.drawable.bg_diagnostics_status_success;
                    colorRes = R.color.status_success_text;
                    break;
                case INSTALLED:
                    textRes = R.string.diagnostics_status_installed;
                    backgroundRes = R.drawable.bg_diagnostics_status_success;
                    colorRes = R.color.status_success_text;
                    break;
                case ENTRY_LOADED:
                    textRes = R.string.diagnostics_status_entry_loaded;
                    backgroundRes = R.drawable.bg_diagnostics_status_warning;
                    colorRes = R.color.banner_warning_text;
                    break;
                case BOOTSTRAP_FAILED:
                    textRes = R.string.diagnostics_status_bootstrap_failed;
                    backgroundRes = R.drawable.bg_diagnostics_status_error;
                    colorRes = R.color.status_error_text;
                    break;
                case VERSION_MISMATCH:
                    textRes = R.string.diagnostics_status_version_mismatch;
                    backgroundRes = R.drawable.bg_diagnostics_status_warning;
                    colorRes = R.color.banner_warning_text;
                    break;
                case FAILED:
                    textRes = R.string.diagnostics_status_failed;
                    backgroundRes = R.drawable.bg_diagnostics_status_error;
                    colorRes = R.color.status_error_text;
                    break;
                case UNSUPPORTED:
                    textRes = R.string.diagnostics_status_unsupported;
                    backgroundRes = R.drawable.bg_diagnostics_status_warning;
                    colorRes = R.color.banner_warning_text;
                    break;
                case SKIPPED:
                    textRes = R.string.diagnostics_status_skipped;
                    backgroundRes = R.drawable.bg_residency_badge_neutral;
                    colorRes = R.color.chip_text;
                    break;
                case PENDING:
                    textRes = R.string.diagnostics_status_pending;
                    backgroundRes = R.drawable.bg_diagnostics_status_warning;
                    colorRes = R.color.banner_warning_text;
                    break;
                case MISSING:
                    textRes = R.string.diagnostics_status_missing;
                    backgroundRes = R.drawable.bg_residency_badge_neutral;
                    colorRes = R.color.chip_text;
                    break;
                case UNKNOWN:
                default:
                    textRes = R.string.diagnostics_status_unknown;
                    backgroundRes = R.drawable.bg_residency_badge_neutral;
                    colorRes = R.color.chip_text;
                    break;
            }
            badge.setText(textRes);
            badge.setTextColor(itemView.getContext().getColor(colorRes));
            badge.setBackgroundResource(backgroundRes);
            badge.setVisibility(View.VISIBLE);
        }

        private CharSequence buildContentDescription() {
            ArrayList<CharSequence> values = new ArrayList<>();
            values.add(title.getText());
            if (subtitle.getVisibility() == View.VISIBLE) values.add(subtitle.getText());
            if (badge.getVisibility() == View.VISIBLE) values.add(badge.getText());
            values.add(metrics.getText());
            if (detail.getVisibility() == View.VISIBLE) values.add(detail.getText());
            if (actionDescriptionRes != 0) {
                values.add(itemView.getContext().getString(actionDescriptionRes));
            }
            return android.text.TextUtils.join("，", values);
        }

        private int uidIdentityTitle(DiagnosticsUiMapper.ApplicationRow row) {
            switch (row.uidKind) {
                case SHARED_UID: return R.string.diagnostics_application_shared_uid;
                case SYSTEM_UID: return R.string.diagnostics_application_system_uid;
                case APP_ZYGOTE_UID:
                    return R.string.diagnostics_application_app_zygote_uid;
                case ISOLATED_UID: return R.string.diagnostics_application_isolated_uid;
                case SDK_SANDBOX_UID: return R.string.diagnostics_application_sdk_sandbox_uid;
                case RESOLUTION_FAILED:
                    return R.string.diagnostics_application_resolution_failed;
                case SINGLE_APPLICATION:
                case UNKNOWN:
                default:
                    return row.identityUid >= 0
                            ? R.string.diagnostics_application_uid_only
                            : R.string.diagnostics_application_unidentified;
            }
        }

        private int uidIdentityIcon(DiagnosticsUiMapper.ApplicationRow row) {
            switch (row.uidKind) {
                case SHARED_UID:
                    return R.drawable.ic_diagnostics_group;
                case SYSTEM_UID:
                    return R.drawable.ic_diagnostics_system;
                case APP_ZYGOTE_UID:
                case ISOLATED_UID:
                case SDK_SANDBOX_UID:
                case RESOLUTION_FAILED:
                case UNKNOWN:
                case SINGLE_APPLICATION:
                default:
                    return R.drawable.ic_diagnostics_process;
            }
        }

        private DiagnosticsDataSource.ResolvedApplication uniqueIdentityApplication(
                DiagnosticsUiMapper.ApplicationRow row) {
            return row.uidKind == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                    && row.identityApplications.size() == 1
                    ? row.identityApplications.get(0) : null;
        }

        private int behaviorTitle(DiagnosticsBehavior behavior) {
            switch (behavior) {
                case KILL: return R.string.diagnostics_behavior_kill;
                case FREEZE: return R.string.diagnostics_behavior_freeze;
                case WAKE: return R.string.diagnostics_behavior_wake;
                case RESOURCE: return R.string.diagnostics_behavior_resource;
                case REFREEZE: return R.string.diagnostics_behavior_refreeze;
                case RECOVERY: return R.string.diagnostics_behavior_recovery;
                case UNCLASSIFIED: return R.string.diagnostics_behavior_unclassified;
                default: throw new IllegalArgumentException("unknown behavior");
            }
        }

        private int hookTitle(DiagnosticsUiMapper.HookTargetRow row) {
            String target = row.hookTargetId;
            if (DiagnosticsTargetIds.RUNTIME_PROCESS.equals(target)) {
                return BridgeContract.PROCESS_ATHENA.equals(row.expectedProcessId)
                        ? R.string.diagnostics_runtime_athena_process
                        : R.string.diagnostics_runtime_system_process;
            }
            if (DiagnosticsTargetIds.RUNTIME_CONFIG.equals(target)) {
                return BridgeContract.PROCESS_ATHENA.equals(row.expectedProcessId)
                        ? R.string.diagnostics_runtime_athena_config
                        : R.string.diagnostics_runtime_system_config;
            }
            if (DiagnosticsTargetIds.RUNTIME_IDENTITY.equals(target)) {
                return BridgeContract.PROCESS_ATHENA.equals(row.expectedProcessId)
                        ? R.string.diagnostics_runtime_athena_identity
                        : R.string.diagnostics_runtime_system_identity;
            }
            if (HookTargetIds.DIAGNOSTICS_BRIDGE_STARTUP.equals(target)) {
                return R.string.diagnostics_hook_bridge_startup;
            }
            if (HookTargetIds.ATHENA_PERIODIC.equals(target)) return R.string.periodic_title;
            if (HookTargetIds.OGUARD_KILL.equals(target)) return R.string.oguard_kill_title;
            if (HookTargetIds.OGUARD_FORCE_STOP.equals(target)) return R.string.oguard_stop_title;
            if (HookTargetIds.FAST_CLEAR_DISPATCH.equals(target)) return R.string.fast_clear_nav_title;
            if (HookTargetIds.TOURNAMENT.equals(target)) return R.string.tournament_title;
            if (HookTargetIds.MEMORY_GUARD_DISPATCH.equals(target)) return R.string.memory_nav_title;
            if (HookTargetIds.OSENSE_GROUP_CLEAN.equals(target)) return R.string.nirvana_nav_title;
            if (HookTargetIds.OSENSE_PROCESS_KILL.equals(target)) return R.string.osense_per_process_title;
            if (HookTargetIds.HANS_FREEZE_DISPATCH.equals(target)) return R.string.freeze_nav_title;
            if (HookTargetIds.HANS_FAST_FREEZE_DISPATCH.equals(target)) return R.string.hans_fast_freeze_support_title;
            if (HookTargetIds.HANS_WAKE_DISPATCH.equals(target)) return R.string.hans_wake_support_title;
            if (HookTargetIds.ALARM_WAKE_DELIVERY.equals(target)) return R.string.alarm_wake_delivery_support_title;
            if (HookTargetIds.HANS_RESOURCE_DISPATCH.equals(target)) return R.string.hans_resource_support_title;
            if (HookTargetIds.HANS_R_TO_M_TIMING.equals(target)) return R.string.hans_r_to_m_timing_support_title;
            if (HookTargetIds.HANS_M_TO_F_TIMING.equals(target)) return R.string.hans_m_to_f_timing_support_title;
            if (HookTargetIds.HANS_CORE_EXEMPTION.equals(target)) return R.string.hans_core_exemption_support_title;
            if (HookTargetIds.HANS_LCD_ON_EXEMPTION.equals(target)) return R.string.hans_lcd_on_exemption_support_title;
            if (HookTargetIds.HANS_ACTIVE_RECOVERY.equals(target)) return R.string.hans_recovery_support_title;
            return R.string.diagnostics_status_unknown;
        }

        private int processTitle(String processId) {
            if (BridgeContract.PROCESS_SYSTEM_SERVER.equals(processId)) {
                return R.string.diagnostics_process_system_server;
            }
            if (BridgeContract.PROCESS_ATHENA.equals(processId)) {
                return R.string.diagnostics_process_athena;
            }
            return R.string.diagnostics_process_other;
        }
    }

    private static String metricsSummary(
            Context context, DiagnosticsUiMapper.Metrics metrics) {
        return context.getString(
                R.string.diagnostics_metrics_summary,
                metrics.observed,
                metrics.blocked,
                metrics.failOpen);
    }

    static String resultDetails(
            Context context, DiagnosticsUiMapper.Metrics metrics) {
        ArrayList<String> values = new ArrayList<>();
        addMetric(values, context, R.string.diagnostics_result_passed, metrics.passed);
        addMetric(values, context, R.string.diagnostics_result_observed, metrics.observedOnly);
        addMetric(values, context, R.string.diagnostics_result_recovered, metrics.recovered);
        addMetric(values, context, R.string.diagnostics_result_no_action,
                metrics.noActionNeeded);
        addMetric(values, context, R.string.diagnostics_result_safety_aborted,
                metrics.safetyAborted);
        addMetric(values, context, R.string.diagnostics_result_applied, metrics.applied);
        addMetric(values, context, R.string.diagnostics_result_throttle_allowed,
                metrics.throttleAllowed);
        addMetric(values, context, R.string.diagnostics_result_throttle_blocked,
                metrics.throttleBlocked);
        addMetric(values, context, R.string.diagnostics_result_unknown,
                metrics.unknownResult);
        return android.text.TextUtils.join(" · ", values);
    }

    private static void addMetric(
            List<String> values, Context context, int labelRes, long count) {
        if (count > 0L) values.add(context.getString(labelRes, count));
    }

    private static String applicationDetail(
            Context context, DiagnosticsUiMapper.ApplicationRow row) {
        ArrayList<String> values = new ArrayList<>();
        for (DiagnosticsBehavior behavior : DiagnosticsBehavior.all()) {
            DiagnosticsUiMapper.Metrics metrics = row.behavior(behavior);
            if (metrics.observed > 0L) {
                values.add(context.getString(behaviorTitleRes(behavior))
                        + " " + metrics.observed);
            }
        }
        if (values.isEmpty()) values.add(context.getString(R.string.diagnostics_no_activity));
        if (row.totals.lastAt > 0L) {
            values.add(context.getString(
                    R.string.diagnostics_recent_activity,
                    formatTime(row.totals.lastAt)));
        }
        return joinLines(values);
    }

    private static String uidIdentitySubtitle(
            Context context, DiagnosticsUiMapper.ApplicationRow row) {
        if (row.identityUid < 0) return "";
        LinkedHashMap<String, DiagnosticsDataSource.ResolvedApplication> applications =
                new LinkedHashMap<>();
        for (DiagnosticsDataSource.ResolvedApplication application
                : row.identityApplications) {
            if (application != null && !application.packageName.isEmpty()) {
                applications.putIfAbsent(application.packageName, application);
            }
        }
        if (applications.isEmpty()) {
            return context.getString(R.string.diagnostics_uid_value, row.identityUid);
        }
        if (row.uidKind == DiagnosticsDataSource.UidKind.SINGLE_APPLICATION
                && applications.size() == 1) {
            return context.getString(
                    R.string.diagnostics_uid_with_applications,
                    row.identityUid,
                    applications.keySet().iterator().next());
        }
        LinkedHashMap<String, Integer> labelCounts = new LinkedHashMap<>();
        for (DiagnosticsDataSource.ResolvedApplication application
                : applications.values()) {
            String label = application.displayName.isEmpty()
                    ? application.packageName : application.displayName;
            labelCounts.put(label, labelCounts.getOrDefault(label, 0) + 1);
        }
        ArrayList<String> visible = new ArrayList<>();
        for (DiagnosticsDataSource.ResolvedApplication application
                : applications.values()) {
            String label = application.displayName.isEmpty()
                    ? application.packageName : application.displayName;
            if (labelCounts.get(label) > 1 && !label.equals(application.packageName)) {
                label += " (" + application.packageName + ")";
            }
            visible.add(label);
            if (visible.size() == 3) break;
        }
        String names = android.text.TextUtils.join("、", visible);
        if (applications.size() > visible.size()) {
            names = context.getString(
                    R.string.diagnostics_uid_applications_more,
                    names,
                    applications.size());
        }
        return context.getString(
                R.string.diagnostics_uid_with_applications, row.identityUid, names);
    }

    private static int behaviorTitleRes(DiagnosticsBehavior behavior) {
        switch (behavior) {
            case KILL: return R.string.diagnostics_behavior_kill;
            case FREEZE: return R.string.diagnostics_behavior_freeze;
            case WAKE: return R.string.diagnostics_behavior_wake;
            case RESOURCE: return R.string.diagnostics_behavior_resource;
            case REFREEZE: return R.string.diagnostics_behavior_refreeze;
            case RECOVERY: return R.string.diagnostics_behavior_recovery;
            case UNCLASSIFIED: return R.string.diagnostics_behavior_unclassified;
            default: throw new IllegalArgumentException("unknown behavior");
        }
    }

    private static String joinLines(List<String> values) {
        return android.text.TextUtils.join("\n", values);
    }

    private static String formatTime(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    private static final class RowDiff extends DiffUtil.Callback {
        private final List<DiagnosticsUiMapper.UiRow> oldItems;
        private final List<DiagnosticsUiMapper.UiRow> newItems;

        RowDiff(List<DiagnosticsUiMapper.UiRow> oldItems,
                List<DiagnosticsUiMapper.UiRow> newItems) {
            this.oldItems = oldItems;
            this.newItems = newItems;
        }

        @Override public int getOldListSize() { return oldItems.size(); }
        @Override public int getNewListSize() { return newItems.size(); }

        @Override
        public boolean areItemsTheSame(int oldPosition, int newPosition) {
            return oldItems.get(oldPosition).stableId()
                    .equals(newItems.get(newPosition).stableId());
        }

        @Override
        public boolean areContentsTheSame(int oldPosition, int newPosition) {
            return oldItems.get(oldPosition) == newItems.get(newPosition);
        }
    }
}
