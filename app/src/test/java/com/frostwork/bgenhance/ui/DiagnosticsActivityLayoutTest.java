package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.ColorUtils;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.NormalizedDiagnosticsEvent;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsViewModel;
import com.frostwork.bgenhance.ui.diagnostics.FullDiagnosticsExportViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.Test;
import org.json.JSONObject;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class DiagnosticsActivityLayoutTest {
    @Before
    public void resetDiagnosticsBeforeTest() throws Exception {
        resetModuleDiagnostics();
    }

    @After
    public void resetDiagnosticsAfterTest() throws Exception {
        resetModuleDiagnostics();
    }

    @Test
    public void screenUsesThreeMaterialViewsWithoutRepeatingHomepageOverview() {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .create()
                .start()
                .resume()
                .get();

        MaterialButtonToggleGroup tabs = activity.findViewById(R.id.diagnostics_tabs);
        assertEquals(3, tabs.getChildCount());
        assertTrue(tabs.isSingleSelection());
        assertTrue(tabs.isSelectionRequired());
        assertEquals(R.id.diagnostics_tab_applications, tabs.getCheckedButtonId());
        assertEquals(activity.getString(R.string.diagnostics_tab_applications),
                ((TextView) tabs.getChildAt(0)).getText());
        assertEquals(activity.getString(R.string.diagnostics_tab_behaviors),
                ((TextView) tabs.getChildAt(1)).getText());
        assertEquals(activity.getString(R.string.diagnostics_tab_hooks),
                ((TextView) tabs.getChildAt(2)).getText());

        RecyclerView list = activity.findViewById(R.id.diagnostics_list);
        assertNotNull(list.getAdapter());
        assertTrue(list.getAdapter() instanceof DiagnosticsRowsAdapter);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_result_count)
                        .getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_loading_state)
                        .getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_empty_state)
                        .getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_error_state)
                        .getAccessibilityLiveRegion());
        assertHistoryNoticeLayout(activity);
        assertNull(activity.findViewById(R.id.home_stats_overview));
        activity.finish();
    }

    @Test
    public void selectedDiagnosticSegmentSurvivesActivityRecreation() {
        ActivityController<DiagnosticsActivity> controller =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        MaterialButtonToggleGroup tabs =
                controller.get().findViewById(R.id.diagnostics_tabs);

        tabs.check(R.id.diagnostics_tab_hooks);
        assertEquals(R.id.diagnostics_tab_hooks, tabs.getCheckedButtonId());

        controller.recreate();
        MaterialButtonToggleGroup recreatedTabs =
                controller.get().findViewById(R.id.diagnostics_tabs);
        assertEquals(R.id.diagnostics_tab_hooks, recreatedTabs.getCheckedButtonId());
        controller.pause().stop().destroy();
    }

    @Test
    @Config(qualifiers = "land", sdk = 34, application = Application.class)
    public void landscapeKeepsActionsTabsAndExpandedListAvailable() {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();

        assertEquals(Configuration.ORIENTATION_LANDSCAPE,
                activity.getResources().getConfiguration().orientation);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertNotNull(toolbar.getMenu().findItem(R.id.action_refresh_diagnostics));
        assertNotNull(toolbar.getMenu().findItem(R.id.action_export_diagnostics));
        assertNotNull(toolbar.getMenu().findItem(R.id.action_save_full_diagnostics));
        assertNotNull(toolbar.getMenu().findItem(R.id.action_clear_diagnostics));
        assertNotNull(toolbar.getMenu().findItem(R.id.action_more_diagnostics));
        assertNotNull(activity.<RecyclerView>findViewById(R.id.diagnostics_list));
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.diagnostics_loading_state)
                        .getAccessibilityLiveRegion());
        LinearLayout emptyState = activity.findViewById(R.id.diagnostics_empty_state);
        assertEquals(LinearLayout.HORIZONTAL, emptyState.getOrientation());
        LinearLayout.LayoutParams summaryParams = (LinearLayout.LayoutParams)
                activity.findViewById(R.id.diagnostics_empty_summary).getLayoutParams();
        assertEquals(0, summaryParams.width);
        assertEquals(1f, summaryParams.weight, 0.01f);
        TextView emptySummary = activity.findViewById(R.id.diagnostics_empty_summary);
        assertEquals(2, emptySummary.getMaxLines());
        assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM,
                emptySummary.getAutoSizeTextType());
        assertHistoryNoticeLayout(activity);
        activity.finish();
    }

    @Test
    public void historyNoticeOnlyShowsForCompletedAggregateViewsWithTruncatedHistory()
            throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        TextView notice = activity.findViewById(R.id.diagnostics_history_notice);

        render(activity, state(
                DiagnosticsViewModel.View.APPLICATION,
                DiagnosticsViewModel.Status.CONTENT,
                true));
        assertEquals(View.VISIBLE, notice.getVisibility());

        render(activity, state(
                DiagnosticsViewModel.View.BEHAVIOR,
                DiagnosticsViewModel.Status.EMPTY,
                true));
        assertEquals(View.VISIBLE, notice.getVisibility());

        render(activity, state(
                DiagnosticsViewModel.View.HOOK_TARGET,
                DiagnosticsViewModel.Status.CONTENT,
                true));
        assertEquals(View.GONE, notice.getVisibility());

        render(activity, state(
                DiagnosticsViewModel.View.APPLICATION,
                DiagnosticsViewModel.Status.CONTENT,
                false));
        assertEquals(View.GONE, notice.getVisibility());

        render(activity, state(
                DiagnosticsViewModel.View.BEHAVIOR,
                DiagnosticsViewModel.Status.LOADING,
                true));
        assertEquals(View.GONE, notice.getVisibility());
        activity.finish();
    }

    @Test
    public void portraitAndLandscapeRetainListSpaceAtDoubleFontScale() {
        assertListHasSpace(configuredContext(false), 360, 640, 72);
        assertListHasSpace(configuredContext(true), 640, 320, 48);
    }

    @Test
    public void diagnosticsTabsReuseHomepageSegmentedControlInDayAndNightThemes() {
        assertMatchesHomepageSegments(configuredContext(false, false));
        assertMatchesHomepageSegments(configuredContext(false, true));
    }

    @Test
    public void doubleFontScaleKeepsDiagnosticSegmentsCompleteAndEqualHeight() {
        assertCompleteSegments(configuredContext(false), 360, 640);
        assertCompleteSegments(configuredContext(true), 640, 320);
    }

    @Test
    public void compactStatusBadgesMeetNormalTextContrast() {
        assertStatusBadgeContrast(configuredContext(false, false));
        assertStatusBadgeContrast(configuredContext(false, true));
    }

    private static void assertStatusBadgeContrast(Context context) {
        assertTrue(ColorUtils.calculateContrast(
                context.getColor(R.color.status_success_text),
                context.getColor(R.color.status_success_bg)) >= 4.5d);
        assertTrue(ColorUtils.calculateContrast(
                context.getColor(R.color.status_error_text),
                context.getColor(R.color.status_error_bg)) >= 4.5d);
        assertTrue(ColorUtils.calculateContrast(
                context.getColor(R.color.banner_warning_text),
                context.getColor(R.color.status_warning_bg)) >= 4.5d);
    }

    @Test
    public void toolbarExportCopiesOnlyRedactedBackendSnapshot() throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        ResidencyDiagnosticsBackend backend = ModuleApplication.diagnosticsBackend();
        DiagnosticsQuery.ClearResult cleared = backend.clearAsync(
                DiagnosticsQuery.ClearScope.USER_ACTIVITY).get(5, TimeUnit.SECONDS);
        ModuleApplication.publishDiagnosticsEpoch(cleared.epoch);
        String privatePackage = "com.private.diagnostics.target";
        assertTrue(backend.recordEvent(NormalizedDiagnosticsEvent.builder()
                .timestampMillis(System.currentTimeMillis())
                .bootId(ModuleApplication.diagnosticsBootId())
                .processId("system_server")
                .featureId(FeatureIds.HANS)
                .hookTargetId(HookTargetIds.HANS_FREEZE_DISPATCH)
                .behavior("FREEZE")
                .source("NORMAL")
                .packageName(privatePackage)
                .uid(10123)
                .decision("BLOCK_FREEZE")
                .result("blocked")
                .build()));
        backend.flushAsync().get(5, TimeUnit.SECONDS);

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_export_diagnostics, 0));

        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        String exported = waitForClipboard(clipboard);
        assertTrue(exported.contains("bgenhance-residency-diagnostics"));
        assertTrue(!exported.contains(privatePackage));
        assertTrue(!exported.contains("\"uid\":10123"));
        JSONObject report = new JSONObject(exported);
        assertEquals(2, report.getInt("formatVersion"));
        assertEquals("modern", report.getJSONObject("protocols")
                .getJSONObject("xposed").getString("mode"));
        assertEquals(102, report.getJSONObject("protocols")
                .getJSONObject("xposed").getInt("targetApi"));
        assertFalse(report.getJSONObject("privacy").getBoolean("deviceDetailsIncluded"));
        assertFalse(report.getJSONObject("system").has("model"));
        assertFalse(report.getJSONObject("currentBoot").has("id"));
        assertTrue(report.getJSONObject("exportStatus").has("collections"));
        activity.finish();
    }

    @Test
    public void fullExportRequiresConfirmationAndPayloadBeforeCreateDocument()
            throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        ShadowActivity shadow = Shadow.extract(activity);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);

        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_save_full_diagnostics, 0));
        assertNull(shadow.peekNextStartedActivityForResult());
        Dialog latest = ShadowDialog.getLatestDialog();
        assertTrue(latest instanceof AlertDialog);
        AlertDialog confirmation = (AlertDialog) latest;
        TextView message = confirmation.findViewById(android.R.id.message);
        assertNotNull(message);
        assertEquals(activity.getString(R.string.diagnostics_full_export_confirm_message),
                message.getText().toString());

        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowActivity.IntentForResult request = waitForCreateDocument(shadow);
        assertNotNull(request);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
        assertEquals("application/json", request.intent.getType());
        String displayName = request.intent.getStringExtra(Intent.EXTRA_TITLE);
        assertNotNull(displayName);
        assertTrue(displayName.startsWith("BgEnhance-diagnostics-"));
        assertTrue(displayName.endsWith(".json"));
        assertFalse(displayName.endsWith(".partial.json"));
        activity.finish();
    }

    @Test
    public void cancelingFullExportConfirmationDoesNotOpenSaf() {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        ShadowActivity shadow = Shadow.extract(activity);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);

        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_save_full_diagnostics, 0));
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();

        assertNull(shadow.peekNextStartedActivityForResult());
        activity.finish();
    }

    @Test
    public void unavailableSnapshotNeverLaunchesCreateDocument() throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        ResidencyDiagnosticsBackend backend = ModuleApplication.diagnosticsBackend();
        backend.closeAsync().get(5, TimeUnit.SECONDS);
        ShadowActivity shadow = Shadow.extract(activity);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);

        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_save_full_diagnostics, 0));
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        waitForToast(activity.getString(R.string.diagnostics_full_export_failed));

        assertNull(shadow.peekNextStartedActivityForResult());
        activity.finish();
    }

    @Test
    public void cancelingCreateDocumentDiscardsPreparedPayload() throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        ShadowActivity shadow = Shadow.extract(activity);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_save_full_diagnostics, 0));
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowActivity.IntentForResult request = waitForCreateDocument(shadow);

        shadow.receiveResult(request.intent, Activity.RESULT_CANCELED, null);
        FullDiagnosticsExportViewModel exportViewModel = fullExportViewModel(activity);
        waitForFullExportStatus(
                exportViewModel, FullDiagnosticsExportViewModel.Status.IDLE);

        assertEquals(FullDiagnosticsExportViewModel.Status.IDLE,
                exportViewModel.state().status);
        activity.finish();
    }

    @Test
    public void rotationRetainsPayloadWithoutLaunchingCreateDocumentAgain()
            throws Exception {
        ActivityController<DiagnosticsActivity> controller =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        DiagnosticsActivity activity = controller.get();
        ShadowActivity firstShadow = Shadow.extract(activity);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_save_full_diagnostics, 0));
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertNotNull(waitForCreateDocument(firstShadow));
        FullDiagnosticsExportViewModel retained = fullExportViewModel(activity);
        assertEquals(FullDiagnosticsExportViewModel.Status.AWAITING_URI,
                retained.state().status);

        controller.recreate();

        DiagnosticsActivity recreated = controller.get();
        assertSame(retained, fullExportViewModel(recreated));
        assertEquals(FullDiagnosticsExportViewModel.Status.AWAITING_URI,
                retained.state().status);
        ShadowActivity recreatedShadow = Shadow.extract(recreated);
        assertNull(recreatedShadow.peekNextStartedActivityForResult());
        controller.pause().stop().destroy();
    }

    @Test
    public void clearActionPublishesNewEpochBeforeReloading() throws Exception {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();
        ResidencyDiagnosticsBackend backend = ModuleApplication.diagnosticsBackend();
        long before = backend.currentEpochAsync().get(5, TimeUnit.SECONDS);
        ModuleApplication.publishDiagnosticsEpoch(before);

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_clear_diagnostics, 0));
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        View positive = dialog.findViewById(android.R.id.button1);
        assertNotNull(positive);
        positive.performClick();

        long published = waitForNewEpoch(before);
        assertEquals(backend.currentEpochAsync().get(5, TimeUnit.SECONDS).longValue(), published);
        activity.finish();
    }

    private static void assertListHasSpace(
            Context context, int widthDp, int heightDp, int minimumListDp) {
        View root = LayoutInflater.from(context)
                .inflate(R.layout.activity_diagnostics, null, false);
        root.findViewById(R.id.diagnostics_history_notice).setVisibility(View.VISIBLE);
        int width = dp(context, widthDp);
        int height = dp(context, heightDp);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertTrue(root.findViewById(R.id.diagnostics_list).getMeasuredHeight()
                >= dp(context, minimumListDp));
    }

    private static void assertHistoryNoticeLayout(Activity activity) {
        TextView notice = activity.findViewById(R.id.diagnostics_history_notice);
        assertNotNull(notice);
        assertEquals(activity.getString(R.string.diagnostics_history_truncated),
                notice.getText().toString());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                notice.getAccessibilityLiveRegion());
        assertEquals(activity.getColor(R.color.item_summary), notice.getCurrentTextColor());
        assertEquals(activity.getResources().getDimension(R.dimen.setting_summary_size),
                notice.getTextSize(), 0.5f);
        assertNull(notice.getBackground());
        assertEquals(Integer.MAX_VALUE, notice.getMaxLines());
    }

    private static DiagnosticsViewModel.State state(
            DiagnosticsViewModel.View view,
            DiagnosticsViewModel.Status status,
            boolean historyTruncated) throws Exception {
        Constructor<DiagnosticsViewModel.State> constructor =
                DiagnosticsViewModel.State.class.getDeclaredConstructor(
                        DiagnosticsViewModel.View.class,
                        DiagnosticsViewModel.Status.class,
                        List.class,
                        int.class,
                        boolean.class,
                        boolean.class,
                        String.class,
                        long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                view,
                status,
                Collections.emptyList(),
                0,
                false,
                historyTruncated,
                "",
                1L);
    }

    private static void render(
            DiagnosticsActivity activity,
            DiagnosticsViewModel.State state) throws Exception {
        Method render = DiagnosticsActivity.class.getDeclaredMethod(
                "render", DiagnosticsViewModel.State.class);
        render.setAccessible(true);
        render.invoke(activity, state);
    }

    private static void assertMatchesHomepageSegments(Context context) {
        View homepage = LayoutInflater.from(context)
                .inflate(R.layout.activity_settings, null, false);
        View diagnostics = LayoutInflater.from(context)
                .inflate(R.layout.activity_diagnostics, null, false);
        MaterialButtonToggleGroup homepageGroup = homepage.findViewById(R.id.preset_group);
        MaterialButtonToggleGroup diagnosticsGroup =
                diagnostics.findViewById(R.id.diagnostics_tabs);
        homepageGroup.check(R.id.button_preset_balanced);
        diagnosticsGroup.check(R.id.diagnostics_tab_applications);

        MaterialButton expected = homepage.findViewById(R.id.button_preset_balanced);
        MaterialButton actual = diagnostics.findViewById(
                R.id.diagnostics_tab_applications);
        assertEquals(expected.getMinWidth(), actual.getMinWidth());
        assertEquals(expected.getMaxLines(), actual.getMaxLines());
        assertEquals(2, actual.getMinLines());
        assertEquals(expected.getPaddingStart(), actual.getPaddingStart());
        assertEquals(expected.getPaddingEnd(), actual.getPaddingEnd());
        assertEquals(expected.getTextSize(), actual.getTextSize(), 0.01f);
        assertEquals(expected.getCurrentTextColor(), actual.getCurrentTextColor());
        assertSameColorStates(expected.getBackgroundTintList(), actual.getBackgroundTintList());
        assertSameColorStates(expected.getStrokeColor(), actual.getStrokeColor());
        android.graphics.RectF bounds = new android.graphics.RectF(0f, 0f, 100f, 100f);
        float expectedRadius = expected.getShapeAppearanceModel()
                .getTopLeftCornerSize().getCornerSize(bounds);
        float actualRadius = actual.getShapeAppearanceModel()
                .getTopLeftCornerSize().getCornerSize(bounds);
        assertTrue(actualRadius > 0f);
        assertEquals(expectedRadius, actualRadius, 0.01f);
    }

    private static void assertSameColorStates(
            android.content.res.ColorStateList expected,
            android.content.res.ColorStateList actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.getDefaultColor(), actual.getDefaultColor());
        int[] checked = {android.R.attr.state_enabled, android.R.attr.state_checked};
        assertEquals(
                expected.getColorForState(checked, expected.getDefaultColor()),
                actual.getColorForState(checked, actual.getDefaultColor()));
    }

    private static void assertCompleteSegments(
            Context context, int widthDp, int heightDp) {
        View root = LayoutInflater.from(context)
                .inflate(R.layout.activity_diagnostics, null, false);
        int width = dp(context, widthDp);
        int height = dp(context, heightDp);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);

        MaterialButtonToggleGroup group = root.findViewById(R.id.diagnostics_tabs);
        assertEquals(LinearLayout.HORIZONTAL, group.getOrientation());
        int segmentHeight = group.getChildAt(0).getMeasuredHeight();
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView button = (TextView) group.getChildAt(index);
            assertEquals(segmentHeight, button.getMeasuredHeight());
            assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM,
                    button.getAutoSizeTextType());
            assertTrue(button.getAutoSizeMinTextSize() > 0);
            assertTrue(button.getAutoSizeMaxTextSize()
                    > button.getAutoSizeMinTextSize());
            assertNotNull(button.getLayout());
            assertTrue(button.getLayout().getLineCount() <= 2);
            for (int line = 0; line < button.getLayout().getLineCount(); line++) {
                assertEquals(0, button.getLayout().getEllipsisCount(line));
            }
        }
    }

    private static ContextThemeWrapper configuredContext(boolean landscape) {
        return configuredContext(landscape, false);
    }

    private static ContextThemeWrapper configuredContext(
            boolean landscape, boolean night) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.orientation = landscape
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        configuration.screenWidthDp = landscape ? 640 : 360;
        configuration.screenHeightDp = landscape ? 320 : 640;
        configuration.fontScale = 2f;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context configured = application.createConfigurationContext(configuration);
        return new ContextThemeWrapper(configured, R.style.AppTheme);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String waitForClipboard(ClipboardManager clipboard) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ShadowLooper.idleMainLooper();
            if (clipboard != null && clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.toString().contains(
                        "bgenhance-residency-diagnostics")) {
                    return text.toString();
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("diagnostics export was not copied");
    }

    private static ShadowActivity.IntentForResult waitForCreateDocument(
            ShadowActivity shadow) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            ShadowLooper.idleMainLooper();
            if (shadow.peekNextStartedActivityForResult() != null) {
                return shadow.getNextStartedActivityForResult();
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("CreateDocument was not launched");
    }

    private static void waitForToast(String expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ShadowLooper.idleMainLooper();
            if (expected.equals(ShadowToast.getTextOfLatestToast())) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("expected toast was not shown: " + expected);
    }

    private static void waitForFullExportStatus(
            FullDiagnosticsExportViewModel viewModel,
            FullDiagnosticsExportViewModel.Status expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ShadowLooper.idleMainLooper();
            if (viewModel.state().status == expected) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("full export state did not become " + expected);
    }

    private static FullDiagnosticsExportViewModel fullExportViewModel(
            DiagnosticsActivity activity) throws Exception {
        Field field = DiagnosticsActivity.class.getDeclaredField("fullExportViewModel");
        field.setAccessible(true);
        return (FullDiagnosticsExportViewModel) field.get(activity);
    }

    private static long waitForNewEpoch(long before) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ShadowLooper.idleMainLooper();
            long current = ModuleApplication.diagnosticsEpoch();
            if (current > before) return current;
            Thread.sleep(10L);
        }
        throw new AssertionError("cleared diagnostics epoch was not published");
    }

    private static void resetModuleDiagnostics() throws Exception {
        Field backendField = ModuleApplication.class.getDeclaredField("sDiagnosticsBackend");
        backendField.setAccessible(true);
        ResidencyDiagnosticsBackend backend =
                (ResidencyDiagnosticsBackend) backendField.get(null);
        if (backend != null) {
            try {
                backend.closeAsync().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // A prior Robolectric sandbox may already have invalidated its SQLite shadow.
            }
        }
        backendField.set(null, null);

        Field bootField = ModuleApplication.class.getDeclaredField("sDiagnosticsBootId");
        bootField.setAccessible(true);
        bootField.set(null, "");

        Field epochField = ModuleApplication.class.getDeclaredField("DIAGNOSTICS_EPOCH");
        epochField.setAccessible(true);
        ((AtomicLong) epochField.get(null)).set(-1L);
    }
}
