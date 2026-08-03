package com.frostwork.bgenhance.residency.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakeSource;
import org.junit.Test;

public final class LegacyHansPolicyImporterTest {
    @Test
    public void versionOneMergesPackageRulesUsingReferenceSemantics() throws Exception {
        LegacyHansImportResult result = LegacyHansPolicyImporter.importJson("{"
                + "\"version\":1,\"enabled\":true,\"revision\":-4,\"rules\":["
                + "{\"package\":\"com.example.residency.managed\","
                + "\"mode\":\"FOLLOW_SYSTEM\",\"enabled\":false,"
                + "\"rToMMs\":11000,\"mToFMs\":12000},"
                + "{\"package\":\"com.example.residency.managed\","
                + "\"mode\":\"EXEMPT\",\"enabled\":false,"
                + "\"rToMMs\":21000,\"mToFMs\":22000},"
                + "{\"package\":\"com.example.residency.managed\","
                + "\"mode\":\"CUSTOM\",\"enabled\":true,"
                + "\"rToMMs\":31000,\"mToFMs\":32000}]}" );

        assertEquals(1, result.sourceVersion());
        assertTrue(result.sourceEnabled());
        assertEquals(0L, result.sourceRevision());
        assertEquals(1, result.policies().size());
        AppResidencyPolicy policy = result.policies().get(0);
        assertTrue(policy.enabled());
        assertEquals(ResidencyMode.CUSTOM, policy.mode());
        assertTrue(policy.freezePolicy().fullExemption());
        assertTrue(policy.freezePolicy().customTiming());
        assertEquals(31_000L, policy.freezePolicy().rToMDelayMs());
        assertEquals(32_000L, policy.freezePolicy().mToFDelayMs());
        assertAllFreezeSources(policy, false);
        assertNoKillProtection(policy);
    }

    @Test
    public void versionTwoReadsNewerOptionalFieldsAndMapsAllPolicyFamilies() throws Exception {
        int freeze = LegacyHansWireConstants.FREEZE_FAST
                | LegacyHansWireConstants.FREEZE_SUPER;
        int resources = LegacyHansWireConstants.PROXY_AUDIO
                | LegacyHansWireConstants.PROXY_JOB;
        int wakes = LegacyHansWireConstants.WAKE_SERVICE
                | LegacyHansWireConstants.WAKE_PROVIDER;
        String json = "{\"version\":2,\"revision\":9,\"rules\":[{"
                + "\"package\":\"com.example.residency.managed\","
                + "\"customTiming\":true,\"rToMMs\":45000,\"mToFMs\":55000,"
                + "\"blockedFreezeSources\":" + freeze + ","
                + "\"bypassProxyFlags\":" + resources + ",\"keepNetwork\":true,"
                + "\"packetWakeMode\":1,\"packetWakeCooldownMs\":60000,"
                + "\"packetRefreezeMs\":30000,"
                + "\"alarmWakeMode\":2,\"alarmWakeCooldownMs\":900000,"
                + "\"alarmRefreezeMs\":45000,\"blockedWakeSources\":" + wakes
                + "}]}";

        AppResidencyPolicy policy = LegacyHansPolicyImporter.importJson(json).policies().get(0);

        assertTrue(policy.freezePolicy().allows(FreezeSource.NORMAL));
        assertFalse(policy.freezePolicy().allows(FreezeSource.FAST));
        assertFalse(policy.freezePolicy().allows(FreezeSource.SUPER));
        assertTrue(policy.freezePolicy().allows(FreezeSource.PRELOAD));
        assertEquals(WakeMode.THROTTLE, policy.wakePolicy().modeFor(WakeSource.PACKET));
        assertEquals(WakeMode.BLOCK, policy.wakePolicy().modeFor(WakeSource.ALARM));
        assertEquals(WakeMode.BLOCK, policy.wakePolicy().modeFor(WakeSource.SERVICE));
        assertEquals(WakeMode.BLOCK, policy.wakePolicy().modeFor(WakeSource.PROVIDER));
        assertTrue(policy.resourcePolicy().keepNetwork());
        assertTrue(policy.resourcePolicy().keeps(ResourceType.AUDIO));
        assertTrue(policy.resourcePolicy().keeps(ResourceType.JOB));
        assertEquals(RefreezeMode.AFTER_WAKE, policy.refreezePolicy().mode());
        assertEquals(30_000L, policy.refreezePolicy().packetDelayMs());
        assertEquals(45_000L, policy.refreezePolicy().alarmDelayMs());
        assertNoKillProtection(policy);
    }

