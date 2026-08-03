package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ResidencyAppFilterTest {
    @Test
    public void searchMatchesApplicationLabelAndPackageNameIgnoringCase() {
        List<ResidencyAppItem> source = Arrays.asList(
                item("Reader", "com.example.reader", false, true, null),
                item("Music", "com.example.audio", false, true, null));

        assertEquals("com.example.reader", filterPicker(source, "READ").get(0).packageName());
        assertEquals("com.example.audio", filterPicker(source, "AUDIO").get(0).packageName());
    }

    @Test
    public void managedListFiltersByExpandedModeAndEnabledState() {
        AppResidencyPolicy balanced = ResidencyPresets.create(
                "com.example.balanced", ResidencyMode.BALANCED);
        AppResidencyPolicy disabledActive = withEnabled(
                ResidencyPresets.create("com.example.active", ResidencyMode.ACTIVE), false);
        List<ResidencyAppItem> source = Arrays.asList(
                item("Balanced", balanced.packageName(), false, true, balanced),
                item("Active", disabledActive.packageName(), false, true, disabledActive));

        List<ResidencyAppItem> modes = ResidencyAppFilter.apply(
                source,
                ResidencyAppCatalog.Source.MANAGED,
                "",
                ResidencyAppFilter.ModeFilter.ACTIVE,
                ResidencyAppFilter.StatusFilter.ALL,
                false);
        List<ResidencyAppItem> disabled = ResidencyAppFilter.apply(
                source,
                ResidencyAppCatalog.Source.MANAGED,
                "",
                ResidencyAppFilter.ModeFilter.ALL,
                ResidencyAppFilter.StatusFilter.DISABLED,
                false);

        assertEquals(1, modes.size());
        assertEquals(disabledActive.packageName(), modes.get(0).packageName());
        assertEquals(1, disabled.size());
        assertEquals(disabledActive.packageName(), disabled.get(0).packageName());
    }

    @Test
    public void pickerDefaultsToLaunchableUserAppsAndRevealsSystemAppsExplicitly() {
        List<ResidencyAppItem> source = Arrays.asList(
                item("User", "com.example.user", false, true, null),
                item("Hidden user", "com.example.hidden", false, false, null),
                item("System", "com.example.system", true, true, null));

        List<ResidencyAppItem> normal = ResidencyAppFilter.apply(
                source,
                ResidencyAppCatalog.Source.PICKER,
                "",
                ResidencyAppFilter.ModeFilter.ALL,
                ResidencyAppFilter.StatusFilter.ALL,
                false);
        List<ResidencyAppItem> withSystem = ResidencyAppFilter.apply(
                source,
                ResidencyAppCatalog.Source.PICKER,
                "",
                ResidencyAppFilter.ModeFilter.ALL,
                ResidencyAppFilter.StatusFilter.ALL,
                true);

        assertEquals(1, normal.size());
        assertEquals("com.example.user", normal.get(0).packageName());
        assertEquals(2, withSystem.size());
    }

    @Test
    public void pickerKeepsRestrictedEntriesVisibleForAnExplicitSafetyExplanation() {
        ResidencyAppItem configurable = item(
                "User", "com.example.user", false, true, null);
        ResidencyAppItem restricted = new ResidencyAppItem(
                "Core",
                "com.example.core",
                true,
                true,
                true,
                ResidencyAppSafety.Result.restricted(
                        ResidencyAppSafety.Restriction.CORE_SYSTEM),
                null);
        List<ResidencyAppItem> source = Arrays.asList(configurable, restricted);

        List<ResidencyAppItem> filtered = ResidencyAppFilter.apply(
                source,
                ResidencyAppCatalog.Source.PICKER,
                "",
                ResidencyAppFilter.ModeFilter.ALL,
                ResidencyAppFilter.StatusFilter.ALL,
                true);

        assertEquals(2, filtered.size());
        assertEquals(configurable.packageName(), filtered.get(0).packageName());
        assertEquals(restricted.packageName(), filtered.get(1).packageName());
    }

    private static List<ResidencyAppItem> filterPicker(
            List<ResidencyAppItem> source, String query) {
        return ResidencyAppFilter.apply(
                source,
                ResidencyAppCatalog.Source.PICKER,
                query,
                ResidencyAppFilter.ModeFilter.ALL,
                ResidencyAppFilter.StatusFilter.ALL,
                false);
    }

    private static ResidencyAppItem item(
            String label,
            String packageName,
            boolean system,
            boolean launchable,
            AppResidencyPolicy policy) {
        return new ResidencyAppItem(
                label,
                packageName,
                true,
                system,
                launchable,
                ResidencyAppSafety.Result.allowed(),
                policy);
    }

    private static AppResidencyPolicy withEnabled(
            AppResidencyPolicy policy, boolean enabled) {
        return new AppResidencyPolicy(
                policy.packageName(),
                enabled,
                policy.mode(),
                policy.killPolicy(),
                policy.freezePolicy(),
                policy.wakePolicy(),
                policy.resourcePolicy(),
                policy.refreezePolicy());
    }
}
