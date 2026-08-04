package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.BuildConfig;
import com.frostwork.bgenhance.DiagnosticsTargetIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleRuntimeStatus;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsHookState;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class SettingsUiAccessibilityTest {
    @Before
    @After
    public void clearSettings() {
        ModuleSettings.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }

    @Test
    public void homepageSwitchesExposeVisibleTitleAndSummaryOnTheSwitchNode() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        assertCompleteSwitch(
                activity, R.id.row_master, R.id.switch_master,
                R.string.master_title, R.string.master_summary);
        assertCompleteSwitch(
                activity, R.id.row_periodic, R.id.switch_periodic,
                R.string.periodic_title, R.string.periodic_summary);
        assertCompleteSwitch(
                activity, R.id.row_oguard_kill, R.id.switch_oguard_kill,
                R.string.oguard_kill_title, R.string.oguard_kill_summary);
        assertCompleteSwitch(
                activity, R.id.row_oguard_stop, R.id.switch_oguard_stop,
                R.string.oguard_stop_title, R.string.oguard_stop_summary);
        assertCompleteSwitch(
                activity, R.id.row_dry_run, R.id.switch_dry_run,
                R.string.dry_run_title, R.string.dry_run_summary);
        activity.finish();
    }

    @Test
    public void featureSwitchReadsTitleSummaryAndImpactWithoutDuplicatingTextNodes() {
        ContextThemeWrapper context = themedContext(1f);
        FeatureCatalog.FeatureSpec spec =
                FeatureCatalog.forGroup(FeatureCatalog.GROUP_FAST_CLEAR).get(0);
        SettingSwitchRow row = new SettingSwitchRow(context);
        row.bind(spec, true);

        MaterialSwitch switchView = findFirst(row, MaterialSwitch.class);
        assertNotNull(switchView);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                row.getImportantForAccessibility());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                row.getChildAt(0).getImportantForAccessibility());
        String description = switchView.getContentDescription().toString();
        assertTrue(description.contains(context.getString(spec.titleRes)));
        assertTrue(description.contains(context.getString(spec.summaryRes)));
        assertTrue(description.contains(context.getString(spec.detailRes)));
        assertFalse(description.contains("已开启"));
        assertFalse(description.contains("已关闭"));
    }

    @Test
    public void riskNoticeSummaryUsesTheSharedSummaryTextAppearance() {
        ContextThemeWrapper context = themedContext(1f);
        RiskNoticeView notice = new RiskNoticeView(context);
        TextView summary = (TextView) notice.getChildAt(1);

        assertEquals(context.getColor(R.color.item_summary), summary.getCurrentTextColor());
        assertEquals(context.getResources().getDimension(R.dimen.setting_summary_size),
                summary.getTextSize(), 0.5f);
    }

    @Test
    public void enlargedHomepageStatsKeepThreeMetricsEqualAndPreserveBothMessages() {
        assertExpandedStats(1.5f);
        assertExpandedStats(2f);
    }

    @Test
    public void asynchronousHomepageStatusAndStatsArePoliteLiveRegions() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        View statusTextGroup = (View) activity.findViewById(
                R.id.text_module_status).getParent();
        View statusGroup = (View) statusTextGroup.getParent();
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                statusGroup.getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.text_effect_notice).getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                activity.findViewById(R.id.home_stats_overview).getAccessibilityLiveRegion());

        LinearLayout dryRun = activity.findViewById(R.id.row_dry_run);
        assertTrue(dryRun.getPaddingTop() > 0);
        assertTrue(dryRun.getPaddingBottom() > 0);
        activity.finish();
    }

    @Test
    public void settingsRendersTruncatedHistoryWithoutReplacingTheDiagnosticsAction() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();
        RecentBehaviorStats stats = RecentBehaviorStats.fromAggregateRows(
                Collections.emptyList(), true);
        HomeDiagnosticsViewModel.State state = HomeDiagnosticsViewModel.State.content(
                stats, new DiagnosticsQuery.HookStatePage(Collections.emptyList(), ""));
        activity.renderHomeDiagnostics(state);

        StatsOverviewView overview = activity.findViewById(R.id.home_stats_overview);
        assertEquals(View.VISIBLE, findText(
                overview, activity.getString(R.string.diagnostics_history_truncated))
                .getVisibility());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, findText(
                overview, activity.getString(R.string.diagnostics_history_truncated))
                .getAccessibilityLiveRegion());
        assertEquals(View.VISIBLE, findText(
                overview, activity.getString(R.string.home_stats_action)).getVisibility());
        activity.finish();
    }

    @Test
    public void ordinaryUiUsesUserFacingBackgroundManagementNames() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("系统后台管理服务（Athena）",
                context.getString(R.string.diagnostics_runtime_athena_process));
        assertEquals("系统后台管理服务（Athena）设置",
                context.getString(R.string.diagnostics_runtime_athena_config));
        assertEquals("系统后台管理服务（Athena）",
                context.getString(R.string.diagnostics_process_athena));
        assertEquals("其他后台管理清理",
                context.getString(R.string.athena_other_title));
        assertTrue(context.getString(R.string.status_inactive_summary).contains("Athena"));
        assertTrue(context.getString(R.string.status_inactive_summary)
                .contains("系统后台管理服务"));
    }

    @Test
    public void protectionPresetsPreserveRuntimeControlSwitches() {
        Context context = RuntimeEnvironment.getApplication();
        ModuleSettings.prefs(context).edit()
                .putBoolean(ModuleSettings.KEY_MASTER_ENABLE, false)
                .putBoolean(ModuleSettings.KEY_DRY_RUN, true)
                .putBoolean(ModuleSettings.KEY_VERBOSE_LOG, true)
                .commit();

        Map<String, Boolean> values = SettingsActivity.presetValues(context, true);

        assertFalse(values.get(ModuleSettings.KEY_MASTER_ENABLE));
        assertTrue(values.get(ModuleSettings.KEY_DRY_RUN));
        assertTrue(values.get(ModuleSettings.KEY_VERBOSE_LOG));
        assertTrue(values.get(ModuleSettings.KEY_FAST_CAMERA_START));
    }

    @Test
    public void unavailablePrimaryHookCanBeTurnedOffButCannotBeReenabled() {
        Context context = RuntimeEnvironment.getApplication();
        ModuleSettings.prefs(context).edit()
                .putBoolean(ModuleSettings.KEY_MASTER_ENABLE, true)
                .putBoolean(ModuleSettings.KEY_PERIODIC, false)
                .putBoolean(ModuleSettings.KEY_OGUARD_KILL, true)
                .putBoolean(ModuleSettings.KEY_OGUARD_FORCE_STOP, false)
                .commit();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();
        String bootId = "current-boot";
        int revision = ModuleSettings.getConfigRevision(activity);
        HomeDiagnosticsStatus status = HomeDiagnosticsStatus.evaluate(
                Arrays.asList(
                        process(BridgeContract.PROCESS_SYSTEM_SERVER, bootId),
                        process(BridgeContract.PROCESS_ATHENA, bootId),
                        config(BridgeContract.PROCESS_SYSTEM_SERVER, bootId, revision),
                        config(BridgeContract.PROCESS_ATHENA, bootId, revision),
                        hook(HookTargetIds.ATHENA_PERIODIC, "FAILED_FAIL_OPEN", bootId),
                        hook(HookTargetIds.OGUARD_KILL, "UNSUPPORTED", bootId),
                        hook(HookTargetIds.OGUARD_FORCE_STOP, "INSTALLED", bootId)),
                bootId,
                revision,
                BuildConfig.VERSION_CODE);

        activity.applyHomeDiagnosticsStatus(status);

        MaterialSwitch periodic = activity.findViewById(R.id.switch_periodic);
        MaterialSwitch kill = activity.findViewById(R.id.switch_oguard_kill);
        MaterialSwitch forceStop = activity.findViewById(R.id.switch_oguard_stop);
        assertFalse(periodic.isEnabled());
        assertTrue(kill.isChecked());
        assertTrue(kill.isEnabled());
        assertTrue(forceStop.isEnabled());

        activity.findViewById(R.id.row_oguard_kill).performClick();
        assertFalse(kill.isChecked());
        assertFalse(kill.isEnabled());
        activity.finish();
    }

    @Test
    public void activeModuleStatusDoesNotShowTheRedundantSummary() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        activity.renderActivation(HomeDiagnosticsStatus.Activation.ACTIVE);

        assertEquals(View.GONE,
                activity.findViewById(R.id.text_module_summary).getVisibility());
        activity.finish();
    }

    private static void assertCompleteSwitch(
            SettingsActivity activity,
            int rowId,
            int switchId,
            int titleRes,
            int summaryRes) {
        ViewGroup row = activity.findViewById(rowId);
        MaterialSwitch switchView = activity.findViewById(switchId);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                row.getImportantForAccessibility());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                row.getChildAt(0).getImportantForAccessibility());
        String description = switchView.getContentDescription().toString();
        assertTrue(description.contains(activity.getString(titleRes)));
        assertTrue(description.contains(activity.getString(summaryRes)));
    }

    private static DiagnosticsHookState process(String processId, String bootId) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_PROCESS)
                .installStatus(ModuleRuntimeStatus.featuresInstalled(BuildConfig.VERSION_CODE))
                .bootId(bootId)
                .build();
    }

    private static DiagnosticsHookState config(
            String processId, String bootId, int revision) {
        return DiagnosticsHookState.builder()
                .processId(processId)
                .hookTargetId(DiagnosticsTargetIds.RUNTIME_CONFIG)
                .installStatus("CONFIG_READY")
                .configRevision(revision)
                .bootId(bootId)
                .build();
    }

    private static DiagnosticsHookState hook(
            String hookTargetId, String status, String bootId) {
        return DiagnosticsHookState.builder()
                .processId(HookTargetIds.expectedProcessId(hookTargetId))
                .hookTargetId(hookTargetId)
                .installStatus(status)
                .bootId(bootId)
                .build();
    }

    private static void assertExpandedStats(float fontScale) {
        ContextThemeWrapper context = themedContext(fontScale);
        StatsOverviewView overview = new StatsOverviewView(context);
        overview.bind(12L, 8L, 1L,
                context.getString(R.string.home_stats_action), true, true);
        int width = dp(context, 324);
        overview.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        overview.layout(0, 0, width, overview.getMeasuredHeight());

        LinearLayout metrics = (LinearLayout) overview.getChildAt(0);
        LinearLayout first = (LinearLayout) metrics.getChildAt(0);
        LinearLayout second = (LinearLayout) metrics.getChildAt(2);
        LinearLayout third = (LinearLayout) metrics.getChildAt(4);
        assertTrue(first.getMeasuredHeight() > 0);
        assertEquals(first.getMeasuredHeight(), second.getMeasuredHeight());
        assertEquals(first.getMeasuredHeight(), third.getMeasuredHeight());
        for (LinearLayout metric : new LinearLayout[]{first, second, third}) {
            TextView label = (TextView) metric.getChildAt(1);
            assertEquals(2, label.getMinLines());
            assertEquals(2, label.getMaxLines());
        }

        assertEquals(View.VISIBLE, findText(
                overview, context.getString(R.string.diagnostics_history_truncated))
                .getVisibility());
        assertEquals(View.VISIBLE, findText(
                overview, context.getString(R.string.home_stats_action)).getVisibility());

        overview.bind(12L, 8L, 1L,
                context.getString(R.string.home_stats_action), true, false);
        assertEquals(View.GONE, findText(
                overview, context.getString(R.string.diagnostics_history_truncated))
                .getVisibility());
        assertEquals(View.VISIBLE, findText(
                overview, context.getString(R.string.home_stats_action)).getVisibility());
    }

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView
                && expected.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView match = findText(group.getChildAt(index), expected);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static <T extends View> T findFirst(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T match = findFirst(group.getChildAt(index), type);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static ContextThemeWrapper themedContext(float fontScale) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        configuration.screenWidthDp = 360;
        configuration.screenHeightDp = 640;
        return new ContextThemeWrapper(
                application.createConfigurationContext(configuration), R.style.AppTheme);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
