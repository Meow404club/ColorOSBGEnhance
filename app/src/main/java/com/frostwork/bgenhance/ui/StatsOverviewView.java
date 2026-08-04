package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import com.frostwork.bgenhance.R;

/** Three-metric diagnostics summary shown on the home page. */
public final class StatsOverviewView extends LinearLayout {
    private final TextView observedCount;
    private final TextView blockedCount;
    private final TextView failOpenCount;
    private final TextView historyNotice;
    private final TextView hint;
    private final ImageView chevron;

    public StatsOverviewView(Context context) {
        this(context, null);
    }

    public StatsOverviewView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatsOverviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout metrics = new LinearLayout(context);
        metrics.setOrientation(HORIZONTAL);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.setPadding(UiUtils.dp(context, 8), UiUtils.dp(context, 14),
                UiUtils.dp(context, 8), UiUtils.dp(context, 12));
        observedCount = addMetric(metrics, R.string.stats_metric_observed, R.color.brand);
        addVerticalDivider(metrics);
        blockedCount = addMetric(metrics, R.string.stats_metric_blocked, R.color.error);
        addVerticalDivider(metrics);
        failOpenCount = addMetric(metrics, R.string.stats_metric_fail_open, R.color.warning);
        addView(metrics, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        historyNotice = new TextView(context);
        historyNotice.setText(R.string.diagnostics_history_truncated);
        historyNotice.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_Summary);
        historyNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        historyNotice.setPadding(UiUtils.dp(context, 20), 0,
                UiUtils.dp(context, 20), UiUtils.dp(context, 12));
        historyNotice.setVisibility(View.GONE);
        addView(historyNotice,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(context.getColor(R.color.divider));
        LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, UiUtils.dp(context, 1));
        dividerParams.setMarginStart(UiUtils.dp(context, 20));
        dividerParams.setMarginEnd(UiUtils.dp(context, 20));
        addView(divider, dividerParams);

        LinearLayout hintRow = new LinearLayout(context);
        hintRow.setOrientation(HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.setPadding(UiUtils.dp(context, 20), UiUtils.dp(context, 10),
                UiUtils.dp(context, 14), UiUtils.dp(context, 12));

        hint = new TextView(context);
        hint.setTextAppearance(R.style.TextAppearance_ColorOSBgEnhance_Summary);
        hint.setGravity(Gravity.CENTER);
        hintRow.addView(hint, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setContentDescription(null);
        chevron.setVisibility(View.GONE);
        LayoutParams chevronParams = new LayoutParams(UiUtils.dp(context, 24), UiUtils.dp(context, 24));
        chevronParams.setMarginStart(UiUtils.dp(context, 8));
        hintRow.addView(chevron, chevronParams);
        addView(hintRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void bind(long observed, long blocked, long failOpen, CharSequence hintText,
                     boolean actionable) {
        bind(observed, blocked, failOpen, hintText, actionable, false);
    }

    public void bind(long observed, long blocked, long failOpen, CharSequence hintText,
                     boolean actionable, boolean historyTruncated) {
        observedCount.setText(String.valueOf(observed));
        blockedCount.setText(String.valueOf(blocked));
        failOpenCount.setText(String.valueOf(failOpen));
        historyNotice.setVisibility(historyTruncated ? View.VISIBLE : View.GONE);
        bindHint(hintText, actionable);
    }

    public void bindLoading(CharSequence hintText, boolean actionable) {
        bindPlaceholder("...");
        historyNotice.setVisibility(View.GONE);
        bindHint(hintText, actionable);
    }

    public void bindUnavailable(CharSequence hintText, boolean actionable) {
        bindPlaceholder("-");
        historyNotice.setVisibility(View.GONE);
        bindHint(hintText, actionable);
    }

    private void bindPlaceholder(CharSequence value) {
        observedCount.setText(value);
        blockedCount.setText(value);
        failOpenCount.setText(value);
    }

    private void bindHint(CharSequence hintText, boolean actionable) {
        hint.setText(hintText);
        hint.setGravity(actionable ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        chevron.setVisibility(actionable ? View.VISIBLE : View.GONE);
    }

    private TextView addMetric(LinearLayout parent, int labelRes, int colorRes) {
        LinearLayout metric = new LinearLayout(getContext());
        metric.setOrientation(VERTICAL);
        metric.setGravity(Gravity.CENTER);

        TextView count = new TextView(getContext());
        count.setTextColor(getContext().getColor(colorRes));
        count.setTextSize(22f);
        count.setTypeface(count.getTypeface(), android.graphics.Typeface.BOLD);
        count.setGravity(Gravity.CENTER);
        count.setIncludeFontPadding(false);
        count.setSingleLine(true);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                count, 8, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        metric.addView(count, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(getContext());
        label.setText(labelRes);
        label.setTextColor(getContext().getColor(R.color.item_summary));
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        if (usesExpandedMetricLabels()) {
            label.setMinLines(2);
            label.setMaxLines(2);
        }
        LayoutParams labelParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = UiUtils.dp(getContext(), 5);
        metric.addView(label, labelParams);

        parent.addView(metric, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        return count;
    }

    private void addVerticalDivider(LinearLayout parent) {
        View divider = new View(getContext());
        divider.setBackgroundColor(getContext().getColor(R.color.divider));
        parent.addView(divider, new LayoutParams(UiUtils.dp(getContext(), 1),
                LayoutParams.MATCH_PARENT));
    }

    private boolean usesExpandedMetricLabels() {
        return getResources().getConfiguration().fontScale >= 1.5f;
    }
}
