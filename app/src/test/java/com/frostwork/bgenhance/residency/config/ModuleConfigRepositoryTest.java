package com.frostwork.bgenhance.residency.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.List;
import org.junit.Test;

public final class ModuleConfigRepositoryTest {
    @Test
    public void unchangedPolicyKeepsCurrentObjectAndRevision() {
        ResidencyPolicyDocument current = document(7L, true, ResidencyMode.BALANCED);
        ResidencyPolicyDocument candidate = document(999L, true, ResidencyMode.BALANCED);

        assertSame(current, ModuleConfigRepository.nextPolicy(current, candidate));
    }

    @Test
    public void changedPolicyUsesRepositoryOwnedNextRevision() {
        ResidencyPolicyDocument current = document(7L, true, ResidencyMode.BALANCED);
        ResidencyPolicyDocument candidate = document(999L, false, ResidencyMode.BALANCED);

        ResidencyPolicyDocument next = ModuleConfigRepository.nextPolicy(current, candidate);

        assertEquals(8L, next.policyRevision());
        assertFalse(next.enabled());
        assertEquals(candidate.rules(), next.rules());
    }

    @Test
    public void exhaustedRevisionsAreRejectedInsteadOfWrapping() {
        ResidencyPolicyDocument current = document(
                Long.MAX_VALUE, true, ResidencyMode.BALANCED);
        ResidencyPolicyDocument changed = document(0L, false, ResidencyMode.BALANCED);

        assertThrows(IllegalStateException.class,
                () -> ModuleConfigRepository.nextPolicy(current, changed));
        assertThrows(IllegalStateException.class,
                () -> ModuleConfigRepository.nextConfigRevision(Integer.MAX_VALUE));
    }

    private static ResidencyPolicyDocument document(
            long revision, boolean enabled, ResidencyMode defaultMode) {
        return new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                revision,
                enabled,
                defaultMode,
                List.of(ResidencyPresets.create(
                        "com.example.repository", ResidencyMode.BALANCED)));
    }
}
