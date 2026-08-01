package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
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

    public SettingSwitchRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(UiUtils.dp(context, 92));
        setPadding(UiUtils.dp(context, 20), UiUtils.dp(context, 14),
                UiUtils.dp(context, 16), UiUtils.dp(context, 14));
        setForeground(context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground})
                .getDrawable(0));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(VERTICAL);
        LayoutParams textParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginEnd(UiUtils.dp(context, 12));
        addView(textColumn, textParams);

        titleView = new TextView(context);
        titleView.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_SettingTitle);
        titleView.setSingleLine(false);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
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
        addView(trailing, new LayoutParams(UiUtils.dp(context, 68), UiUtils.dp(context, 48)));

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
        trailingStatusView.setTextSize(11f);
        trailingStatusView.setVisibility(View.GONE);
        GradientDrawable neutral = new GradientDrawable();
        neutral.setShape(GradientDrawable.RECTANGLE);
        neutral.setColor(context.getColor(R.color.chip_neutral));
        neutral.setCornerRadius(UiUtils.dp(context, 10));
        trailingStatusView.setBackground(neutral);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                UiUtils.dp(context, 68), UiUtils.dp(context, 34), Gravity.CENTER);
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
        summaryView.setText(spec.summaryRes);
        detailView.setText(spec.detailRes);
        trailingStatusView.setVisibility(View.GONE);
        switchView.setVisibility(View.VISIBLE);
        rowEnabled = true;
        switchView.setEnabled(true);
        setAlpha(1f);
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
        rowEnabled = enabled;
        switchView.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.55f);
    }

    public void showUnsupported() {
        showTrailingStatus(R.string.trailing_unsupported);
        summaryView.append("\n" + getContext().getString(R.string.feature_unavailable_summary));
    }

    public void showDiagnosticOnly() {
        showTrailingStatus(R.string.status_diagnostic);
    }

    private void showTrailingStatus(int textRes) {
        rowEnabled = false;
        switchView.setEnabled(false);
        switchView.setVisibility(View.GONE);
        trailingStatusView.setText(textRes);
        trailingStatusView.setVisibility(View.VISIBLE);
        setAlpha(0.62f);
    }
}
