package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** RecyclerView adapter shared by managed applications and the application picker. */
public final class ResidencyAppsAdapter
        extends RecyclerView.Adapter<ResidencyAppsAdapter.AppViewHolder>
        implements AutoCloseable {
    public interface Listener {
        void onAppSelected(ResidencyAppItem item);
    }

    private final ResidencyAppIconLoader iconLoader;
    private final Listener listener;
    private List<ResidencyAppItem> items = new ArrayList<>();

    public ResidencyAppsAdapter(Context context, Listener listener) {
        this.iconLoader = new ResidencyAppIconLoader(context);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void submitList(List<ResidencyAppItem> nextItems) {
        List<ResidencyAppItem> replacement = new ArrayList<>(nextItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new AppDiff(items, replacement));
        items = replacement;
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_residency_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(items.get(position), position == items.size() - 1);
    }

    @Override
    public void onViewRecycled(@NonNull AppViewHolder holder) {
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

    final class AppViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView packageName;
        private final TextView status;
        private final View divider;

        AppViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.residency_app_icon);
            name = itemView.findViewById(R.id.residency_app_name);
            packageName = itemView.findViewById(R.id.residency_app_package);
            status = itemView.findViewById(R.id.residency_app_status);
            divider = itemView.findViewById(R.id.residency_app_divider);
        }

        void bind(ResidencyAppItem item, boolean last) {
            Context context = itemView.getContext();
            name.setText(item.label());
            packageName.setText(item.packageName());
            iconLoader.bind(icon, item.packageName());
            String statusText = statusText(context, item);
            status.setText(statusText);
            itemView.setAlpha(1f);
            divider.setVisibility(last ? View.GONE : View.VISIBLE);
            itemView.setContentDescription(context.getString(
                    R.string.residency_app_content_description,
                    item.label(), item.packageName(), statusText));
            itemView.setOnClickListener(view -> listener.onAppSelected(item));
        }
    }

    private static String statusText(Context context, ResidencyAppItem item) {
        if (item.safety().isRestricted()) {
            return context.getString(R.string.residency_status_restricted);
        }
        AppResidencyPolicy policy = item.policy();
        if (policy == null) return context.getString(R.string.residency_status_unmanaged);
        if (!policy.enabled()) return context.getString(R.string.residency_status_disabled);
        return context.getString(ResidencyUiText.modeName(policy.mode()));
    }

    private static final class AppDiff extends DiffUtil.Callback {
        private final List<ResidencyAppItem> oldItems;
        private final List<ResidencyAppItem> newItems;

        AppDiff(List<ResidencyAppItem> oldItems, List<ResidencyAppItem> newItems) {
            this.oldItems = oldItems;
            this.newItems = newItems;
        }

        @Override
        public int getOldListSize() { return oldItems.size(); }

        @Override
        public int getNewListSize() { return newItems.size(); }

        @Override
        public boolean areItemsTheSame(int oldPosition, int newPosition) {
            return oldItems.get(oldPosition).packageName()
                    .equals(newItems.get(newPosition).packageName());
        }

        @Override
        public boolean areContentsTheSame(int oldPosition, int newPosition) {
            ResidencyAppItem oldItem = oldItems.get(oldPosition);
            ResidencyAppItem newItem = newItems.get(newPosition);
            return (oldPosition == oldItems.size() - 1)
                            == (newPosition == newItems.size() - 1)
                    && oldItem.label().equals(newItem.label())
                    && oldItem.installed() == newItem.installed()
                    && oldItem.systemApp() == newItem.systemApp()
                    && oldItem.launchable() == newItem.launchable()
                    && oldItem.safety().restriction() == newItem.safety().restriction()
                    && Objects.equals(oldItem.policy(), newItem.policy());
        }
    }
}
