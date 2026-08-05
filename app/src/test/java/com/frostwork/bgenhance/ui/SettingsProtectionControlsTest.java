package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class SettingsProtectionControlsTest {
    @Before
    @After
    public void clearSettings() {
        ModuleSettings.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }

    @Test
    public void strongPresetConfirmsBeforeOneAtomicWriteAndDoesNotRepeat() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();
        int revisionBefore = ModuleSettings.getConfigRevision(activity);
        Map<String, Boolean> strongValues = SettingsActivity.presetValues(activity, true);

        activity.findViewById(R.id.button_preset_strong).performClick();

        AlertDialog firstConfirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(firstConfirmation);
        assertTrue(firstConfirmation.isShowing());
        assertEquals(ModuleSettings.PRESET_BALANCED, ModuleSettings.getLocalString(
                activity, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED));
        assertEquals(revisionBefore, ModuleSettings.getConfigRevision(activity));
        for (FeatureCatalog.FeatureSpec spec : FeatureCatalog.all()) {
            if (Boolean.TRUE.equals(strongValues.get(spec.prefKey))) {
                assertEquals(ModuleSettings.defaultFor(spec.prefKey),
                        ModuleSettings.getLocalBoolean(activity, spec.prefKey));
            }
        }

        firstConfirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals(ModuleSettings.PRESET_BALANCED, ModuleSettings.getLocalString(
                activity, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED));
        assertEquals(revisionBefore, ModuleSettings.getConfigRevision(activity));
        assertEquals(R.id.button_preset_balanced,
                ((MaterialButtonToggleGroup) activity.findViewById(R.id.preset_group))
                        .getCheckedButtonId());

        activity.findViewById(R.id.button_preset_strong).performClick();
        AlertDialog secondConfirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertFalse(firstConfirmation == secondConfirmation);
        secondConfirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals(ModuleSettings.PRESET_STRONG, ModuleSettings.getLocalString(
                activity, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED));
        assertEquals(revisionBefore + 1, ModuleSettings.getConfigRevision(activity));
        int confirmed = 0;
        for (FeatureCatalog.FeatureSpec spec : FeatureCatalog.all()) {
            if (!Boolean.TRUE.equals(strongValues.get(spec.prefKey))) continue;
            assertTrue(ModuleSettings.getLocalBoolean(activity, spec.prefKey));
            if (FeatureCatalog.needsRiskConfirmation(spec)) {
                confirmed++;
                assertTrue(ModuleSettings.prefs(activity).getBoolean(
                        FeatureCatalog.riskConfirmationKey(spec), false));
            }
        }
        assertEquals(4, confirmed);

        activity.findViewById(R.id.button_preset_balanced).performClick();
        int shownDialogs = ShadowDialog.getShownDialogs().size();
        activity.findViewById(R.id.button_preset_strong).performClick();

        assertEquals(shownDialogs, ShadowDialog.getShownDialogs().size());
        assertEquals(ModuleSettings.PRESET_STRONG, ModuleSettings.getLocalString(
                activity, ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_BALANCED));
        activity.finish();
    }

    @Test
    public void strongPresetConfirmationCountsAllNewlyEnabledRiskItems() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();
        Map<String, Boolean> strongValues = SettingsActivity.presetValues(activity, true);
        FeatureCatalog.FeatureSpec alreadyConfirmed = null;
        int newlyEnabledRiskCount = 0;
        for (FeatureCatalog.FeatureSpec spec : FeatureCatalog.all()) {
            if (!Boolean.TRUE.equals(strongValues.get(spec.prefKey))
                    || !FeatureCatalog.needsRiskConfirmation(spec)
                    || ModuleSettings.getLocalBoolean(activity, spec.prefKey)) {
                continue;
            }
            newlyEnabledRiskCount++;
            if (alreadyConfirmed == null) alreadyConfirmed = spec;
        }
        assertNotNull(alreadyConfirmed);
        assertTrue(newlyEnabledRiskCount > 1);
        ModuleSettings.prefs(activity).edit()
                .putBoolean(FeatureCatalog.riskConfirmationKey(alreadyConfirmed), true)
                .commit();

        activity.findViewById(R.id.button_preset_strong).performClick();

        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = confirmation.findViewById(android.R.id.message);
        assertNotNull(message);
        assertEquals(activity.getString(
                R.string.preset_risk_dialog_message, newlyEnabledRiskCount),
                message.getText().toString());
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        activity.finish();
    }

    @Test
    public void masterOffLocksProtectionEditorsButKeepsResidencyConfigurationAvailable() {
        Context context = RuntimeEnvironment.getApplication();
        ModuleSettings.prefs(context).edit()
                .putBoolean(ModuleSettings.KEY_MASTER_ENABLE, false)
                .putBoolean(ModuleSettings.KEY_DRY_RUN, true)
                .putBoolean(ModuleSettings.KEY_PERIODIC, true)
                .putString(ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_CUSTOM)
                .commit();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.banner_master_disabled).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.banner_dry_run).getVisibility());
        MaterialButtonToggleGroup presets = activity.findViewById(R.id.preset_group);
        assertFalse(presets.isEnabled());
        for (int index = 0; index < presets.getChildCount(); index++) {
            assertFalse(presets.getChildAt(index).isEnabled());
        }
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.switch_periodic)).isEnabled());
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.switch_dry_run)).isEnabled());
        assertFalse(activity.findViewById(R.id.row_dry_run).isEnabled());
        for (int rowId : new int[] {
                R.id.row_fast_clear, R.id.row_memory, R.id.row_nirvana, R.id.row_freeze
        }) {
            assertFalse(activity.findViewById(rowId).isEnabled());
        }
        assertEquals(0.55f, activity.findViewById(R.id.custom_advanced_section).getAlpha(), 0f);
        assertTrue(activity.findViewById(R.id.row_residency).isEnabled());
        assertTrue(ModuleSettings.getLocalBoolean(activity, ModuleSettings.KEY_DRY_RUN));
        assertTrue(ModuleSettings.getLocalBoolean(activity, ModuleSettings.KEY_PERIODIC));

        activity.findViewById(R.id.row_master).performClick();

        assertEquals(View.GONE,
                activity.findViewById(R.id.banner_master_disabled).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.banner_dry_run).getVisibility());
        assertTrue(presets.isEnabled());
        for (int index = 0; index < presets.getChildCount(); index++) {
            assertTrue(presets.getChildAt(index).isEnabled());
        }
        assertTrue(((MaterialSwitch) activity.findViewById(R.id.switch_periodic)).isEnabled());
        assertTrue(((MaterialSwitch) activity.findViewById(R.id.switch_dry_run)).isEnabled());
        assertTrue(activity.findViewById(R.id.row_dry_run).isEnabled());
        assertEquals(1f, activity.findViewById(R.id.custom_advanced_section).getAlpha(), 0f);
        activity.finish();
    }

    @Test
    public void disabledDryRunRowCannotChangeTheStoredValue() {
        Context context = RuntimeEnvironment.getApplication();
        ModuleSettings.prefs(context).edit()
                .putBoolean(ModuleSettings.KEY_MASTER_ENABLE, false)
                .putBoolean(ModuleSettings.KEY_DRY_RUN, false)
                .commit();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        activity.findViewById(R.id.row_dry_run).performClick();
        assertFalse(ModuleSettings.getLocalBoolean(
                activity, ModuleSettings.KEY_DRY_RUN));
        activity.finish();
    }

    @Test
    public void featurePageLocksEnabledUnsupportedRowsWhenMasterIsOff() {
        Context context = RuntimeEnvironment.getApplication();
        ModuleSettings.prefs(context).edit()
                .putBoolean(ModuleSettings.KEY_MASTER_ENABLE, false)
                .putBoolean(ModuleSettings.KEY_DRY_RUN, true)
                .putBoolean(ModuleSettings.KEY_FAST_CAMERA_START, true)
                .putString(ModuleSettings.KEY_ACTIVE_PRESET, ModuleSettings.PRESET_CUSTOM)
                .commit();
        Intent intent = new Intent(context, FeatureSettingsActivity.class)
                .putExtra(FeatureSettingsActivity.EXTRA_GROUP, FeatureCatalog.GROUP_FAST_CLEAR);
        FeatureSettingsActivity activity = Robolectric.buildActivity(
                        FeatureSettingsActivity.class, intent)
                .setup()
                .get();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.banner_master_disabled).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.banner_dry_run).getVisibility());
        SettingSwitchRow firstRow = findFirst(
                activity.findViewById(R.id.card_container), SettingSwitchRow.class);
        assertNotNull(firstRow);
        MaterialSwitch firstSwitch = findFirst(firstRow, MaterialSwitch.class);
        assertNotNull(firstSwitch);
        assertTrue(firstSwitch.isChecked());
        assertFalse(firstSwitch.isEnabled());

        firstRow.showUnsupported(true);
        firstRow.setRowEnabled(false);
        assertFalse(firstSwitch.isEnabled());
        activity.finish();
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
}
