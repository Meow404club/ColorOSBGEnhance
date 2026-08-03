package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.frostwork.bgenhance.R;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
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
    public void summaryTextMeetsNormalTextContrastOnPageAndCards() {
        ContextThemeWrapper context = themedContext();
        int summary = context.getColor(R.color.item_summary);
        assertTrue(ColorUtils.calculateContrast(
                summary, context.getColor(R.color.card_bg)) >= 4.5d);
        assertTrue(ColorUtils.calculateContrast(
                summary, context.getColor(R.color.page_bg)) >= 4.5d);
    }

    @Test
    public void nightThemeKeepsBrandedMaterialControlRoles() {
        ContextThemeWrapper context = nightContext();
        assertEquals(context.getColor(R.color.brand_soft),
                resolvedColor(context,
                        com.google.android.material.R.attr.colorSecondaryContainer));
        assertEquals(context.getColor(R.color.field_bg),
                resolvedColor(context,
                        com.google.android.material.R.attr.colorSurfaceVariant));

        TypedValue switchStyle = resolvedAttribute(
                context, com.google.android.material.R.attr.materialSwitchStyle);
        assertEquals(R.style.Widget_ColorOSBgEnhance_MaterialSwitch,
                switchStyle.resourceId);
    }

    @Test
    public void residencyInputsShareFilledMaterialStyling() {
        View apps = inflate(R.layout.activity_residency_apps);
        View picker = inflate(R.layout.activity_residency_app_picker);
        View policy = inflate(R.layout.activity_residency_policy);

        assertFilled(apps, R.id.residency_mode_filter);
        assertFilled(apps, R.id.residency_status_filter);
        assertFilled(picker, R.id.residency_availability_filter);
        assertFilled(policy, R.id.residency_mode_selector);
    }

    @Test
    public void everyResidencyDropdownIsCenteredAndUsesTitleTypography() {
        ContextThemeWrapper portrait = portraitContext(1f);
        assertDropdownValue(
                portrait, R.layout.activity_residency_policy, R.id.residency_mode_selector);
        assertDropdownValue(
                portrait, R.layout.activity_residency_apps, R.id.residency_mode_filter);
        assertDropdownValue(
                portrait, R.layout.activity_residency_apps, R.id.residency_status_filter);
        assertDropdownValue(
                portrait,
                R.layout.activity_residency_app_picker,
                R.id.residency_availability_filter);

        ContextThemeWrapper landscape = landscapeContext(1f);
        assertDropdownValue(
                landscape, R.layout.activity_residency_apps, R.id.residency_mode_filter);
        assertDropdownValue(
                landscape, R.layout.activity_residency_apps, R.id.residency_status_filter);
        assertDropdownValue(
                landscape,
                R.layout.activity_residency_app_picker,
                R.id.residency_availability_filter);

        TypedArray style = portrait.obtainStyledAttributes(
                R.style.Widget_ColorOSBgEnhance_DropdownValue,
                new int[] {android.R.attr.textStyle});
        try {
            assertEquals(Typeface.BOLD, style.getInt(0, Typeface.NORMAL));
        } finally {
            style.recycle();
        }
    }

    @Test
    public void everyDropdownLabelUsesTheSettingTitleHierarchy() {
        ContextThemeWrapper portrait = portraitContext(1f);
        assertDropdownLabel(
                portrait, R.layout.activity_residency_apps, R.id.residency_mode_filter_label);
        assertDropdownLabel(
                portrait, R.layout.activity_residency_apps, R.id.residency_status_filter_label);
        assertDropdownLabel(
                portrait,
                R.layout.activity_residency_app_picker,
                R.id.residency_availability_filter_label);

        View picker = inflate(portrait, R.layout.activity_residency_app_picker);
        assertSameTextStyle(
                picker.findViewById(R.id.residency_show_system_title),
                picker.findViewById(R.id.residency_availability_filter_label));

        ContextThemeWrapper landscape = landscapeContext(1f);
        assertDropdownLabel(
                landscape, R.layout.activity_residency_apps, R.id.residency_mode_filter_label);
        assertDropdownLabel(
                landscape, R.layout.activity_residency_apps, R.id.residency_status_filter_label);
        assertDropdownLabel(
                landscape,
                R.layout.activity_residency_app_picker,
                R.id.residency_availability_filter_label);

        TypedArray titleStyle = portrait.obtainStyledAttributes(
                R.style.TextAppearance_ColorOSBgEnhance_SettingTitle,
                new int[] {android.R.attr.textStyle});
        try {
            assertEquals(Typeface.BOLD, titleStyle.getInt(0, Typeface.NORMAL));
        } finally {
            titleStyle.recycle();
        }
    }

    @Test
    public void enlargedTextStacksCompactFilterRowsAboveFullWidthDropdowns() {
        ContextThemeWrapper context = portraitContext(2f);
        View apps = inflate(context, R.layout.activity_residency_apps);
        ResidencyFilterRows.stackForLargeText(
                apps,
                2f,
                R.id.residency_mode_filter_row,
                R.id.residency_mode_filter_label,
                R.id.residency_mode_filter);
        ResidencyFilterRows.stackForLargeText(
                apps,
                2f,
                R.id.residency_status_filter_row,
                R.id.residency_status_filter_label,
                R.id.residency_status_filter);
        assertStackedFilterRow(
                apps, R.id.residency_mode_filter_row, R.id.residency_mode_filter);
        assertStackedFilterRow(
                apps, R.id.residency_status_filter_row, R.id.residency_status_filter);

        View picker = inflate(context, R.layout.activity_residency_app_picker);
        ResidencyFilterRows.stackForLargeText(
                picker,
                2f,
                R.id.residency_availability_filter_row,
                R.id.residency_availability_filter_label,
                R.id.residency_availability_filter);
        assertStackedFilterRow(
                picker,
                R.id.residency_availability_filter_row,
                R.id.residency_availability_filter);
    }

    @Test
    public void dropdownPopupUsesThemedSurfaceAndOptionLayout() {
        ContextThemeWrapper context = themedContext();
        TypedArray style = context.obtainStyledAttributes(
                R.style.Widget_ColorOSBgEnhance_DropdownValue,
                new int[] {
                    com.google.android.material.R.attr.simpleItemLayout,
                    android.R.attr.popupBackground
                });
        try {
            assertEquals(R.layout.item_residency_dropdown_option, style.getResourceId(0, 0));
            assertEquals(R.drawable.bg_residency_dropdown_popup, style.getResourceId(1, 0));
        } finally {
            style.recycle();
        }

        TextView option = (TextView) inflate(context, R.layout.item_residency_dropdown_option);
        assertEquals(context.getResources().getDimension(R.dimen.setting_title_size),
                option.getTextSize(), 0.01f);
        assertTrue(option.getMinimumHeight()
                >= context.getResources().getDimensionPixelSize(R.dimen.field_min_height));

        MaterialAutoCompleteTextView field = inflate(
                context, R.layout.activity_residency_app_picker)
                .findViewById(R.id.residency_availability_filter);
        Drawable background = field.getDropDownBackground();
        assertNotNull(background);
        assertEquals(dp(context, 4), field.getDropDownVerticalOffset());
    }

    @Test
    public void primarySwitchRowsAreClickableAndCountsAnnounceUpdates() {
        View apps = inflate(R.layout.activity_residency_apps);
        View picker = inflate(R.layout.activity_residency_app_picker);
        View policy = inflate(R.layout.activity_residency_policy);

        assertTrue(picker.findViewById(R.id.residency_show_system_row).isClickable());
        assertTrue(policy.findViewById(R.id.residency_policy_enabled_row).isClickable());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                apps.findViewById(R.id.residency_result_count).getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                picker.findViewById(R.id.residency_result_count).getAccessibilityLiveRegion());
    }

    @Test
    public void pickerListSurfaceOwnsEveryDynamicListState() {
        View picker = inflate(R.layout.activity_residency_app_picker);
        View surface = picker.findViewById(R.id.residency_list_surface);

        assertTrue(isDescendant(surface, picker.findViewById(R.id.residency_app_list)));
        assertTrue(isDescendant(surface, picker.findViewById(R.id.residency_loading_state)));
        assertTrue(isDescendant(surface, picker.findViewById(R.id.residency_empty_state)));
        assertTrue(isDescendant(surface, picker.findViewById(R.id.residency_error_state)));
        assertTrue(!isDescendant(
                surface, picker.findViewById(R.id.residency_show_system_row)));
    }

    @Test
    public void wakeChoicesReuseHomepageSegmentedControlMetrics() {
        View home = inflate(R.layout.activity_settings);
        View policy = inflate(R.layout.activity_residency_policy);
        assertSameSegmentStyle(
                home.findViewById(R.id.button_preset_balanced),
                policy.findViewById(R.id.residency_packet_allow));
        assertSameSegmentStyle(
                home.findViewById(R.id.button_preset_strong),
                policy.findViewById(R.id.residency_packet_throttle));
    }

    @Test
    public void doubleFontScaleKeepsWakeSegmentsHorizontalAndComplete() {
        ContextThemeWrapper context = portraitContext(2f);
        View policy = inflate(context, R.layout.activity_residency_policy);
        MaterialButtonToggleGroup packet = policy.findViewById(R.id.residency_packet_group);
        packet.measure(
                View.MeasureSpec.makeMeasureSpec(dp(context, 324), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        packet.layout(0, 0, packet.getMeasuredWidth(), packet.getMeasuredHeight());

        assertEquals(LinearLayout.HORIZONTAL, packet.getOrientation());
        assertCompleteSegment(packet.findViewById(R.id.residency_packet_allow));
        assertCompleteSegment(packet.findViewById(R.id.residency_packet_throttle));
        assertCompleteSegment(packet.findViewById(R.id.residency_packet_block));
    }

    @Test
    public void doubleFontScaleKeepsLongestFilterValuesVisible() {
        ContextThemeWrapper context = portraitContext(2f);
        View apps = inflate(context, R.layout.activity_residency_apps);
        apps.findViewById(R.id.residency_filters).setVisibility(View.VISIBLE);
        assertNoEllipsis(
                apps, context, R.id.residency_mode_filter, "系统管理");
        assertNoEllipsis(
                apps, context, R.id.residency_status_filter, "全部状态");

        View picker = inflate(context, R.layout.activity_residency_app_picker);
        assertNoEllipsis(
                picker, context, R.id.residency_availability_filter, "不可配置");
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

    private static void assertFilled(View root, int editTextId) {
        View editText = root.findViewById(editTextId);
        View parent = (View) editText.getParent();
        while (parent != null && !(parent instanceof TextInputLayout)) {
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        assertNotNull(parent);
        assertEquals(TextInputLayout.BOX_BACKGROUND_FILLED,
                ((TextInputLayout) parent).getBoxBackgroundMode());
    }

    private static void assertDropdownValue(Context context, int layout, int id) {
        MaterialAutoCompleteTextView field = inflate(context, layout).findViewById(id);
        assertTrue(field instanceof CenteredDropdownTextView);
        assertEquals(Gravity.CENTER, field.getGravity());
        assertEquals(context.getResources().getDimension(R.dimen.setting_title_size),
                field.getTextSize(), 0.01f);
        assertEquals(1, field.getMaxLines());
    }

    private static void assertDropdownLabel(Context context, int layout, int id) {
        TextView label = inflate(context, layout).findViewById(id);
        assertEquals(context.getResources().getDimension(R.dimen.setting_title_size),
                label.getTextSize(), 0.01f);
        assertEquals(context.getColor(R.color.item_title), label.getCurrentTextColor());
    }

    private static void assertStackedFilterRow(View root, int rowId, int fieldId) {
        LinearLayout row = root.findViewById(rowId);
        assertEquals(LinearLayout.VERTICAL, row.getOrientation());
        View input = root.findViewById(fieldId);
        while (input.getParent() != row) {
            input = (View) input.getParent();
        }
        LinearLayout.LayoutParams inputParams =
                (LinearLayout.LayoutParams) input.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, inputParams.width);
        assertEquals(0f, inputParams.weight, 0f);
        assertTrue(inputParams.topMargin > 0);
    }

    private static void assertSameTextStyle(TextView expected, TextView actual) {
        assertEquals(expected.getTextSize(), actual.getTextSize(), 0.01f);
        assertEquals(expected.getCurrentTextColor(), actual.getCurrentTextColor());
        assertEquals(expected.getTypeface().getStyle(), actual.getTypeface().getStyle());
    }

    private static void assertNoEllipsis(
            View root, Context context, int id, String value) {
        MaterialAutoCompleteTextView field = root.findViewById(id);
        field.setText(value, false);
        measure(root, context, 360, 1000);
        assertNotNull(field.getLayout());
        assertEquals(1, field.getLayout().getLineCount());
        assertEquals(0, field.getLayout().getEllipsisCount(0));
    }

    private static void assertCompleteSegment(android.widget.TextView button) {
        assertNotNull(button.getLayout());
        assertTrue(button.getLayout().getLineCount() <= 2);
        for (int line = 0; line < button.getLayout().getLineCount(); line++) {
            assertEquals(0, button.getLayout().getEllipsisCount(line));
        }
    }

    private static void assertSameSegmentStyle(
            android.widget.TextView expected, android.widget.TextView actual) {
        assertEquals(expected.getMaxLines(), actual.getMaxLines());
        assertEquals(expected.getMinWidth(), actual.getMinWidth());
        assertEquals(expected.getMinHeight(), actual.getMinHeight());
        assertEquals(expected.getPaddingStart(), actual.getPaddingStart());
        assertEquals(expected.getPaddingEnd(), actual.getPaddingEnd());
        assertEquals(expected.getTextSize(), actual.getTextSize(), 0.01f);
    }

    private static boolean isDescendant(View ancestor, View descendant) {
        android.view.ViewParent parent = descendant.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
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

    private static int resolvedColor(Context context, int attribute) {
        TypedValue value = resolvedAttribute(context, attribute);
        return value.resourceId == 0 ? value.data : context.getColor(value.resourceId);
    }

    private static TypedValue resolvedAttribute(Context context, int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(attribute, value, true));
        return value;
    }

    private static ContextThemeWrapper nightContext() {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;
        Context configured = application.createConfigurationContext(configuration);
        return new ContextThemeWrapper(configured, R.style.AppTheme);
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
