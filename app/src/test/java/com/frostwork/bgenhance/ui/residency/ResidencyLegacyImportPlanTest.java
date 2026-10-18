package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidator;
import com.frostwork.bgenhance.residency.legacy.LegacyHansImportResult;
import com.frostwork.bgenhance.residency.legacy.LegacyHansPolicyImporter;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class ResidencyLegacyImportPlanTest {
    private static final String NEW_PACKAGE = "com.example.alpha";
    private static final String UPDATE_PACKAGE = "com.example.bravo";
    private static final String UNCHANGED_PACKAGE = "com.example.charlie";
    private static final String SKIPPED_PACKAGE = "com.example.delta";
    private static final String EXISTING_PACKAGE = "com.example.existing";

    @Test
    public void previewClassifiesEveryStatusAndExposesSourceMetadataAndCounts()
            throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy updateImport = imported(legacy, UPDATE_PACKAGE);
        AppResidencyPolicy unchangedImport = imported(legacy, UNCHANGED_PACKAGE);
        ResidencyPolicyDocument current = document(
                withKillPolicy(updateImport, KillPolicy.all()),
                unchangedImport,
                ResidencyPresets.create(EXISTING_PACKAGE, ResidencyMode.LOW_POWER));

        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                current,
                legacy,
                setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE));

        assertEquals(5, plan.sourceVersion());
        assertFalse(plan.sourceEnabled());
        assertEquals(41L, plan.sourceRevision());
        assertEquals(1, plan.newCount());
        assertEquals(1, plan.updateCount());
        assertEquals(1, plan.unchangedCount());
        assertEquals(1, plan.skippedCount());
        assertEntry(plan.entries().get(0), NEW_PACKAGE,
                ResidencyLegacyImportPlan.Status.NEW, true, null);
        assertEntry(plan.entries().get(1), UPDATE_PACKAGE,
                ResidencyLegacyImportPlan.Status.UPDATE, true, current.rules().get(0));
        assertEntry(plan.entries().get(2), UNCHANGED_PACKAGE,
                ResidencyLegacyImportPlan.Status.UNCHANGED, false, unchangedImport);
        assertEntry(plan.entries().get(3), SKIPPED_PACKAGE,
                ResidencyLegacyImportPlan.Status.SKIPPED, false, null);
        assertEquals(Arrays.asList(NEW_PACKAGE, UPDATE_PACKAGE),
                new ArrayList<>(plan.defaultSelectedPackages()));
    }

    @Test
    public void entriesAndDefaultSelectionHaveDeterministicOrderAndAreImmutable()
            throws Exception {
        Set<String> importable = new HashSet<>(Arrays.asList(
                UNCHANGED_PACKAGE, UPDATE_PACKAGE, NEW_PACKAGE));
        LegacyHansImportResult legacy = legacyResult();
        ResidencyLegacyImportPlan plan = ResidencyLegacyImportPlan.create(
                document(imported(legacy, UNCHANGED_PACKAGE)), legacy, importable);
        importable.clear();

        assertEquals(Arrays.asList(
                        NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE, SKIPPED_PACKAGE),
                packageNames(plan.entries()));
        assertEquals(Arrays.asList(NEW_PACKAGE, UPDATE_PACKAGE),
                new ArrayList<>(plan.defaultSelectedPackages()));
        assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.defaultSelectedPackages().clear());
    }

    @Test
    public void applyMergesOnlySelectionAndPreservesCurrentDocumentAndOtherRules()
            throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy oldUpdate = ResidencyPresets.create(
                UPDATE_PACKAGE, ResidencyMode.ACTIVE);
        AppResidencyPolicy existing = ResidencyPresets.create(
                EXISTING_PACKAGE, ResidencyMode.LOW_POWER);
        ResidencyPolicyDocument current = document(oldUpdate, existing);
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                current, legacy, setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE));

        ResidencyPolicyDocument applied = plan.apply(
                Arrays.asList(NEW_PACKAGE, UPDATE_PACKAGE), false);

        assertEquals(current.schemaVersion(), applied.schemaVersion());
        assertEquals(current.policyRevision(), applied.policyRevision());
        assertEquals(current.enabled(), applied.enabled());
        assertEquals(current.defaultNewRuleMode(), applied.defaultNewRuleMode());
        assertEquals(imported(legacy, NEW_PACKAGE), find(applied, NEW_PACKAGE));
        assertEquals(imported(legacy, UPDATE_PACKAGE), find(applied, UPDATE_PACKAGE));
        assertSame(existing, find(applied, EXISTING_PACKAGE));
        assertEquals(3, applied.rules().size());
        assertEquals(Arrays.asList(NEW_PACKAGE, UPDATE_PACKAGE, EXISTING_PACKAGE),
                rulePackageNames(applied));
        assertEquals(oldUpdate, find(current, UPDATE_PACKAGE));
        assertEquals(2, current.rules().size());
    }

    @Test
    public void unselectedExistingRuleIsPreservedAndUnselectedNewRuleIsNotAdded()
            throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy oldUpdate = ResidencyPresets.create(
                UPDATE_PACKAGE, ResidencyMode.ACTIVE);
        ResidencyPolicyDocument current = document(oldUpdate);
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                current, legacy, setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE));

        ResidencyPolicyDocument applied = plan.apply(Collections.emptyList(), false);

        assertSame(oldUpdate, find(applied, UPDATE_PACKAGE));
        assertEquals(null, find(applied, NEW_PACKAGE));
        assertEquals(1, applied.rules().size());
        assertEquals(current.policyRevision(), applied.policyRevision());
    }

    @Test
    public void killProtectionChangesOnlySelectedImportedKillPolicy() throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy existing = ResidencyPresets.create(
                EXISTING_PACKAGE, ResidencyMode.LOW_POWER);
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                document(existing), legacy,
                setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE));
        AppResidencyPolicy imported = imported(legacy, NEW_PACKAGE);

        ResidencyPolicyDocument applied = plan.apply(
                Collections.singletonList(NEW_PACKAGE), true);
        AppResidencyPolicy protectedPolicy = find(applied, NEW_PACKAGE);

        assertEquals(KillPolicy.all(), protectedPolicy.killPolicy());
        assertEquals(imported.enabled(), protectedPolicy.enabled());
        assertEquals(imported.mode(), protectedPolicy.mode());
        assertSame(imported.freezePolicy(), protectedPolicy.freezePolicy());
        assertSame(imported.wakePolicy(), protectedPolicy.wakePolicy());
        assertSame(imported.resourcePolicy(), protectedPolicy.resourcePolicy());
        assertSame(imported.refreezePolicy(), protectedPolicy.refreezePolicy());
        assertSame(existing, find(applied, EXISTING_PACKAGE));
        assertEquals(existing.killPolicy(), find(applied, EXISTING_PACKAGE).killPolicy());
    }

    @Test
    public void unchangedEntryMayBeSelectedExplicitly() throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy unchanged = imported(legacy, UNCHANGED_PACKAGE);
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                document(unchanged), legacy,
                setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE));

        ResidencyPolicyDocument applied = plan.apply(
                Collections.singletonList(UNCHANGED_PACKAGE), true);

        assertEquals(KillPolicy.all(), find(applied, UNCHANGED_PACKAGE).killPolicy());
        assertEquals(17L, applied.policyRevision());
    }

    @Test
    public void killProtectionOptionIsIncludedInPreviewClassification() throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy unchanged = imported(legacy, UNCHANGED_PACKAGE);

        ResidencyLegacyImportPlan ordinary = new ResidencyLegacyImportPlan(
                document(unchanged),
                legacy,
                setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE),
                false);
        ResidencyLegacyImportPlan protectedPlan = new ResidencyLegacyImportPlan(
                document(unchanged),
                legacy,
                setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE),
                true);

        assertEquals(
                ResidencyLegacyImportPlan.Status.UNCHANGED,
                ordinary.entries().get(2).status());
        assertEquals(
                ResidencyLegacyImportPlan.Status.UPDATE,
                protectedPlan.entries().get(2).status());
        assertEquals(KillPolicy.all(), protectedPlan.entries().get(2)
                .importedPolicy().killPolicy());
        assertTrue(protectedPlan.defaultSelectedPackages().contains(UNCHANGED_PACKAGE));
    }

    @Test
    public void importingPrimaryUserKeepsAnExistingCloneRule() throws Exception {
        LegacyHansImportResult legacy = legacyResult();
        AppResidencyPolicy importedPrimary = imported(legacy, NEW_PACKAGE);
        AppResidencyPolicy clone = ResidencyPresets.create(
                NEW_PACKAGE, 999, ResidencyMode.ACTIVE);
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                document(clone), legacy, setOf(NEW_PACKAGE));

        assertEquals(1, plan.newCount());
        assertEquals(0, plan.updateCount());
        assertEquals(0, plan.unchangedCount());
        ResidencyPolicyDocument applied = plan.apply(
                Collections.singletonList(NEW_PACKAGE), false);

        assertEquals(2, applied.rules().size());
        assertEquals(clone, ruleForUser(applied, NEW_PACKAGE, 999));
        assertEquals(importedPrimary, ruleForUser(applied, NEW_PACKAGE, 0));
    }

    @Test
    public void applyRejectsSkippedUnknownDuplicateAndNullSelections() throws Exception {
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                document(), legacyResult(),
                setOf(NEW_PACKAGE, UPDATE_PACKAGE, UNCHANGED_PACKAGE));

        assertThrows(IllegalArgumentException.class,
                () -> plan.apply(Collections.singletonList(SKIPPED_PACKAGE), false));
        assertThrows(IllegalArgumentException.class,
                () -> plan.apply(Collections.singletonList("com.example.unknown"), false));
        assertThrows(IllegalArgumentException.class,
                () -> plan.apply(Arrays.asList(NEW_PACKAGE, NEW_PACKAGE), false));
        assertThrows(IllegalArgumentException.class,
                () -> plan.apply(Arrays.asList(NEW_PACKAGE, null), false));
    }

    @Test
    public void applyRejectsASelectionThatWouldExceedMaximumRules() throws Exception {
        List<AppResidencyPolicy> currentRules = new ArrayList<>();
        for (int index = 0; index < ResidencyPolicyValidator.MAX_RULES; index++) {
            currentRules.add(ResidencyPresets.create(
                    String.format("com.example.full.p%03d", index), ResidencyMode.BALANCED));
        }
        ResidencyPolicyDocument current = new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                91L,
                true,
                ResidencyMode.BALANCED,
                currentRules);
        ResidencyLegacyImportPlan plan = new ResidencyLegacyImportPlan(
                current, legacyResult(), setOf(NEW_PACKAGE));

        assertThrows(IllegalArgumentException.class,
                () -> plan.apply(Collections.singletonList(NEW_PACKAGE), false));
        ResidencyPolicyDocument unchanged = plan.apply(Collections.emptyList(), false);
        assertEquals(ResidencyPolicyValidator.MAX_RULES, unchanged.rules().size());
        assertEquals(91L, unchanged.policyRevision());
    }

    private static LegacyHansImportResult legacyResult() throws Exception {
        return LegacyHansPolicyImporter.importJson("{"
                + "\"version\":5,\"enabled\":false,\"revision\":41,\"rules\":["
                + rule(SKIPPED_PACKAGE, false, 44_000L)
                + "," + rule(UNCHANGED_PACKAGE, true, 33_000L)
                + "," + rule(UPDATE_PACKAGE, false, 22_000L)
                + "," + rule(NEW_PACKAGE, true, 11_000L)
                + "]}");
    }

    private static String rule(String packageName, boolean enabled, long delayMs) {
        return "{\"package\":\"" + packageName + "\",\"enabled\":" + enabled
                + ",\"customTiming\":true,\"rToMMs\":" + delayMs
                + ",\"mToFMs\":" + (delayMs + 1_000L) + "}";
    }

    private static ResidencyPolicyDocument document(AppResidencyPolicy... rules) {
        return new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                17L,
                false,
                ResidencyMode.LOW_POWER,
                Arrays.asList(rules));
    }

    private static AppResidencyPolicy imported(
            LegacyHansImportResult legacy, String packageName) {
        for (AppResidencyPolicy policy : legacy.policies()) {
            if (policy.packageName().equals(packageName)) return policy;
        }
        throw new AssertionError("Missing imported policy: " + packageName);
    }

    private static AppResidencyPolicy ruleForUser(
            ResidencyPolicyDocument document, String packageName, int userId) {
        for (AppResidencyPolicy policy : document.rules()) {
            if (policy.packageName().equals(packageName) && policy.userId() == userId) {
                return policy;
            }
        }
        return null;
    }

    private static AppResidencyPolicy find(
            ResidencyPolicyDocument document, String packageName) {
        for (AppResidencyPolicy policy : document.rules()) {
            if (policy.packageName().equals(packageName)) return policy;
        }
        return null;
    }

    private static AppResidencyPolicy withKillPolicy(
            AppResidencyPolicy policy, KillPolicy killPolicy) {
        return new AppResidencyPolicy(
                policy.packageName(),
                policy.enabled(),
                policy.mode(),
                killPolicy,
                policy.freezePolicy(),
                policy.wakePolicy(),
                policy.resourcePolicy(),
                policy.refreezePolicy());
    }

    private static Set<String> setOf(String... packageNames) {
        return new HashSet<>(Arrays.asList(packageNames));
    }

    private static void assertEntry(
            ResidencyLegacyImportPlan.Entry entry,
            String packageName,
            ResidencyLegacyImportPlan.Status status,
            boolean selectedByDefault,
            AppResidencyPolicy currentPolicy) {
        assertEquals(packageName, entry.packageName());
        assertEquals(status, entry.status());
        assertEquals(selectedByDefault, entry.selectedByDefault());
        assertSame(currentPolicy, entry.currentPolicy());
        assertEquals(packageName, entry.importedPolicy().packageName());
    }

    private static List<String> packageNames(
            List<ResidencyLegacyImportPlan.Entry> entries) {
        List<String> names = new ArrayList<>();
        for (ResidencyLegacyImportPlan.Entry entry : entries) names.add(entry.packageName());
        return names;
    }

    private static List<String> rulePackageNames(ResidencyPolicyDocument document) {
        List<String> names = new ArrayList<>();
        for (AppResidencyPolicy policy : document.rules()) names.add(policy.packageName());
        return names;
    }
}
