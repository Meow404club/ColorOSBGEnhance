package com.frostwork.bgenhance.ui.residency;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Objects;

/** Scrollable heading and filters shown before managed residency rules. */
final class ResidencyManagedHeaderAdapter
        extends RecyclerView.Adapter<ResidencyManagedHeaderAdapter.HeaderViewHolder> {
    interface Listener {
        void onQueryChanged(String query);
        void onModeFilterChanged(int position);
        void onStatusFilterChanged(int position);
    }

    private final Listener listener;
    private String query;
    private int modeFilterIndex;
    private int statusFilterIndex;
    private boolean filtersVisible;
    @Nullable private String resultCount;

    ResidencyManagedHeaderAdapter(
            String query,
            int modeFilterIndex,
            int statusFilterIndex,
            Listener listener) {
        this.query = query == null ? "" : query;
        this.modeFilterIndex = modeFilterIndex;
        this.statusFilterIndex = statusFilterIndex;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @NonNull
    @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new HeaderViewHolder(LayoutInflater.from(parent.getContext()).inflate(
                R.layout.item_residency_apps_header, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
        holder.bind();
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    String query() {
        return query;
    }

    int modeFilterIndex() {
        return modeFilterIndex;
    }

    int statusFilterIndex() {
        return statusFilterIndex;
    }

    boolean hasActiveFilters() {
        return !query.isEmpty() || modeFilterIndex != 0 || statusFilterIndex != 0;
    }

    void setPresentation(boolean filtersVisible, @Nullable String resultCount) {
        if (this.filtersVisible == filtersVisible
                && Objects.equals(this.resultCount, resultCount)) {
            return;
        }
        this.filtersVisible = filtersVisible;
        this.resultCount = resultCount;
        notifyItemChanged(0);
    }

    void resetFilters() {
        if (!hasActiveFilters()) return;
        query = "";
        modeFilterIndex = 0;
        statusFilterIndex = 0;
        notifyItemChanged(0);
        listener.onQueryChanged("");
        listener.onModeFilterChanged(0);
        listener.onStatusFilterChanged(0);
    }

    final class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final View filters;
        private final TextInputEditText search;
        private final MaterialAutoCompleteTextView modeFilter;
        private final MaterialAutoCompleteTextView statusFilter;
        private final TextView count;
        private final String[] modes;
        private final String[] statuses;
        private boolean binding;

        HeaderViewHolder(View itemView) {
            super(itemView);
            filters = itemView.findViewById(R.id.residency_filters);
            search = itemView.findViewById(R.id.residency_search);
            modeFilter = itemView.findViewById(R.id.residency_mode_filter);
            statusFilter = itemView.findViewById(R.id.residency_status_filter);
            count = itemView.findViewById(R.id.residency_result_count);
            modes = itemView.getResources().getStringArray(R.array.residency_mode_filters);
            statuses = itemView.getResources().getStringArray(R.array.residency_status_filters);
            ResidencyDropdowns.setItems(modeFilter, modes);
            ResidencyDropdowns.setItems(statusFilter, statuses);
            float fontScale = itemView.getResources().getConfiguration().fontScale;
            ResidencyFilterRows.stackForLargeText(
                    itemView,
                    fontScale,
                    R.id.residency_mode_filter_row,
                    R.id.residency_mode_filter_label,
                    R.id.residency_mode_filter);
            ResidencyFilterRows.stackForLargeText(
                    itemView,
                    fontScale,
                    R.id.residency_status_filter_row,
                    R.id.residency_status_filter_label,
                    R.id.residency_status_filter);

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(
                        CharSequence text, int start, int count, int after) {}

                @Override public void onTextChanged(
                        CharSequence text, int start, int before, int count) {
                    if (binding) return;
                    query = text == null ? "" : text.toString();
                    listener.onQueryChanged(query);
                }

                @Override public void afterTextChanged(Editable editable) {}
            });
            modeFilter.setOnItemClickListener((parent, view, position, id) -> {
                if (binding) return;
                modeFilterIndex = position;
                listener.onModeFilterChanged(position);
            });
            statusFilter.setOnItemClickListener((parent, view, position, id) -> {
                if (binding) return;
                statusFilterIndex = position;
                listener.onStatusFilterChanged(position);
            });
        }

        void bind() {
            binding = true;
            String current = search.getText() == null ? "" : search.getText().toString();
            if (!Objects.equals(current, query)) {
                search.setText(query);
                search.setSelection(search.length());
            }
            modeFilter.setText(modes[modeFilterIndex], false);
            statusFilter.setText(statuses[statusFilterIndex], false);
            filters.setVisibility(filtersVisible ? View.VISIBLE : View.GONE);
            count.setText(resultCount == null ? "" : resultCount);
            count.setVisibility(resultCount == null ? View.GONE : View.VISIBLE);
            binding = false;
        }
    }
}
