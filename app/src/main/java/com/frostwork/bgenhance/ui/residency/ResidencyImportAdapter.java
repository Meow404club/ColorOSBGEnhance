package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Renders a selectable legacy import difference preview. */
final class ResidencyImportAdapter
        extends RecyclerView.Adapter<ResidencyImportAdapter.ImportViewHolder> {
    interface Listener {
        void onSelectionChanged();
    }

    private final Listener listener;
    private List<ResidencyLegacyImportPlan.Entry> entries = Collections.emptyList();
    private Map<String, String> labels = Collections.emptyMap();
    private final LinkedHashSet<String> selectedPackages = new LinkedHashSet<>();

    ResidencyImportAdapter(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void submit(
            List<ResidencyLegacyImportPlan.Entry> nextEntries,
            Map<String, String> nextLabels,
            Collection<String> initialSelection) {
        int previousCount = entries.size();
        entries = Collections.unmodifiableList(new ArrayList<>(nextEntries));
        labels = Objects.requireNonNull(nextLabels, "nextLabels");
        selectedPackages.clear();
        for (String packageName : initialSelection) {
            ResidencyLegacyImportPlan.Entry entry = findEntry(packageName);
            if (entry != null && entry.status() != ResidencyLegacyImportPlan.Status.SKIPPED) {
                selectedPackages.add(packageName);
            }
        }
        if (previousCount > 0) notifyItemRangeRemoved(0, previousCount);
        if (!entries.isEmpty()) notifyItemRangeInserted(0, entries.size());
        listener.onSelectionChanged();
    }

    Set<String> selectedPackages() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(selectedPackages));
    }

    int eligibleCount() {
        int count = 0;
        for (ResidencyLegacyImportPlan.Entry entry : entries) {
            if (entry.status() != ResidencyLegacyImportPlan.Status.SKIPPED) count++;
        }
        return count;
    }

    boolean allEligibleSelected() {
        int eligible = eligibleCount();
        return eligible > 0 && selectedPackages.size() == eligible;
    }

    void selectAll(boolean selected) {
        selectedPackages.clear();
        if (selected) {
            for (ResidencyLegacyImportPlan.Entry entry : entries) {
                if (entry.status() != ResidencyLegacyImportPlan.Status.SKIPPED) {
                    selectedPackages.add(entry.packageName());
                }
            }
        }
        if (!entries.isEmpty()) notifyItemRangeChanged(0, entries.size());
        listener.onSelectionChanged();
    }

    @NonNull
    @Override
    public ImportViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_residency_import, parent, false);
        return new ImportViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImportViewHolder holder, int position) {
        holder.bind(entries.get(position));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private void setSelected(String packageName, boolean selected) {
        boolean changed = selected
                ? selectedPackages.add(packageName) : selectedPackages.remove(packageName);
        if (changed) listener.onSelectionChanged();
    }

    private ResidencyLegacyImportPlan.Entry findEntry(String packageName) {
        for (ResidencyLegacyImportPlan.Entry entry : entries) {
            if (entry.packageName().equals(packageName)) return entry;
        }
        return null;
    }

    final class ImportViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCheckBox checkBox;
        private final TextView appName;
        private final TextView packageName;
        private final TextView changes;
        private final TextView status;

        ImportViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.residency_import_check);
            appName = itemView.findViewById(R.id.residency_import_app_name);
            packageName = itemView.findViewById(R.id.residency_import_package);
            changes = itemView.findViewById(R.id.residency_import_changes);
            status = itemView.findViewById(R.id.residency_import_status);
        }

        void bind(ResidencyLegacyImportPlan.Entry entry) {
            Context context = itemView.getContext();
            String label = labels.get(entry.packageName());
            if (label == null || label.isEmpty()) label = entry.packageName();
            int statusResource = statusResource(entry.status());
            String statusText = context.getString(statusResource);
            String changeText = changeText(context, entry);
            boolean enabled = entry.status() != ResidencyLegacyImportPlan.Status.SKIPPED;

            appName.setText(label);
            packageName.setText(entry.packageName());
            changes.setText(changeText);
            status.setText(statusText);
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(selectedPackages.contains(entry.packageName()));
            checkBox.setEnabled(enabled);
            checkBox.setContentDescription(context.getString(
                    R.string.residency_import_row_description,
                    label,
                    entry.packageName(),
                    statusText,
                    changeText));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                setSelected(entry.packageName(), checked);
            });
            itemView.setEnabled(enabled);
            itemView.setAlpha(enabled ? 1f : 0.58f);
            itemView.setContentDescription(null);
            itemView.setOnClickListener(enabled ? view -> {
                checkBox.setChecked(!selectedPackages.contains(entry.packageName()));
            } : null);
        }
    }

    private static int statusResource(ResidencyLegacyImportPlan.Status status) {
        switch (status) {
            case NEW: return R.string.residency_import_status_new;
            case UPDATE: return R.string.residency_import_status_update;
            case UNCHANGED: return R.string.residency_import_status_unchanged;
            case SKIPPED: return R.string.residency_import_status_skipped;
            default: throw new AssertionError(status);
        }
    }

    private static String changeText(
            Context context, ResidencyLegacyImportPlan.Entry entry) {
        if (entry.status() == ResidencyLegacyImportPlan.Status.SKIPPED) {
            return context.getString(R.string.residency_import_changes_skipped);
        }
        if (entry.status() == ResidencyLegacyImportPlan.Status.UNCHANGED) {
            return context.getString(R.string.residency_import_changes_unchanged);
        }
        AppResidencyPolicy before = entry.currentPolicy();
        AppResidencyPolicy after = entry.importedPolicy();
        List<String> changed = new ArrayList<>();
        if (before == null || before.enabled() != after.enabled()) {
            changed.add(context.getString(R.string.residency_import_change_enabled));
        }
        if (before == null || before.mode() != after.mode()) {
            changed.add(context.getString(R.string.residency_import_change_mode));
        }
        if (before == null
                ? !KillPolicy.none().equals(after.killPolicy())
                : !before.killPolicy().equals(after.killPolicy())) {
            changed.add(context.getString(R.string.residency_import_change_kill));
        }
        if (before == null || !before.freezePolicy().equals(after.freezePolicy())) {
            changed.add(context.getString(R.string.residency_import_change_freeze));
        }
        if (before == null || !before.wakePolicy().equals(after.wakePolicy())) {
            changed.add(context.getString(R.string.residency_import_change_wake));
        }
        if (before == null || !before.resourcePolicy().equals(after.resourcePolicy())) {
            changed.add(context.getString(R.string.residency_import_change_resource));
        }
        if (before == null || !before.refreezePolicy().equals(after.refreezePolicy())) {
            changed.add(context.getString(R.string.residency_import_change_refreeze));
        }
        String fields = String.join("、", changed);
        return context.getString(
                entry.status() == ResidencyLegacyImportPlan.Status.NEW
                        ? R.string.residency_import_changes_new
                        : R.string.residency_import_changes_update,
                fields);
    }
}
