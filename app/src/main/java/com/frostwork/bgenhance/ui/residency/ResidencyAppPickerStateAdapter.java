package com.frostwork.bgenhance.ui.residency;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.google.android.material.button.MaterialButton;
import java.util.Objects;

/** Renders picker loading, empty and error states inside the same scrolling list. */
final class ResidencyAppPickerStateAdapter
        extends RecyclerView.Adapter<ResidencyAppPickerStateAdapter.StateViewHolder> {
    interface Listener {
        void onRetry();
        void onClearFilters();
    }

    private final Listener listener;
    private ResidencyAppsViewModel.Status status = ResidencyAppsViewModel.Status.LOADING;

    ResidencyAppPickerStateAdapter(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void setStatus(ResidencyAppsViewModel.Status status) {
        ResidencyAppsViewModel.Status replacement = Objects.requireNonNull(status, "status");
        boolean wasVisible = isVisible(this.status);
        boolean willBeVisible = isVisible(replacement);
        if (this.status == replacement) return;
        this.status = replacement;
        if (!wasVisible && willBeVisible) {
            notifyItemInserted(0);
        } else if (wasVisible && !willBeVisible) {
            notifyItemRemoved(0);
        } else if (willBeVisible) {
            notifyItemChanged(0);
        }
    }

    @NonNull
    @Override
    public StateViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_residency_app_picker_state, parent, false);
        return new StateViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StateViewHolder holder, int position) {
        holder.bind(status);
    }

    @Override
    public int getItemCount() {
        return isVisible(status) ? 1 : 0;
    }

    private static boolean isVisible(ResidencyAppsViewModel.Status status) {
        return status != ResidencyAppsViewModel.Status.CONTENT;
    }

    final class StateViewHolder extends RecyclerView.ViewHolder {
        private final View progress;
        private final TextView title;
        private final TextView summary;
        private final MaterialButton action;

        StateViewHolder(View itemView) {
            super(itemView);
            progress = itemView.findViewById(R.id.residency_state_progress);
            title = itemView.findViewById(R.id.residency_state_title);
            summary = itemView.findViewById(R.id.residency_state_summary);
            action = itemView.findViewById(R.id.residency_state_action);
        }

        void bind(ResidencyAppsViewModel.Status status) {
            if (status == ResidencyAppsViewModel.Status.LOADING) {
                progress.setVisibility(View.VISIBLE);
                title.setText(R.string.residency_loading);
                summary.setVisibility(View.GONE);
                action.setVisibility(View.GONE);
                action.setOnClickListener(null);
                return;
            }

            progress.setVisibility(View.GONE);
            summary.setVisibility(View.VISIBLE);
            action.setVisibility(View.VISIBLE);
            if (status == ResidencyAppsViewModel.Status.EMPTY) {
                title.setText(R.string.residency_picker_empty_title);
                summary.setText(R.string.residency_picker_empty_summary);
                action.setText(R.string.residency_clear_filters);
                action.setOnClickListener(view -> listener.onClearFilters());
            } else {
                title.setText(R.string.residency_load_error_title);
                summary.setText(R.string.residency_load_error_summary);
                action.setText(R.string.residency_retry);
                action.setOnClickListener(view -> listener.onRetry());
            }
        }
    }
}
