package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.res.Resources;
import com.frostwork.bgenhance.R;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

final class ResidencyDropdowns {
    private static final float MAX_SCREEN_HEIGHT_FRACTION = 0.6f;

    private ResidencyDropdowns() {}

    static void setItems(MaterialAutoCompleteTextView field, String[] items) {
        field.setAdapter(new ResidencyDropdownAdapter(field, items));
        field.setDropDownHeight(popupHeightPx(field.getContext(), items.length));
    }

    static int popupHeightPx(Context context, int itemCount) {
        Resources resources = context.getResources();
        int rowHeight = resources.getDimensionPixelSize(R.dimen.field_min_height);
        int verticalPadding = resources.getDimensionPixelSize(
                R.dimen.dropdown_popup_padding) * 2;
        int screenHeight = resources.getConfiguration().screenHeightDp > 0
                ? Math.round(resources.getConfiguration().screenHeightDp
                        * resources.getDisplayMetrics().density)
                : resources.getDisplayMetrics().heightPixels;
        int maxRows = Math.max(2, (int) Math.floor(
                (screenHeight * MAX_SCREEN_HEIGHT_FRACTION - verticalPadding) / rowHeight));
        int visibleRows = Math.max(1, Math.min(itemCount, maxRows));
        return visibleRows * rowHeight + verticalPadding;
    }
}
