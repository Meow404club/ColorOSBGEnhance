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

/** Renders managed-list loading, empty and error states in the page scroller. */
final class ResidencyManagedStateAdapter
        extends RecyclerView.Adapter<ResidencyManagedStateAdapter.StateViewHolder> {
    interface Listener {
        void onRetry();
        void onEmptyAction(boolean activeFilters);
    }

    private final Listener listener;
    private ResidencyAppsViewModel.Status status = ResidencyAppsViewModel.Status.LOADING;
    private boolean activeFilters;

    ResidencyManagedStateAdapter(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void setState(ResidencyAppsViewModel.Status status, boolean activeFilters) {
        ResidencyAppsViewModel.Status replacement = Objects.requireNonNull(status, "status");
        boolean wasVisible = isVisible(this.status);
        boolean willBeVisible = isVisible(replacement);
        boolean changed = this.status != replacement || this.activeFilters != activeFilters;
        this.status = replacement;
        this.activeFilters = activeFilters;
        if (!changed) return;
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
        return new StateViewHolder(LayoutInflater.from(parent.getContext()).inflate(
                R.layout.item_residency_app_picker_state, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull StateViewHolder holder, int position) {
        holder.bind(status, activeFilters);
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

        void bind(ResidencyAppsViewModel.Status status, boolean activeFilters) {
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
                title.setText(activeFilters
                        ? R.string.residency_filter_empty_title
                        : R.string.residency_empty_title);
                summary.setText(activeFilters
                        ? R.string.residency_filter_empty_summary
                        : R.string.residency_empty_summary);
                action.setText(activeFilters
                        ? R.string.residency_clear_filters
                        : R.string.residency_add_app);
                action.setOnClickListener(view -> listener.onEmptyAction(activeFilters));
            } else {
                title.setText(R.string.residency_load_error_title);
                summary.setText(R.string.residency_load_error_summary);
                action.setText(R.string.residency_retry);
                action.setOnClickListener(view -> listener.onRetry());
            }
        }
    }
}
