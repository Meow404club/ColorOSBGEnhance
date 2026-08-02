package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.SettingsActivity;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.Arrays;
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
public final class ResidencyUiNavigationTest {
    @Test
    public void manifestDeclaresCompleteResidencyWorkflowAndPackageVisibility()
            throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        PackageManager packageManager = application.getPackageManager();
        for (Class<?> activity : Arrays.asList(
                ResidencyAppsActivity.class,
                ResidencyAppPickerActivity.class,
                ResidencyPolicyActivity.class,
                ResidencyImportActivity.class)) {
            assertNotNull(packageManager.getActivityInfo(
                    new ComponentName(application, activity), 0));
        }

        PackageInfo packageInfo = packageManager.getPackageInfo(
                application.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertNotNull(packageInfo.requestedPermissions);
        assertTrue(Arrays.asList(packageInfo.requestedPermissions)
                .contains("android.permission.QUERY_ALL_PACKAGES"));
    }

    @Test
    public void homeResidencyRowOpensManagedApplications() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        activity.findViewById(R.id.row_residency).performClick();

        ShadowActivity shadowActivity = Shadow.extract(activity);
        Intent started = shadowActivity.getNextStartedActivity();
        assertNotNull(started);
        assertEquals(
                new ComponentName(activity, ResidencyAppsActivity.class),
                started.getComponent());
        activity.finish();
    }

    @Test
    public void residencyMenuOpensLegacyImportPreview() {
        ResidencyAppsActivity activity = Robolectric.buildActivity(ResidencyAppsActivity.class)
                .create()
                .get();

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(
                R.id.action_import_residency, 0));

        ShadowActivity shadowActivity = Shadow.extract(activity);
        Intent started = shadowActivity.getNextStartedActivity();
        assertNotNull(started);
        assertEquals(
                new ComponentName(activity, ResidencyImportActivity.class),
                started.getComponent());
        activity.finish();
    }
}
