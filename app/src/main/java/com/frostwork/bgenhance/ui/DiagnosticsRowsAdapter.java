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
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import com.frostwork.bgenhance.ui.residency.ResidencyAppIconLoader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Renders application, behavior and Hook diagnostics with one stable row geometry. */
final class DiagnosticsRowsAdapter
        extends RecyclerView.Adapter<DiagnosticsRowsAdapter.RowViewHolder>
        implements AutoCloseable {
    private final ResidencyAppIconLoader iconLoader;
    private List<DiagnosticsUiMapper.UiRow> items = new ArrayList<>();

    DiagnosticsRowsAdapter(Context context) {
        iconLoader = new ResidencyAppIconLoader(context);
        setHasStableIds(true);
    }

    void submitList(List<DiagnosticsUiMapper.UiRow> nextItems) {
        List<DiagnosticsUiMapper.UiRow> replacement = nextItems == null
                ? new ArrayList<>() : new ArrayList<>(nextItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new RowDiff(items, replacement));
        items = replacement;
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).stableId().hashCode();
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
        iconLoader.close();
    }

    final class RowViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        private final TextView title;
        private final TextView subtitle;
        private final TextView metrics;
        private final TextView detail;
        private final TextView badge;

        RowViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.diagnostics_row_icon);
            title = itemView.findViewById(R.id.diagnostics_row_title);
            subtitle = itemView.findViewById(R.id.diagnostics_row_subtitle);
            metrics = itemView.findViewById(R.id.diagnostics_row_metrics);
            detail = itemView.findViewById(R.id.diagnostics_row_detail);
            badge = itemView.findViewById(R.id.diagnostics_row_badge);
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
            icon.setVisibility(View.VISIBLE);
            iconLoader.bind(icon, row.packageName);
            title.setText(row.displayName);
            if (!row.displayName.equals(row.packageName)) setSubtitle(row.packageName);
            metrics.setText(metricsSummary(context, row.totals));
            setDetail(applicationDetail(context, row));
        }

        private void bindBehavior(DiagnosticsUiMapper.BehaviorRow row) {
            Context context = itemView.getContext();
            title.setText(behaviorTitle(row.behavior));
            metrics.setText(metricsSummary(context, row.metrics));
            if (row.metrics.isEmpty()) {
                setDetail(context.getString(R.string.diagnostics_no_activity));
            } else {
                String value = context.getString(
                        R.string.diagnostics_metrics_secondary,
                        row.metrics.passed,
                        row.metrics.throttled);
                if (row.metrics.lastAt > 0L) {
                    value += "\n" + context.getString(
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
            if (row.configRevision >= 0 || row.policyRevision >= 0) {
                details.add(context.getString(
                        R.string.diagnostics_hook_revisions,
                        row.configRevision,
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

        private void resetOptionalViews() {
            iconLoader.cancel(icon);
            icon.setVisibility(View.GONE);
            subtitle.setVisibility(View.GONE);
            detail.setVisibility(View.GONE);
            badge.setVisibility(View.GONE);
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
            return android.text.TextUtils.join(". ", values);
        }

        private int behaviorTitle(DiagnosticsBehavior behavior) {
            switch (behavior) {
                case KILL: return R.string.diagnostics_behavior_kill;
                case FREEZE: return R.string.diagnostics_behavior_freeze;
                case WAKE: return R.string.diagnostics_behavior_wake;
                case RESOURCE: return R.string.diagnostics_behavior_resource;
                case REFREEZE: return R.string.diagnostics_behavior_refreeze;
                case RECOVERY: return R.string.diagnostics_behavior_recovery;
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

    private static int behaviorTitleRes(DiagnosticsBehavior behavior) {
        switch (behavior) {
            case KILL: return R.string.diagnostics_behavior_kill;
            case FREEZE: return R.string.diagnostics_behavior_freeze;
            case WAKE: return R.string.diagnostics_behavior_wake;
            case RESOURCE: return R.string.diagnostics_behavior_resource;
            case REFREEZE: return R.string.diagnostics_behavior_refreeze;
            case RECOVERY: return R.string.diagnostics_behavior_recovery;
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