    @Test
    public void versionsThreeAndFourUseTheVerifiedModernLegacyShape() throws Exception {
        for (int version = 3; version <= 4; version++) {
            LegacyHansImportResult result = LegacyHansPolicyImporter.importJson("{"
                    + "\"version\":" + version + ",\"enabled\":true,\"revision\":7,"
                    + "\"rules\":[{\"package\":\"com.example.residency.v" + version + "\","
                    + "\"blockedFreezeSources\":2,\"keepNetwork\":true,"
                    + "\"packetWakeMode\":1,\"packetWakeCooldownMs\":60000}]}");

            assertEquals(version, result.sourceVersion());
            assertTrue(result.sourceEnabled());
            assertEquals(7L, result.sourceRevision());
            AppResidencyPolicy policy = result.policies().get(0);
            assertFalse(policy.freezePolicy().allows(FreezeSource.FAST));
            assertTrue(policy.resourcePolicy().keepNetwork());
            assertEquals(WakeMode.THROTTLE,
                    policy.wakePolicy().modeFor(WakeSource.PACKET));
        }
    }

    @Test
    public void fullExemptionOverridesFreezeAndWakeRestrictions() throws Exception {
        String json = "{\"version\":5,\"rules\":[{"
                + "\"package\":\"com.example.residency.managed\",\"fullExempt\":true,"
                + "\"blockedFreezeSources\":15,\"packetWakeMode\":2,"
                + "\"alarmWakeMode\":2,\"blockedWakeSources\":16383}]}";

        AppResidencyPolicy policy = LegacyHansPolicyImporter.importJson(json).policies().get(0);

        assertTrue(policy.freezePolicy().fullExemption());
        assertAllFreezeSources(policy, false);
        for (WakeSource source : WakeSource.values()) {
            assertEquals(source.name(), WakeMode.ALLOW, policy.wakePolicy().modeFor(source));
        }
    }

    @Test
    public void duplicatePackagesUseLastRuleWithoutChangingOrder() throws Exception {
        String json = "{\"version\":5,\"rules\":["
                + "{\"package\":\"com.example.first\",\"enabled\":false},"
                + "{\"package\":\"com.example.second\"},"
                + "{\"package\":\"com.example.first\",\"enabled\":true}]}";

        LegacyHansImportResult result = LegacyHansPolicyImporter.importJson(json);

        assertEquals(2, result.policies().size());
        assertEquals("com.example.first", result.policies().get(0).packageName());
        assertTrue(result.policies().get(0).enabled());
        assertEquals("com.example.second", result.policies().get(1).packageName());
    }

    @Test
    public void invalidDocumentsFailWithoutPartialResults() {
        assertImportError("{", "invalid_document");
        assertImportError("{\"version\":6}", "unsupported_schema");
        assertImportError("{\"version\":5,\"rules\":[{\"package\":\"bad package\"}]}",
                "invalid_package");
        assertImportError("{\"version\":5,\"rules\":[{"
                        + "\"package\":\"com.example.valid\",\"blockedFreezeSources\":16}]}",
                "invalid_freeze_sources");
        assertImportError("{\"version\":5,\"rules\":[{"
                        + "\"package\":\"com.example.valid\",\"rToMMs\":999}]}",
                "invalid_r_to_m");
    }

    @Test
    public void documentAndRuleCountLimitsFailBeforeProducingPartialResults() {
        String oversized = "{\"version\":5,\"padding\":\""
                + "x".repeat(LegacyHansPolicyCodec.MAX_JSON_BYTES)
                + "\"}";
        assertImportError(oversized, "document_too_large");

        StringBuilder excessiveRules = new StringBuilder("{\"version\":5,\"rules\":[");
        for (int index = 0; index <= LegacyHansPolicyCodec.MAX_RULES; index++) {
            if (index > 0) excessiveRules.append(',');
            excessiveRules.append("{\"package\":\"com.example.residency.app")
                    .append(index)
                    .append("\"}");
        }
        excessiveRules.append("]}");
        assertImportError(excessiveRules.toString(), "too_many_rules");
    }

    private static void assertImportError(String json, String expectedCode) {
        LegacyHansImportException error = assertThrows(
                LegacyHansImportException.class,
                () -> LegacyHansPolicyImporter.importJson(json));
        assertEquals(expectedCode, error.code());
    }

    private static void assertAllFreezeSources(AppResidencyPolicy policy, boolean expected) {
        for (FreezeSource source : FreezeSource.values()) {
            assertEquals(source.name(), expected, policy.freezePolicy().allows(source));
        }
    }

    private static void assertNoKillProtection(AppResidencyPolicy policy) {
        assertFalse(policy.killPolicy().protectOguardKill());
        assertFalse(policy.killPolicy().protectOguardForceStop());
        assertFalse(policy.killPolicy().protectAthenaPeriodic());
        assertFalse(policy.killPolicy().protectFastClear());
        assertFalse(policy.killPolicy().protectTournament());
        assertFalse(policy.killPolicy().protectMemoryGuard());
        assertFalse(policy.killPolicy().protectNirvana());
        assertFalse(policy.killPolicy().protectOsensePerProcess());
    }
}
