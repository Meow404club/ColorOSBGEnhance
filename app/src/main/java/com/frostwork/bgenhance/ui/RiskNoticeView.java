package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.frostwork.bgenhance.R;

/** Section header for settings that share the same risk and compatibility labels. */
public final class RiskNoticeView extends LinearLayout {
    private final LinearLayout badges;
    private final TextView summary;

    public RiskNoticeView(Context context) {
        this(context, null);
    }

    public RiskNoticeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RiskNoticeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setPadding(UiUtils.dp(context, 20), UiUtils.dp(context, 16),
                UiUtils.dp(context, 20), UiUtils.dp(context, 14));

        badges = new LinearLayout(context);
        badges.setOrientation(HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        addView(badges, new LayoutParams(LayoutParams.MATCH_PARENT, UiUtils.dp(context, 28)));

        summary = new TextView(context);
        summary.setTextColor(context.getColor(R.color.item_summary));
        summary.setTextSize(13f);
        summary.setLineSpacing(0f, 1.08f);
        LayoutParams summaryParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = UiUtils.dp(context, 9);
        addView(summary, summaryParams);
    }

    public void bind(FeatureCatalog.Risk risk, boolean experimental) {
        badges.removeAllViews();
        addBadge(labelForRisk(risk), colorForRisk(risk), false);
        if (experimental) {
            addBadge(R.string.risk_experimental, R.color.risk_badge_experimental, true);
        }
        summary.setText(summaryFor(risk, experimental));
    }

    private void addBadge(int textRes, int colorRes, boolean addStartMargin) {
        TextView badge = new TextView(getContext());
        badge.setText(textRes);
        badge.setTextColor(getContext().getColor(android.R.color.white));
        badge.setTextSize(10.5f);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(getContext().getColor(colorRes));
        background.setCornerRadius(UiUtils.dp(getContext(), 7));
        badge.setBackground(background);

        LayoutParams params = new LayoutParams(
                UiUtils.dp(getContext(), 68), UiUtils.dp(getContext(), 28));
        if (addStartMargin) params.setMarginStart(UiUtils.dp(getContext(), 8));
        badges.addView(badge, params);
    }

    private static int labelForRisk(FeatureCatalog.Risk risk) {
        if (risk == FeatureCatalog.Risk.VERY_HIGH) return R.string.risk_very_high;
        if (risk == FeatureCatalog.Risk.HIGH) return R.string.risk_high;
        if (risk == FeatureCatalog.Risk.MEDIUM) return R.string.risk_medium;
        return R.string.risk_recommended;
    }

    private static int colorForRisk(FeatureCatalog.Risk risk) {
        if (risk == FeatureCatalog.Risk.VERY_HIGH) return R.color.risk_badge_very_high;
        if (risk == FeatureCatalog.Risk.HIGH) return R.color.risk_badge_high;
        if (risk == FeatureCatalog.Risk.MEDIUM) return R.color.warning;
        return R.color.success;
    }

    private static int summaryFor(FeatureCatalog.Risk risk, boolean experimental) {
        if (experimental) {
            if (risk == FeatureCatalog.Risk.VERY_HIGH) {
                return R.string.risk_notice_very_high_experimental;
            }
            if (risk == FeatureCatalog.Risk.HIGH) {
                return R.string.risk_notice_high_experimental;
            }
            return R.string.risk_notice_experimental;
        }
        if (risk == FeatureCatalog.Risk.VERY_HIGH) return R.string.risk_notice_very_high;
        if (risk == FeatureCatalog.Risk.HIGH) return R.string.risk_notice_high;
        if (risk == FeatureCatalog.Risk.MEDIUM) return R.string.risk_notice_medium;
        return R.string.risk_notice_recommended;
    }
}
