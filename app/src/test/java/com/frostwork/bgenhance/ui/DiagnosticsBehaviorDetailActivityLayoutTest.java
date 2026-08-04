package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.BehaviorDetailStats;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsBehavior;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class DiagnosticsBehaviorDetailActivityLayoutTest {
    @Test
    public void detailIntentOpensMaterialGroupedListForSelectedBehavior() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = DiagnosticsBehaviorDetailActivity.createIntent(
                context, DiagnosticsBehavior.KILL);
        DiagnosticsBehaviorDetailActivity activity = Robolectric.buildActivity(
                DiagnosticsBehaviorDetailActivity.class, intent).setup().get();

        assertEquals(activity.getString(R.string.diagnostics_behavior_kill),
                ((TextView) activity.findViewById(R.id.diagnostics_detail_title))
                        .getText().toString());
        assertTrue(activity.findViewById(R.id.diagnostics_detail_card)
                instanceof MaterialCardView);
        RecyclerView list = activity.findViewById(R.id.diagnostics_detail_list);
        assertTrue(list.getAdapter() instanceof DiagnosticsBehaviorDetailRowsAdapter);
        assertTrue(list.getLayoutManager() instanceof LinearLayoutManager);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_detail_summary)
                        .getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_detail_history_notice)
                        .getAccessibilityLiveRegion());
        assertEquals(View.GONE,
                activity.findViewById(R.id.diagnostics_detail_history_notice).getVisibility());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_detail_loading_state)
                        .getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_detail_error_state)
                        .getAccessibilityLiveRegion());
        activity.finish();
    }

    @Test
    public void invalidDetailIntentFinishesWithoutCreatingContent() {
        DiagnosticsBehaviorDetailActivity activity = Robolectric.buildActivity(
                DiagnosticsBehaviorDetailActivity.class, new Intent(
                        RuntimeEnvironment.getApplication(),
                        DiagnosticsBehaviorDetailActivity.class)).create().get();
        assertTrue(activity.isFinishing());
        assertEquals(0, activity.findViewById(R.id.diagnostics_detail_card) == null ? 0 : 1);
    }

    @Test
    public void summaryCardsExposeChevronAndOpenTheMatchingSecondLevelPage() {
        DiagnosticsActivity activity = Robolectric.buildActivity(
                DiagnosticsActivity.class).setup().get();
        RecyclerView list = new RecyclerView(activity);
        DiagnosticsRowsAdapter adapter = new DiagnosticsRowsAdapter(activity, behavior ->
                activity.startActivity(
                        DiagnosticsBehaviorDetailActivity.createIntent(activity, behavior)));
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        adapter.submitList(new java.util.ArrayList<>(new DiagnosticsUiMapper().mapBehaviors(
                RecentBehaviorStats.fromAggregateRows(java.util.Collections.emptyList()))));
        int width = dp(activity, 360);
        list.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(activity, 640), View.MeasureSpec.EXACTLY));
        list.layout(0, 0, width, dp(activity, 640));

        View first = list.getChildAt(0);
        assertNotNull(first);
        assertEquals(View.VISIBLE,
                first.findViewById(R.id.diagnostics_row_chevron).getVisibility());
        assertTrue(first.performClick());
        Intent started = ((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(DiagnosticsBehaviorDetailActivity.class.getName(),
                started.getComponent().getClassName());
        adapter.close();
        activity.finish();
    }

    @Test
    public void verboseLoggingLivesOnlyInRoundedOverflowSurface() throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(
                DiagnosticsActivity.class).setup().get();
        assertEquals(0, activity.getResources().getIdentifier(
                "diagnostics_verbose_switch", "id", activity.getPackageName()));

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertNotNull(toolbar.getMenu().findItem(R.id.action_more_diagnostics));
        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_more_diagnostics, 0));
        PopupWindow popup = popup(activity);
        assertNotNull(popup);
        MaterialSwitch verbose = popup.getContentView().findViewById(
                R.id.diagnostics_popup_verbose_switch);
        assertNotNull(verbose);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                popup.getContentView().findViewById(
                        R.id.diagnostics_popup_verbose_row)
                        .getImportantForAccessibility());
        String description = verbose.getContentDescription().toString();
        assertTrue(description.contains(activity.getString(R.string.verbose_title)));
        assertTrue(description.contains(activity.getString(R.string.verbose_summary)));
        MaterialCardView card = (MaterialCardView) popup.getContentView();
        android.graphics.RectF bounds = new android.graphics.RectF(0f, 0f, 100f, 100f);
        assertTrue(card.getShapeAppearanceModel().getTopLeftCornerSize()
                .getCornerSize(bounds) > 0f);
        popup.dismiss();
        activity.finish();
    }

    @Test
    public void detailLayoutsKeepScrollableSpaceAtDoubleFontScale() {
        assertDetailListHasSpace(configuredContext(false), 360, 640, 300);
        assertDetailListHasSpace(configuredContext(true), 640, 320, 150);

        View row = LayoutInflater.from(configuredContext(false))
                .inflate(R.layout.item_diagnostics_behavior_detail, null, false);
        assertEquals(Integer.MAX_VALUE,
                ((TextView) row.findViewById(R.id.diagnostics_detail_row_title)).getMaxLines());
        assertFalse(((TextView) row.findViewById(
                R.id.diagnostics_detail_row_metrics)).isSingleLine());
    }

    @Test
    public void lastDetailRowKeepsTheSameBottomSpaceAsRowsWithDividers() {
        Context context = configuredContext(false);
        DiagnosticsUiMapper.BehaviorDetailRow model = new DiagnosticsUiMapper()
                .mapBehaviorDetails(
                        DiagnosticsBehavior.KILL,
                        BehaviorDetailStats.success(Collections.emptyList()))
                .get(0);
        View row = LayoutInflater.from(context).inflate(
                R.layout.item_diagnostics_behavior_detail, null, false);
        DiagnosticsBehaviorDetailRowsAdapter.RowViewHolder holder =
                new DiagnosticsBehaviorDetailRowsAdapter.RowViewHolder(row);

        holder.bind(model, false);
        int withDivider = measuredHeight(row, context);
        assertEquals(View.VISIBLE, row.findViewById(
                R.id.diagnostics_detail_row_divider).getVisibility());

        holder.bind(model, true);
        int asLast = measuredHeight(row, context);
        assertEquals(View.INVISIBLE, row.findViewById(
                R.id.diagnostics_detail_row_divider).getVisibility());
        assertEquals(withDivider, asLast);
    }

    private static void assertDetailListHasSpace(
            Context context, int widthDp, int heightDp, int minimumDp) {
        View root = LayoutInflater.from(context).inflate(
                R.layout.activity_diagnostics_behavior_detail, null, false);
        root.findViewById(R.id.diagnostics_detail_list).setVisibility(View.VISIBLE);
        int width = dp(context, widthDp);
        int height = dp(context, heightDp);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertTrue(root.findViewById(R.id.diagnostics_detail_list).getMeasuredHeight()
                >= dp(context, minimumDp));
    }

    private static Context configuredContext(boolean landscape) {
        Context base = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = 2f;
        configuration.orientation = landscape
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        configuration.screenWidthDp = landscape ? 640 : 360;
        configuration.screenHeightDp = landscape ? 320 : 640;
        return new ContextThemeWrapper(
                base.createConfigurationContext(configuration), R.style.AppTheme);
    }

    private static PopupWindow popup(DiagnosticsActivity activity) throws Exception {
        Field field = DiagnosticsActivity.class.getDeclaredField("optionsPopup");
        field.setAccessible(true);
        return (PopupWindow) field.get(activity);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int measuredHeight(View view, Context context) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(dp(context, 324), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }
}
