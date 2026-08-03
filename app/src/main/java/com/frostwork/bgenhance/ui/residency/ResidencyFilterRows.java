package com.frostwork.bgenhance.ui.residency;

import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

/** Stacks compact filter rows when enlarged text no longer fits beside the dropdown. */
final class ResidencyFilterRows {
    private ResidencyFilterRows() {}

    static void stackForLargeText(
            View root, float fontScale, int rowId, int labelId, int fieldId) {
        if (fontScale <= 1.01f) return;
        LinearLayout row = root.findViewById(rowId);
        View label = root.findViewById(labelId);
        View field = root.findViewById(fieldId);
        if (row == null || label == null || field == null) return;

        View input = directChildOf(row, field);
        if (input == null) return;

        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        row.setMinimumHeight(0);

        LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) label.getLayoutParams();
        labelParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
        labelParams.weight = 0f;
        label.setLayoutParams(labelParams);
        label.setPaddingRelative(
                label.getPaddingStart(), label.getPaddingTop(), 0, label.getPaddingBottom());

        LinearLayout.LayoutParams inputParams = (LinearLayout.LayoutParams) input.getLayoutParams();
        inputParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
        inputParams.weight = 0f;
        inputParams.topMargin = Math.round(8f * root.getResources().getDisplayMetrics().density);
        input.setLayoutParams(inputParams);
    }

    private static View directChildOf(LinearLayout row, View descendant) {
        View current = descendant;
        ViewParent parent = current.getParent();
        while (parent instanceof View && parent != row) {
            current = (View) parent;
            parent = current.getParent();
        }
        return parent == row ? current : null;
    }
}
