package com.frostwork.bgenhance.ui.residency;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.TextView;
import com.frostwork.bgenhance.R;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/** Keeps residency dropdown menus visually consistent with their closed fields. */
final class ResidencyDropdownAdapter extends ArrayAdapter<String> {
    private final MaterialAutoCompleteTextView field;
    private final String[] items;
    private final Filter allItemsFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            results.values = items;
            results.count = items.length;
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            notifyDataSetChanged();
        }
    };

    ResidencyDropdownAdapter(MaterialAutoCompleteTextView field, String[] items) {
        super(field.getContext(), R.layout.item_residency_dropdown_option,
                android.R.id.text1, items);
        this.field = field;
        this.items = items.clone();
    }

    @Override
    public Filter getFilter() {
        return allItemsFilter;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getView(position, convertView, parent);
        view.setActivated(TextUtils.equals(view.getText(), field.getText()));
        return view;
    }
}
