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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import java.util.List;
import java.util.Objects;

/** Scrollable controls shown before the application picker results. */
final class ResidencyAppPickerHeaderAdapter
        extends RecyclerView.Adapter<ResidencyAppPickerHeaderAdapter.HeaderViewHolder> {
    private static final Object PAYLOAD_RESULT_COUNT = new Object();

    interface Listener {
        void onQueryChanged(String query);
        void onShowSystemAppsChanged(boolean showSystemApps);
    }

    private final Listener listener;
    private String query;
    private boolean showSystemApps;
    @Nullable private String resultCount;

    ResidencyAppPickerHeaderAdapter(
            String query, boolean showSystemApps, Listener listener) {
        this.query = query == null ? "" : query;
        this.showSystemApps = showSystemApps;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @NonNull
    @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_residency_app_picker_header, parent, false);
        return new HeaderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
        holder.bind();
    }

    @Override
    public void onBindViewHolder(
            @NonNull HeaderViewHolder holder,
            int position,
            @NonNull List<Object> payloads) {
        if (containsOnlyResultCountPayload(payloads)) {
            holder.bindResultCount();
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    String query() {
        return query;
    }

    boolean showSystemApps() {
        return showSystemApps;
    }

    void setResultCount(@Nullable String resultCount) {
        if (Objects.equals(this.resultCount, resultCount)) return;
        this.resultCount = resultCount;
        notifyItemChanged(0, PAYLOAD_RESULT_COUNT);
    }

    private static boolean containsOnlyResultCountPayload(List<Object> payloads) {
        if (payloads.isEmpty()) return false;
        for (Object payload : payloads) {
            if (payload != PAYLOAD_RESULT_COUNT) return false;
        }
        return true;
    }

    void resetFilters() {
        if (query.isEmpty() && !showSystemApps) return;
        query = "";
        showSystemApps = false;
        notifyItemChanged(0);
        listener.onQueryChanged("");
        listener.onShowSystemAppsChanged(false);
    }

    final class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextInputEditText search;
        private final MaterialSwitch showSystem;
        private final TextView count;
        private boolean binding;

        HeaderViewHolder(View itemView) {
            super(itemView);
            search = itemView.findViewById(R.id.residency_search);
            showSystem = itemView.findViewById(R.id.residency_show_system);
            count = itemView.findViewById(R.id.residency_result_count);
            itemView.findViewById(R.id.residency_show_system_row)
                    .setOnClickListener(view -> showSystem.toggle());
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence text, int start, int count, int after) {}

                @Override
                public void onTextChanged(
                        CharSequence text, int start, int before, int count) {
                    if (binding) return;
                    query = text == null ? "" : text.toString();
                    listener.onQueryChanged(query);
                }

                @Override
                public void afterTextChanged(Editable editable) {}
            });
            showSystem.setOnCheckedChangeListener((button, checked) -> {
                if (binding) return;
                showSystemApps = checked;
                listener.onShowSystemAppsChanged(checked);
            });
        }

        void bind() {
            binding = true;
            if (!Objects.equals(search.getText() == null
                    ? "" : search.getText().toString(), query)) {
                search.setText(query);
                search.setSelection(search.length());
            }
            showSystem.setChecked(showSystemApps);
            bindResultCount();
            binding = false;
        }

        void bindResultCount() {
            count.setText(resultCount == null ? "" : resultCount);
            count.setVisibility(resultCount == null ? View.GONE : View.VISIBLE);
        }
    }
}
