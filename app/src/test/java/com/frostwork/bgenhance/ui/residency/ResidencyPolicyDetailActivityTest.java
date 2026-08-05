package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.widget.RadioGroup;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyPolicyDetailActivityTest {
    private static final String PACKAGE_NAME = "com.example.detail";

    @Test
    public void eachRequestShowsOnlyItsTargetPolicyCard() {
        int[] cards = {
            R.id.residency_detail_kill,
            R.id.residency_detail_freeze,
            R.id.residency_detail_wake,
            R.id.residency_detail_resource,
            R.id.residency_detail_refreeze
        };
        ResidencyPolicyEditorContract.Section[] sections =
                ResidencyPolicyEditorContract.Section.values();

        for (int target = 0; target < sections.length; target++) {
            ActivityController<ResidencyPolicyDetailActivity> controller =
                    controller(sections[target]);
            ResidencyPolicyDetailActivity activity = controller.get();
            for (int index = 0; index < cards.length; index++) {
                assertEquals(index == target ? View.VISIBLE : View.GONE,
                        activity.findViewById(cards[index]).getVisibility());
            }
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void selectingAfterWakeFillsSafeDefaultsInsteadOfBlankFields() {
        ActivityController<ResidencyPolicyDetailActivity> controller =
                controller(ResidencyPolicyEditorContract.Section.REFREEZE);
        ResidencyPolicyDetailActivity activity = controller.get();

        ((RadioGroup) activity.findViewById(R.id.residency_refreeze_group))
                .check(R.id.residency_refreeze_after_wake);

        assertText(activity, R.id.residency_refreeze_default_value, "60");
        assertText(activity, R.id.residency_refreeze_packet_value, "60");
        assertText(activity, R.id.residency_refreeze_alarm_value, "120");
        assertText(activity, R.id.residency_refreeze_binder_value, "60");
        assertText(activity, R.id.residency_refreeze_service_value, "60");
        assertText(activity, R.id.residency_refreeze_broadcast_value, "60");
        assertText(activity, R.id.residency_refreeze_job_value, "60");
        controller.pause().stop().destroy();
    }

    @Test
    public void fullFreezeExemptionDisablesOverriddenControlsAndReturnsNormalizedPolicy() {
        ActivityController<ResidencyPolicyDetailActivity> controller =
                controller(ResidencyPolicyEditorContract.Section.FREEZE);
        ResidencyPolicyDetailActivity activity = controller.get();
        MaterialSwitch full = activity.findViewById(R.id.residency_freeze_full_exemption);

        full.setChecked(true);
        assertFalse(activity.findViewById(R.id.residency_freeze_normal).isEnabled());
        assertFalse(activity.findViewById(R.id.residency_freeze_custom_timing).isEnabled());
        activity.findViewById(R.id.residency_detail_done).performClick();

        ShadowActivity shadow = Shadow.extract(activity);
        assertEquals(Activity.RESULT_OK, shadow.getResultCode());
        ResidencyPolicyDraft result = ResidencyPolicyEditorContract.readResult(
                shadow.getResultIntent(),
                PACKAGE_NAME,
                ResidencyPolicyEditorContract.Section.FREEZE);
        FreezePolicy freeze = result.customFreezePolicy();
        assertTrue(freeze.fullExemption());
        assertFalse(freeze.allowNormalFreeze());
        assertFalse(freeze.customTiming());
        assertEquals(0L, freeze.rToMDelayMs());
        assertEquals(0L, freeze.mToFDelayMs());
        controller.destroy();
    }

    @Test
    public void hiddenCoreExemptionSurvivesFreezeSectionEditing() {
        Application application = RuntimeEnvironment.getApplication();
        ResidencyPolicyDraft draft = ResidencyPolicyDraft.from(
                ResidencyPresets.create(PACKAGE_NAME, ResidencyMode.CUSTOM))
                .withFreezePolicy(new FreezePolicy(
                        true, true, true, true, false, 0L, 0L,
                        false, true, false));
        Intent intent = ResidencyPolicyEditorContract.createIntent(
                application,
                PACKAGE_NAME,
                ResidencyPolicyEditorContract.Section.FREEZE,
                draft);
        ActivityController<ResidencyPolicyDetailActivity> controller =
                Robolectric.buildActivity(ResidencyPolicyDetailActivity.class, intent).setup();
        ResidencyPolicyDetailActivity activity = controller.get();

        activity.findViewById(R.id.residency_detail_done).performClick();

        ShadowActivity shadow = Shadow.extract(activity);
        ResidencyPolicyDraft result = ResidencyPolicyEditorContract.readResult(
                shadow.getResultIntent(),
                PACKAGE_NAME,
                ResidencyPolicyEditorContract.Section.FREEZE);
        assertTrue(result.customFreezePolicy().coreExemption());
        controller.destroy();
    }

    private static ActivityController<ResidencyPolicyDetailActivity> controller(
            ResidencyPolicyEditorContract.Section section) {
        Application application = RuntimeEnvironment.getApplication();
        ResidencyPolicyDraft draft = ResidencyPolicyDraft.from(
                ResidencyPresets.create(PACKAGE_NAME, ResidencyMode.CUSTOM));
        Intent intent = ResidencyPolicyEditorContract.createIntent(
                application, PACKAGE_NAME, section, draft);
        return Robolectric.buildActivity(ResidencyPolicyDetailActivity.class, intent).setup();
    }

    private static void assertText(Activity activity, int id, String expected) {
        TextInputEditText input = activity.findViewById(id);
        assertEquals(expected, input.getText().toString());
    }
}
