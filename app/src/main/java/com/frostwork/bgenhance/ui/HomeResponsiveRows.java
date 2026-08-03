package com.frostwork.bgenhance.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.frostwork.bgenhance.R;

/** Keeps compact homepage navigation rows readable when the system font is enlarged. */
final class HomeResponsiveRows {
    private static final float STACK_STATUS_FONT_SCALE = 1.3f;

    private HomeResponsiveRows() {}

    static void adaptResidencyRow(View row, float fontScale) {
        if (row == null || fontScale < STACK_STATUS_FONT_SCALE) return;
        LinearLayout textContainer = row.findViewById(R.id.residency_home_text_container);
        TextView status = row.findViewById(R.id.status_residency);
        if (textContainer == null || status == null || status.getParent() == textContainer) return;
        ViewGroup oldParent = (ViewGroup) status.getParent();
        oldParent.removeView(status);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = row.getResources().getDimensionPixelSize(
                R.dimen.large_font_status_spacing);
        textContainer.addView(status, params);
        status.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    }
}
