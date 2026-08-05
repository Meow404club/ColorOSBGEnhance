package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.frostwork.bgenhance.R;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class SettingSwitchRow extends LinearLayout {
    public interface Listener { void onRequestedChange(SettingSwitchRow row, boolean checked); }

    private final TextView titleView;
    private final TextView summaryView;
    private final TextView detailView;
    private final TextView trailingStatusView;
    private final MaterialSwitch switchView;
    private Listener listener;
    private boolean binding;
    private boolean rowEnabled = true;
    private int summaryRes;

    public SettingSwitchRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMinimumHeight(UiUtils.dp(context, 92));
        setPaddingRelative(UiUtils.dp(context, 20), UiUtils.dp(context, 14),
                UiUtils.dp(context, 16), UiUtils.dp(context, 14));
        TypedArray selectable = context.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        try {
            setForeground(selectable.getDrawable(0));
        } finally {
            selectable.recycle();
        }

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(VERTICAL);
        textColumn.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LayoutParams textParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginEnd(UiUtils.dp(context, 12));
        addView(textColumn, textParams);

        titleView = new TextView(context);
        titleView.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_SettingTitle);
        titleView.setSingleLine(false);
        titleView.setMaxLines(Integer.MAX_VALUE);
        titleView.setEllipsize(null);
        textColumn.addView(titleView,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        summaryView = new TextView(context);
        summaryView.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_Summary);
        LayoutParams summaryParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = UiUtils.dp(context, 4);
        textColumn.addView(summaryView, summaryParams);

        detailView = new TextView(context);
        detailView.setTextColor(context.getColor(R.color.item_summary));
        detailView.setTextSize(12f);
        LayoutParams detailParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = UiUtils.dp(context, 8);
        textColumn.addView(detailView, detailParams);

        FrameLayout trailing = new FrameLayout(context);
        trailing.setMinimumWidth(UiUtils.dp(context, 68));
        trailing.setMinimumHeight(UiUtils.dp(context, 48));
        addView(trailing, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        switchView = new MaterialSwitch(context);
        switchView.setShowText(false);
        FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        trailing.addView(switchView, switchParams);

        trailingStatusView = new TextView(context);
        trailingStatusView.setGravity(Gravity.CENTER);
        trailingStatusView.setIncludeFontPadding(false);
        trailingStatusView.setTextColor(context.getColor(R.color.chip_text));
        trailingStatusView.setTextSize(12f);
        trailingStatusView.setTypeface(
                trailingStatusView.getTypeface(), android.graphics.Typeface.BOLD);
        trailingStatusView.setMinHeight(UiUtils.dp(context, 32));
        trailingStatusView.setMaxLines(2);
        trailingStatusView.setPadding(UiUtils.dp(context, 10), UiUtils.dp(context, 5),
                UiUtils.dp(context, 10), UiUtils.dp(context, 5));
        trailingStatusView.setVisibility(View.GONE);
        trailingStatusView.setContentDescription(null);
        GradientDrawable neutral = new GradientDrawable();
        neutral.setShape(GradientDrawable.RECTANGLE);
        neutral.setColor(context.getColor(R.color.chip_neutral));
        neutral.setCornerRadius(UiUtils.dp(context, 10));
        trailingStatusView.setBackground(neutral);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        trailing.addView(trailingStatusView, statusParams);

        switchView.setOnCheckedChangeListener((button, checked) -> {
            if (!binding && listener != null) listener.onRequestedChange(this, checked);
        });
        setOnClickListener(v -> {
            if (rowEnabled && switchView.getVisibility() == View.VISIBLE && switchView.isEnabled()) {
                switchView.toggle();
            }
        });
    }

    public void bind(FeatureCatalog.FeatureSpec spec, boolean checked) {
        titleView.setText(spec.titleRes);
        summaryRes = spec.summaryRes;
        summaryView.setText(spec.summaryRes);
        detailView.setText(spec.detailRes);
        updateSwitchContentDescription();
        trailingStatusView.setVisibility(View.GONE);
        trailingStatusView.setContentDescription(null);
        switchView.setVisibility(View.VISIBLE);
        updateInteraction(true, 1f);
        setCheckedSilently(checked);
    }

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    public void setCheckedSilently(boolean checked) {
        binding = true;
        switchView.setChecked(checked);
        binding = false;
    }

    public boolean isChecked() { return switchView.isChecked(); }

    public void setRowEnabled(boolean enabled) {
        updateInteraction(enabled, enabled ? 1f : 0.55f);
    }

    public void showUnsupported() {
        showUnsupported(isChecked());
    }

    public void showUnsupported(boolean currentlyEnabled) {
        if (summaryRes != 0) summaryView.setText(summaryRes);
        if (currentlyEnabled) {
            trailingStatusView.setVisibility(View.GONE);
            switchView.setVisibility(View.VISIBLE);
            summaryView.append("\n" + getContext().getString(
                    R.string.feature_unavailable_enabled_summary));
            updateSwitchContentDescription();
            updateInteraction(true, 1f);
            return;
        }
        summaryView.append("\n" + getContext().getString(R.string.feature_unavailable_summary));
        showTrailingStatus(R.string.trailing_unsupported);
    }

    public void showDiagnosticOnly() {
        showTrailingStatus(R.string.status_diagnostic);
    }

    private void showTrailingStatus(int textRes) {
        switchView.setVisibility(View.GONE);
        trailingStatusView.setText(textRes);
        trailingStatusView.setContentDescription(getContext().getString(
                R.string.setting_status_accessibility,
                titleView.getText(),
                summaryView.getText(),
                detailView.getText(),
                getContext().getString(textRes)));
        trailingStatusView.setVisibility(View.VISIBLE);
        updateInteraction(false, 0.62f);
    }

    private void updateInteraction(boolean enabled, float alpha) {
        rowEnabled = enabled;
        boolean switchAvailable = switchView.getVisibility() == View.VISIBLE;
        switchView.setEnabled(enabled && switchAvailable);
        switchView.setFocusable(enabled && switchAvailable);
        setClickable(enabled && switchAvailable);
        setFocusable(false);
        setAlpha(alpha);
    }

    private void updateSwitchContentDescription() {
        switchView.setContentDescription(getContext().getString(
                R.string.setting_switch_accessibility_detail,
                titleView.getText(),
                summaryView.getText(),
                detailView.getText()));
    }
}
