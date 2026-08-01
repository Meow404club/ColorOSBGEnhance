package com.frostwork.bgenhance.residency.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class RuntimeConfigSnapshotTest {
    private static final String PACKAGE = "com.example.runtime.managed";

    @Test
    public void copiesAndLocksPolicyMap() {
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE, ResidencyMode.BALANCED);
        Map<String, AppResidencyPolicy> mutable = new LinkedHashMap<>();
        mutable.put(PACKAGE, policy);

        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.available(
                12L, true, true, false, 34L, mutable);
        mutable.clear();

        assertEquals(1, snapshot.policiesByPackage().size());
        assertSame(policy, snapshot.policyForPackage(PACKAGE));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.policiesByPackage().clear());
    }

    @Test
    public void rejectsPolicyWhoseMapKeyDoesNotMatchPackage() {
        AppResidencyPolicy policy = ResidencyPresets.create(PACKAGE, ResidencyMode.BALANCED);
        Map<String, AppResidencyPolicy> invalid = new LinkedHashMap<>();
        invalid.put("com.example.runtime.other", policy);

        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeConfigSnapshot.available(1L, true, true, false, 1L, invalid));
    }

    @Test
    public void rejectsDuplicateDocumentRulesInsteadOfChoosingOne() {
        AppResidencyPolicy first = ResidencyPresets.create(PACKAGE, ResidencyMode.BALANCED);
        AppResidencyPolicy second = ResidencyPresets.create(PACKAGE, ResidencyMode.LOW_POWER);
        List<AppResidencyPolicy> rules = new ArrayList<>();
        rules.add(first);
        rules.add(second);
        ResidencyPolicyDocument document = new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                5L,
                true,
                ResidencyMode.BALANCED,
                rules);

        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeConfigSnapshot.available(6L, true, false, document));
    }
}
