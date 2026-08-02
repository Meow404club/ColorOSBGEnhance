package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyAppSafetyAndroidTest {
    @Test
    public void sharedUidCannotBeConfiguredAsOneIndependentApplication() {
        Application application = RuntimeEnvironment.getApplication();
        install(application, "com.example.shared.one", 10_777);
        install(application, "com.example.shared.two", 10_777);

        assertEquals(
                ResidencyAppSafety.Restriction.SHARED_UID,
                ResidencyAppSafety.inspect(application, "com.example.shared.one")
                        .restriction());
    }

    private static void install(Application application, String packageName, int uid) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        packageInfo.applicationInfo.uid = uid;
        ((ShadowPackageManager) Shadow.extract(application.getPackageManager()))
                .installPackage(packageInfo);
    }
}
