package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.view.View;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyPolicyActivityTest {
    private static final String ALLOWED_PACKAGE = "com.example.residency.allowed";
    private static final String RESTRICTED_PACKAGE = "com.example.residency.restricted";

    @Before
    @After
    public void clearSettings() {
        ModuleSettings.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }

    @Test
    public void overallDisableLocksModeAndCustomEditorsButKeepsEnableRowActionable()
            throws Exception {
        install(ALLOWED_PACKAGE, 10_321);
        ActivityController<ResidencyPolicyActivity> controller = controller(ALLOWED_PACKAGE);
        ResidencyPolicyActivity activity = controller.get();
        awaitReady(activity);

        MaterialSwitch enabled = activity.findViewById(R.id.residency_policy_enabled);
        View enabledRow = activity.findViewById(R.id.residency_policy_enabled_row);
        assertTrue(enabled.isEnabled());
        assertEquals(enabled.isEnabled(), enabledRow.isEnabled());

        enabled.setChecked(false);

        assertTrue(enabled.isEnabled());
        assertEquals(enabled.isEnabled(), enabledRow.isEnabled());
        assertTrue(enabledRow.isClickable());
        assertModeLockedAndCannotChange(activity, ResidencyMode.BALANCED);
        assertFalse(activity.findViewById(R.id.residency_custom_section).isEnabled());

        assertTrue(enabledRow.performClick());
        assertTrue(enabled.isChecked());
        assertTrue(activity.findViewById(R.id.residency_mode_selector_layout).isEnabled());
        controller.pause().stop().destroy();
    }

    @Test
    public void savingDisablesEnableRowAndModeWithoutLeavingRippleOnlyActions()
            throws Exception {
        install(ALLOWED_PACKAGE, 10_322);
        ActivityController<ResidencyPolicyActivity> controller = controller(ALLOWED_PACKAGE);
        ResidencyPolicyActivity activity = controller.get();
        awaitReady(activity);

        activity.setBusy(true);

        MaterialSwitch enabled = activity.findViewById(R.id.residency_policy_enabled);
        View enabledRow = activity.findViewById(R.id.residency_policy_enabled_row);
        assertFalse(enabled.isEnabled());
        assertEquals(enabled.isEnabled(), enabledRow.isEnabled());
        assertFalse(enabledRow.isClickable());
        assertFalse(enabledRow.isFocusable());
        assertModeLockedAndCannotChange(activity, ResidencyMode.BALANCED);
        controller.pause().stop().destroy();
    }

    @Test
    public void restrictedApplicationCannotUseModeIconDropdownOrItemCallback()
            throws Exception {
        install(RESTRICTED_PACKAGE, 9_999);
        ActivityController<ResidencyPolicyActivity> controller = controller(RESTRICTED_PACKAGE);
        ResidencyPolicyActivity activity = controller.get();
        awaitReady(activity);

        MaterialSwitch enabled = activity.findViewById(R.id.residency_policy_enabled);
        View enabledRow = activity.findViewById(R.id.residency_policy_enabled_row);
        assertFalse(enabled.isEnabled());
        assertEquals(enabled.isEnabled(), enabledRow.isEnabled());
        assertFalse(enabledRow.isClickable());
        assertModeLockedAndCannotChange(activity, ResidencyMode.BALANCED);
        controller.pause().stop().destroy();
    }

    private static void assertModeLockedAndCannotChange(
            ResidencyPolicyActivity activity, ResidencyMode expected) {
        CenteredDropdownTextView selector =
                activity.findViewById(R.id.residency_mode_selector);
        TextInputLayout layout =
                activity.findViewById(R.id.residency_mode_selector_layout);
        String[] labels = activity.getResources().getStringArray(
                R.array.residency_policy_modes);

        assertFalse(layout.isEnabled());
        View endIcon = layout.findViewById(
                com.google.android.material.R.id.text_input_end_icon);
        assertFalse(endIcon.isEnabled());
        assertFalse(selector.isEnabled());
        selector.showDropDown();
        assertFalse(selector.isPopupShowing());
        endIcon.performClick();
        assertFalse(selector.isPopupShowing());

        // Model Material's ordering: selected text may be replaced before OnItemClick runs.
        selector.setText(labels[ResidencyMode.ACTIVE.ordinal()], false);
        selector.getOnItemClickListener().onItemClick(
                null, null, ResidencyMode.ACTIVE.ordinal(), ResidencyMode.ACTIVE.ordinal());

        assertEquals(labels[expected.ordinal()], selector.getText().toString());
        assertEquals(activity.getString(modeSummary(expected)),
                ((android.widget.TextView) activity.findViewById(
                        R.id.residency_mode_selected_summary)).getText().toString());
    }

    private static int modeSummary(ResidencyMode mode) {
        switch (mode) {
            case SYSTEM: return R.string.residency_mode_system_summary;
            case BALANCED: return R.string.residency_mode_balanced_summary;
            case LOW_POWER: return R.string.residency_mode_low_power_summary;
            case ACTIVE: return R.string.residency_mode_active_summary;
            case CUSTOM: return R.string.residency_mode_custom_summary;
            default: throw new AssertionError(mode);
        }
    }

    private static ActivityController<ResidencyPolicyActivity> controller(String packageName) {
        Intent intent = new Intent(
                RuntimeEnvironment.getApplication(), ResidencyPolicyActivity.class)
                .putExtra(ResidencyPolicyActivity.EXTRA_PACKAGE_NAME, packageName);
        return Robolectric.buildActivity(ResidencyPolicyActivity.class, intent).setup();
    }

    private static void awaitReady(ResidencyPolicyActivity activity) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            if (activity.findViewById(R.id.residency_policy_content).getVisibility()
                    == View.VISIBLE) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("residency policy did not finish loading");
    }

    private static void install(String packageName, int uid) {
        Application application = RuntimeEnvironment.getApplication();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        packageInfo.applicationInfo.uid = uid;
        ((ShadowPackageManager) Shadow.extract(application.getPackageManager()))
                .installPackage(packageInfo);
    }
}
