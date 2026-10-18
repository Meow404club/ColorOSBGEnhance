package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class ResidencyRuleMutationTest {
    @Test
    public void upsertReplacesOneRuleWithoutChangingDocumentMetadata() {
        AppResidencyPolicy first = ResidencyPresets.create(
                "com.example.first", ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(
                "com.example.second", ResidencyMode.LOW_POWER);
        ResidencyPolicyDocument current = document(first, second);
        AppResidencyPolicy replacement = ResidencyPresets.create(
                first.packageName(), ResidencyMode.ACTIVE);

        ResidencyPolicyDocument candidate = ResidencyRuleMutation.upsert(current, replacement);

        assertEquals(current.schemaVersion(), candidate.schemaVersion());
        assertEquals(current.policyRevision(), candidate.policyRevision());
        assertEquals(current.enabled(), candidate.enabled());
        assertEquals(current.defaultNewRuleMode(), candidate.defaultNewRuleMode());
        assertEquals(2, candidate.rules().size());
        assertEquals(replacement, ResidencyRuleMutation.find(candidate, first.packageName()));
        assertEquals(second, ResidencyRuleMutation.find(candidate, second.packageName()));
    }

    @Test
    public void upsertAddsMissingRuleAndDeleteRemovesOnlyTarget() {
        AppResidencyPolicy first = ResidencyPresets.create(
                "com.example.first", ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(
                "com.example.second", ResidencyMode.CUSTOM);
        ResidencyPolicyDocument added = ResidencyRuleMutation.upsert(document(first), second);
        ResidencyPolicyDocument deleted = ResidencyRuleMutation.delete(added, first.packageName());

        assertEquals(2, added.rules().size());
        assertEquals(1, deleted.rules().size());
        assertNull(ResidencyRuleMutation.find(deleted, first.packageName()));
        assertEquals(second, ResidencyRuleMutation.find(deleted, second.packageName()));
    }

    @Test
    public void deleteRemovesOnlyTheRequestedUserOfTheSamePackage() {
        AppResidencyPolicy primary = ResidencyPresets.create(
                "com.gof.china", ResidencyMode.BALANCED);
        AppResidencyPolicy clone = ResidencyPresets.create(
                "com.gof.china", 999, ResidencyMode.LOW_POWER);
        ResidencyPolicyDocument current = document(primary, clone);

        ResidencyPolicyDocument deleted = ResidencyRuleMutation.delete(
                current, clone.instanceKey());

        assertEquals(1, deleted.rules().size());
        assertEquals(primary, ResidencyRuleMutation.find(deleted, primary.packageName()));
        assertNull(ResidencyRuleMutation.find(deleted, clone.instanceKey()));
    }

    @Test
    public void deletingUnknownPackageReturnsOriginalDocument() {
        ResidencyPolicyDocument current = document(ResidencyPresets.create(
                "com.example.first", ResidencyMode.BALANCED));

        assertSame(current, ResidencyRuleMutation.delete(current, "com.example.missing"));
    }

    private static ResidencyPolicyDocument document(AppResidencyPolicy... rules) {
        return new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                17L,
                false,
                ResidencyMode.LOW_POWER,
                rules.length == 0 ? Collections.emptyList() : Arrays.asList(rules));
    }
}
