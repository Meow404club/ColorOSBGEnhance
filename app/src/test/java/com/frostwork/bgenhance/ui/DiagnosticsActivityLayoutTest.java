package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.Dialog;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.ColorUtils;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.NormalizedDiagnosticsEvent;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Field;
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

        TabLayout tabs = activity.findViewById(R.id.diagnostics_tabs);
        assertEquals(3, tabs.getTabCount());
        assertEquals(activity.getString(R.string.diagnostics_tab_applications),
                tabs.getTabAt(0).getText());
        assertEquals(activity.getString(R.string.diagnostics_tab_behaviors),
                tabs.getTabAt(1).getText());
        assertEquals(activity.getString(R.string.diagnostics_tab_hooks),
                tabs.getTabAt(2).getText());

        RecyclerView list = activity.findViewById(R.id.diagnostics_list);
        assertNotNull(list.getAdapter());
        assertTrue(list.getAdapter() instanceof DiagnosticsRowsAdapter);
        assertNull(activity.findViewById(R.id.home_stats_overview));
        activity.finish();
    }

    @Test
    @Config(qualifiers = "land", sdk = 34, application = Application.class)
    public void landscapeKeepsActionsTabsListAndVerboseSwitchAvailable() {
        DiagnosticsActivity activity = Robolectric.buildActivity(DiagnosticsActivity.class)
                .setup()
                .get();

        assertEquals(Configuration.ORIENTATION_LANDSCAPE,
                activity.getResources().getConfiguration().orientation);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertNotNull(toolbar.getMenu().findItem(R.id.action_refresh_diagnostics));
        assertNotNull(toolbar.getMenu().findItem(R.id.action_export_diagnostics));
        assertNotNull(toolbar.getMenu().findItem(R.id.action_clear_diagnostics));
        assertNotNull(activity.<MaterialSwitch>findViewById(
                R.id.diagnostics_verbose_switch));
        assertNotNull(activity.<RecyclerView>findViewById(R.id.diagnostics_list));
        activity.finish();
    }

    @Test
    public void portraitAndLandscapeRetainListSpaceAtDoubleFontScale() {
        assertListHasSpace(configuredContext(false), 360, 640, 72);
        assertListHasSpace(configuredContext(true), 640, 320, 48);
    }

    @Test
    public void compactStatusBadgesMeetNormalTextContrast() {
        Context context = configuredContext(false);
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
        activity.finish();
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
        int width = dp(context, widthDp);
        int height = dp(context, heightDp);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertTrue(root.findViewById(R.id.diagnostics_list).getMeasuredHeight()
                >= dp(context, minimumListDp));
    }

    private static ContextThemeWrapper configuredContext(boolean landscape) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.orientation = landscape
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        configuration.screenWidthDp = landscape ? 640 : 360;
        configuration.screenHeightDp = landscape ? 320 : 640;
        configuration.fontScale = 2f;
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
