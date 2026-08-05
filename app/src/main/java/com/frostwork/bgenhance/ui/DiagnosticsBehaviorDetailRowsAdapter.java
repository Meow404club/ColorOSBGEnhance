package com.frostwork.bgenhance.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Renders compact, unframed rows inside one grouped behavior detail surface. */
final class DiagnosticsBehaviorDetailRowsAdapter
        extends RecyclerView.Adapter<DiagnosticsBehaviorDetailRowsAdapter.RowViewHolder> {
    private List<DiagnosticsUiMapper.BehaviorDetailRow> items = new ArrayList<>();
    private Map<String, Long> stableIds = java.util.Collections.emptyMap();

    DiagnosticsBehaviorDetailRowsAdapter() {
        setHasStableIds(true);
    }

    void submitList(List<DiagnosticsUiMapper.BehaviorDetailRow> nextItems) {
        List<DiagnosticsUiMapper.BehaviorDetailRow> replacement = nextItems == null
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
        return new RowViewHolder(LayoutInflater.from(parent.getContext()).inflate(
                R.layout.item_diagnostics_behavior_detail, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {
        holder.bind(items.get(position), position == items.size() - 1);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class RowViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView metrics;
        private final TextView detail;
        private final View divider;

        RowViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.diagnostics_detail_row_title);
            metrics = itemView.findViewById(R.id.diagnostics_detail_row_metrics);
            detail = itemView.findViewById(R.id.diagnostics_detail_row_secondary);
            divider = itemView.findViewById(R.id.diagnostics_detail_row_divider);
        }

        void bind(DiagnosticsUiMapper.BehaviorDetailRow row, boolean last) {
            CharSequence value = itemView.getContext().getString(row.titleRes);
            if (row.titleFormatRes != 0) {
                value = itemView.getContext().getString(row.titleFormatRes, value);
            }
            title.setText(value);
            metrics.setText(itemView.getContext().getString(
                    R.string.diagnostics_metrics_summary,
                    row.metrics.observed,
                    row.metrics.blocked,
                    row.metrics.failOpen));
            if (row.metrics.isEmpty()) {
                detail.setText(R.string.diagnostics_no_activity);
            } else {
                String secondary = DiagnosticsRowsAdapter.resultDetails(
                        itemView.getContext(), row.metrics);
                if (row.metrics.lastAt > 0L) {
                    if (!secondary.isEmpty()) secondary += " · ";
                    secondary += itemView.getContext().getString(
                            R.string.diagnostics_recent_activity,
                            DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT)
                                    .format(new Date(row.metrics.lastAt)));
                }
                detail.setText(secondary);
            }
            divider.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
            itemView.setContentDescription(title.getText() + "，"
                    + metrics.getText() + "，" + detail.getText());
        }
    }

    private static final class RowDiff extends DiffUtil.Callback {
        private final List<DiagnosticsUiMapper.BehaviorDetailRow> oldItems;
        private final List<DiagnosticsUiMapper.BehaviorDetailRow> newItems;

        RowDiff(
                List<DiagnosticsUiMapper.BehaviorDetailRow> oldItems,
                List<DiagnosticsUiMapper.BehaviorDetailRow> newItems) {
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
