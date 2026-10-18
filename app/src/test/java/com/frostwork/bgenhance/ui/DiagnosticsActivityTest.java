package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Intent;
import com.frostwork.bgenhance.ui.residency.ResidencyPolicyActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class DiagnosticsActivityTest {
    private static final String PACKAGE = "com.example.reader";

    @Test
    public void policyIntentCarriesTheConfirmedUser() {
        Intent intent = DiagnosticsActivity.createPolicyIntent(
                RuntimeEnvironment.getApplication(), PACKAGE, 999);

        assertEquals(ResidencyPolicyActivity.class.getName(),
                intent.getComponent().getClassName());
        assertEquals(PACKAGE, intent.getStringExtra(
                ResidencyPolicyActivity.EXTRA_PACKAGE_NAME));
        assertEquals(999, intent.getIntExtra(ResidencyPolicyActivity.EXTRA_USER_ID, -1));
    }

    @Test
    public void policyIntentRejectsAnUnknownUser() {
        try {
            DiagnosticsActivity.createPolicyIntent(
                    RuntimeEnvironment.getApplication(), PACKAGE, -1);
            fail("unknown user must not open a policy");
        } catch (IllegalArgumentException expected) {
            assertEquals("userId must be known", expected.getMessage());
        }
    }
}
