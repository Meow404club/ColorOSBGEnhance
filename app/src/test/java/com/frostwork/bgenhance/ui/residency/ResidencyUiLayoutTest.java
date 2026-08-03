package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.ColorUtils;
import com.frostwork.bgenhance.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyUiLayoutTest {
    @Test
    public void unavailableCoreExemptionIsHiddenAndRiskNoticePrecedesCustomControls() {
        View root = inflate(R.layout.activity_residency_policy);
        assertEquals(View.GONE,
                root.findViewById(R.id.residency_freeze_core_exemption).getVisibility());

        View risk = root.findViewById(R.id.residency_risk_notices);
        View custom = root.findViewById(R.id.residency_custom_section);
        assertNotNull(risk);
        assertNotNull(custom);
        assertTrue(risk.getParent() == custom.getParent());
        ViewGroup parent = (ViewGroup) risk.getParent();
        assertTrue(parent.indexOfChild(risk) < parent.indexOfChild(custom));
    }

    @Test
    public void iconOnlySwitchesExposeUserFacingNamesAndProgressBlocksTouches() {
        View policy = inflate(R.layout.activity_residency_policy);
        View picker = inflate(R.layout.activity_residency_app_picker);
        View importer = inflate(R.layout.activity_residency_import);

        assertHasDescription(policy, R.id.residency_policy_enabled);
        assertHasDescription(picker, R.id.residency_show_system);
        assertHasDescription(importer, R.id.residency_import_kill);
        assertTrue(importer.findViewById(R.id.residency_import_progress).isClickable());
        assertTrue(importer.findViewById(R.id.residency_import_progress).isFocusable());
    }

    @Test
    public void whiteRiskBadgeTextMeetsNormalTextContrast() {
        ContextThemeWrapper context = themedContext();
        int high = context.getColor(R.color.risk_badge_high);
        int experimental = context.getColor(R.color.risk_badge_experimental);
        assertTrue(ColorUtils.calculateContrast(Color.WHITE, high) >= 4.5d);
        assertTrue(ColorUtils.calculateContrast(Color.WHITE, experimental) >= 4.5d);
    }

    @Test
    public void loadingRecreationDoesNotRestoreDefaultXmlForm() {
        Bundle loadingState = new Bundle();
        loadingState.putString("selected_mode", "BALANCED");
        assertTrue(!ResidencyPolicyActivity.shouldRestoreFormState(loadingState));

        loadingState.putBoolean("form_ready", true);
        assertTrue(ResidencyPolicyActivity.shouldRestoreFormState(loadingState));
    }

    @Test
    public void applyNoticeDoesNotPromiseImmediateApplicationBeforeStatusLoads() {
        ContextThemeWrapper context = themedContext();
        View root = inflate(context, R.layout.activity_residency_policy);
        android.widget.TextView notice = root.findViewById(
                R.id.residency_policy_apply_notice);

        assertEquals(context.getString(R.string.status_checking_summary),
                notice.getText().toString());
    }

    @Test
    public void landscapeLayoutsKeepListsAndImportActionsVisibleAtDoubleFontScale() {
        ContextThemeWrapper context = landscapeContext(2f);
        assertListHasSpace(context, R.layout.activity_residency_apps, R.id.residency_app_list);
        assertListHasSpace(
                context, R.layout.activity_residency_app_picker, R.id.residency_app_list);

        View importer = inflate(context, R.layout.activity_residency_import);
        importer.findViewById(R.id.residency_import_initial).setVisibility(View.GONE);
        importer.findViewById(R.id.residency_import_preview).setVisibility(View.VISIBLE);
        measureLandscape(importer, context);
        View list = importer.findViewById(R.id.residency_import_list);
        View apply = importer.findViewById(R.id.residency_import_apply);
        View reselect = importer.findViewById(R.id.residency_import_reselect);
        assertTrue(list.getMeasuredHeight() >= dp(context, 48));
        assertTrue(apply.getMeasuredHeight() > 0);
        assertTrue(reselect.getMeasuredHeight() > 0);
        assertTrue(reselect.getBottom() <= importer.getMeasuredHeight());
    }

    @Test
    public void portraitLayoutsRetainUsableContentAtDoubleFontScale() {
        ContextThemeWrapper context = portraitContext(2f);
        assertPortraitListHasSpace(
                context, R.layout.activity_residency_apps, R.id.residency_app_list);
        assertPortraitListHasSpace(
                context, R.layout.activity_residency_app_picker, R.id.residency_app_list);

        View importer = inflate(context, R.layout.activity_residency_import);
        importer.findViewById(R.id.residency_import_page_title).setVisibility(View.GONE);
        importer.findViewById(R.id.residency_import_page_subtitle).setVisibility(View.GONE);
        importer.findViewById(R.id.residency_import_initial).setVisibility(View.GONE);
        importer.findViewById(R.id.residency_import_preview).setVisibility(View.VISIBLE);
        measure(importer, context, 360, 640);
        assertTrue(importer.findViewById(R.id.residency_import_list).getMeasuredHeight()
                >= dp(context, 48));
        assertTrue(importer.findViewById(R.id.residency_import_reselect).getBottom()
                <= importer.getMeasuredHeight());
    }

    private static void assertHasDescription(View root, int id) {
        CharSequence description = root.findViewById(id).getContentDescription();
        assertNotNull(description);
        assertTrue(description.length() > 0);
    }

    private static View inflate(int layout) {
        return LayoutInflater.from(themedContext()).inflate(layout, null, false);
    }

    private static View inflate(Context context, int layout) {
        return LayoutInflater.from(context).inflate(layout, null, false);
    }

    private static void assertListHasSpace(Context context, int layout, int listId) {
        View root = inflate(context, layout);
        measureLandscape(root, context);
        assertTrue(root.findViewById(listId).getMeasuredHeight() >= dp(context, 96));
    }

    private static void assertPortraitListHasSpace(Context context, int layout, int listId) {
        View root = inflate(context, layout);
        measure(root, context, 360, 640);
        assertTrue(root.findViewById(listId).getMeasuredHeight() >= dp(context, 72));
    }

    private static void measureLandscape(View root, Context context) {
        measure(root, context, 640, 320);
    }

    private static void measure(
            View root, Context context, int widthDp, int heightDp) {
        int width = dp(context, widthDp);
        int height = dp(context, heightDp);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static ContextThemeWrapper landscapeContext(float fontScale) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        configuration.screenWidthDp = 640;
        configuration.screenHeightDp = 320;
        configuration.fontScale = fontScale;
        Context configured = application.createConfigurationContext(configuration);
        return new ContextThemeWrapper(configured, R.style.AppTheme);
    }

    private static ContextThemeWrapper portraitContext(float fontScale) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        configuration.screenWidthDp = 360;
        configuration.screenHeightDp = 640;
        configuration.fontScale = fontScale;
        Context configured = application.createConfigurationContext(configuration);
        return new ContextThemeWrapper(configured, R.style.AppTheme);
    }

    private static ContextThemeWrapper themedContext() {
        return new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme);
    }
}
