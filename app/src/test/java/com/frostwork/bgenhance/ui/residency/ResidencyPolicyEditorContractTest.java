package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.content.Intent;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyPolicyEditorContractTest {
    private static final String PACKAGE_NAME = "com.example.editor";

    @Test
    public void everySectionRoundTripsOneValidatedDraft() {
        ResidencyPolicyDraft draft = customDraft();
        Application application = RuntimeEnvironment.getApplication();

        for (ResidencyPolicyEditorContract.Section section
                : ResidencyPolicyEditorContract.Section.values()) {
            Intent requestIntent = ResidencyPolicyEditorContract.createIntent(
                    application, PACKAGE_NAME, section, draft);
            ResidencyPolicyEditorContract.Request request =
                    ResidencyPolicyEditorContract.readRequest(requestIntent);
            assertEquals(PACKAGE_NAME, request.packageName());
            assertEquals(section, request.section());
            assertSamePolicies(draft, request.draft());

            Intent result = ResidencyPolicyEditorContract.createResult(
                    PACKAGE_NAME, section, request.draft());
            assertSamePolicies(draft, ResidencyPolicyEditorContract.readResult(
                    result, PACKAGE_NAME, section));
        }
    }

    @Test
    public void resultRejectsChangedIdentitySectionAndPayload() {
        ResidencyPolicyDraft draft = customDraft();
        Intent valid = ResidencyPolicyEditorContract.createResult(
                PACKAGE_NAME, ResidencyPolicyEditorContract.Section.KILL, draft);

        assertThrows(IllegalArgumentException.class,
                () -> ResidencyPolicyEditorContract.readResult(
                        valid, "com.example.other", ResidencyPolicyEditorContract.Section.KILL));
        assertThrows(IllegalArgumentException.class,
                () -> ResidencyPolicyEditorContract.readResult(
                        valid, PACKAGE_NAME, ResidencyPolicyEditorContract.Section.FREEZE));

        Intent corrupt = new Intent(valid).putExtra("editor_policy", "not-json");
        assertThrows(RuntimeException.class,
                () -> ResidencyPolicyEditorContract.readResult(
                        corrupt, PACKAGE_NAME, ResidencyPolicyEditorContract.Section.KILL));
    }

    @Test
    public void savedStateCodecRejectsAChangedPackage() {
        String encoded = ResidencyPolicyEditorContract.encodeDraft(PACKAGE_NAME, customDraft());
        assertThrows(IllegalArgumentException.class,
                () -> ResidencyPolicyEditorContract.decodeDraft("com.example.other", encoded));
    }

    private static ResidencyPolicyDraft customDraft() {
        return ResidencyPolicyDraft.from(ResidencyPresets.create(PACKAGE_NAME, ResidencyMode.CUSTOM));
    }

    private static void assertSamePolicies(
            ResidencyPolicyDraft expected, ResidencyPolicyDraft actual) {
        assertEquals(expected.enabled(), actual.enabled());
        assertEquals(ResidencyMode.CUSTOM, actual.mode());
        assertEquals(expected.customKillPolicy(), actual.customKillPolicy());
        assertEquals(expected.customFreezePolicy(), actual.customFreezePolicy());
        assertEquals(expected.customWakePolicy(), actual.customWakePolicy());
        assertEquals(expected.customResourcePolicy(), actual.customResourcePolicy());
        assertEquals(expected.customRefreezePolicy(), actual.customRefreezePolicy());
    }
}
