package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import com.frostwork.bgenhance.R;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

final class ResidencyDropdowns {
    private static final float MAX_SCREEN_HEIGHT_FRACTION = 0.6f;

    private ResidencyDropdowns() {}

    static void setItems(MaterialAutoCompleteTextView field, String[] items) {
        field.setAdapter(new ResidencyDropdownAdapter(field, items));
        updatePopupHeight(field);
    }

    static void updatePopupHeight(MaterialAutoCompleteTextView field) {
        int itemCount = field.getAdapter() == null ? 0 : field.getAdapter().getCount();
        int availableHeight = maxAvailableHeightPx(field);
        field.setDropDownHeight(availableHeight > 0
                ? popupHeightPx(field.getContext(), itemCount, availableHeight)
                : popupHeightPx(field.getContext(), itemCount));
    }

    static int popupHeightPx(Context context, int itemCount) {
        Resources resources = context.getResources();
        int screenHeight = resources.getConfiguration().screenHeightDp > 0
                ? Math.round(resources.getConfiguration().screenHeightDp
                        * resources.getDisplayMetrics().density)
                : resources.getDisplayMetrics().heightPixels;
        return popupHeightPx(context, itemCount,
                Math.round(screenHeight * MAX_SCREEN_HEIGHT_FRACTION));
    }

    static int popupHeightPx(Context context, int itemCount, int availableHeight) {
        Resources resources = context.getResources();
        int rowHeight = resources.getDimensionPixelSize(R.dimen.field_min_height);
        int verticalPadding = resources.getDimensionPixelSize(
                R.dimen.dropdown_popup_padding) * 2;
        return popupHeightPx(itemCount, rowHeight, verticalPadding, availableHeight);
    }

    static int popupHeightPx(
            int itemCount, int rowHeight, int verticalPadding, int availableHeight) {
        if (itemCount <= 0 || rowHeight <= 0 || availableHeight <= verticalPadding) return 0;
        int visibleRows = Math.min(itemCount, (availableHeight - verticalPadding) / rowHeight);
        return visibleRows * rowHeight + (visibleRows > 0 ? verticalPadding : 0);
    }

    static int maxAvailableHeightPx(MaterialAutoCompleteTextView field) {
        if (!field.isLaidOut() || field.getHeight() <= 0) return 0;
        Rect visibleFrame = new Rect();
        field.getWindowVisibleDisplayFrame(visibleFrame);
        if (visibleFrame.isEmpty()) return 0;

        int[] location = new int[2];
        field.getLocationOnScreen(location);
        return maxAvailableHeightPx(visibleFrame, location[1],
                location[1] + field.getHeight(), field.getDropDownVerticalOffset());
    }

    static int maxAvailableHeightPx(
            Rect visibleFrame, int anchorTop, int anchorBottom, int verticalOffset) {
        if (visibleFrame == null || visibleFrame.isEmpty() || anchorBottom < anchorTop) return 0;
        int below = visibleFrame.bottom - anchorBottom - verticalOffset;
        int above = anchorTop - visibleFrame.top + verticalOffset;
        return Math.min(visibleFrame.height(), Math.max(0, Math.max(above, below)));
    }
}
