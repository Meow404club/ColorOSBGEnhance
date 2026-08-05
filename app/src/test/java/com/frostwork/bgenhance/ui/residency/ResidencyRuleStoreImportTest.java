package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.config.ModuleConfigRepository;
import com.frostwork.bgenhance.residency.legacy.LegacyHansImportResult;
import com.frostwork.bgenhance.residency.legacy.LegacyHansPolicyImporter;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyRuleStoreImportTest {
    private static final String PACKAGE_NAME = "com.example.imported";
    private Application application;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = PACKAGE_NAME;
        packageInfo.applicationInfo.uid = 10_123;
        ((ShadowPackageManager) Shadow.extract(application.getPackageManager()))
                .installPackage(packageInfo);
    }

    @Test
    public void importPreservesCurrentRootAndVerifiesPublishedRule() throws Exception {
        ModuleConfigRepository.ensureInitialized(application);
        ResidencyPolicyDocument initial = new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                1L,
                false,
                ResidencyMode.LOW_POWER,
                Collections.emptyList());
        assertTrue(ModuleConfigRepository.writeResidencyPolicy(application, initial));
        ResidencyPolicyDocument before = ModuleConfigRepository.readResidencyPolicy(application);
        LegacyHansImportResult legacy = LegacyHansPolicyImporter.importJson(
                "{\"version\":5,\"enabled\":true,\"revision\":999,\"rules\":[{"
                        + "\"package\":\"" + PACKAGE_NAME + "\"}]}" );

        ResidencyRuleStore.ImportResult result = ResidencyRuleStore.importLegacy(
                application, legacy, Collections.singleton(PACKAGE_NAME), true);

        ResidencyPolicyDocument after = ModuleConfigRepository.readResidencyPolicy(application);
        assertEquals(1, result.selectedCount());
        assertEquals(1, result.changedCount());
        assertFalse(after.enabled());
        assertEquals(ResidencyMode.LOW_POWER, after.defaultNewRuleMode());
        assertEquals(before.policyRevision() + 1L, after.policyRevision());
        AppResidencyPolicy imported = ResidencyRuleMutation.find(after, PACKAGE_NAME);
        assertNotNull(imported);
        assertTrue(imported.killPolicy().protectOguardKill());
        assertTrue(imported.killPolicy().protectOguardForceStop());
    }
}
