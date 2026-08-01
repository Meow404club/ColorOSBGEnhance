package com.frostwork.bgenhance.residency.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public final class RuntimeConfigCodecTest {
    private final RuntimeConfigCodec codec = new RuntimeConfigCodec();

    @Test
    public void emptyDocumentHasStableCanonicalEncoding() {
        String expected = "{\"documentType\":\"bgenhance_runtime_config\","
                + "\"schemaVersion\":1,\"revision\":0,\"updatedAtEpochMs\":0,"
                + "\"global\":{\"masterEnabled\":true,\"dryRun\":false,"
                + "\"verboseDiagnostics\":false,\"activePreset\":\"balanced\","
                + "\"legacyFeatures\":{}},"
                + "\"residency\":{\"schemaVersion\":1,\"policyRevision\":0,"
                + "\"enabled\":true,\"defaultNewRuleMode\":\"BALANCED\",\"rules\":[]}}";

        assertEquals(expected, codec.encode(RuntimeConfigDocument.empty()));
        assertEquals(expected, codec.encode(codec.decode(expected)));
    }

    @Test
    public void completeEnvelopeRoundTripsWithIndependentRevisions() {
        RuntimeConfigDocument original = document(
                41L,
                1_800_000_000_000L,
                7L,
                Map.of("block_oguard_kill", true, "block_hans_background_freeze", false),
                List.of(ResidencyPresets.create(
                        "com.example.reader", ResidencyMode.LOW_POWER)));

        RuntimeConfigDocument decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded);
        assertEquals(41L, decoded.revision());
        assertEquals(7L, decoded.residency().policyRevision());
    }

    @Test
    public void residencyIsEmbeddedAsAnObjectInsteadOfAnEscapedString() throws Exception {
        String encoded = codec.encode(RuntimeConfigDocument.empty());
        Object residency = new JSONObject(encoded).get("residency");

        assertTrue(residency instanceof JSONObject);
        assertFalse(encoded.contains("\"residency\":\""));
    }

    @Test
    public void canonicalEncodingSortsLegacyFeaturesAndResidencyRules() {
        Map<String, Boolean> legacy = new LinkedHashMap<>();
        legacy.put("zeta_feature", true);
        legacy.put("alpha_feature", false);
        RuntimeConfigDocument unsorted = document(
                2L,
                3L,
                1L,
                legacy,
                List.of(
                        ResidencyPresets.create("com.example.zeta", ResidencyMode.BALANCED),
                        ResidencyPresets.create("com.example.alpha", ResidencyMode.ACTIVE)));

        String first = codec.encode(unsorted);
        String second = codec.encode(unsorted);
        RuntimeConfigDocument decoded = codec.decode(first);

        assertEquals(first, second);
        assertTrue(first.indexOf("alpha_feature") < first.indexOf("zeta_feature"));
        assertTrue(first.indexOf("com.example.alpha") < first.indexOf("com.example.zeta"));
        assertEquals(
                List.of("alpha_feature", "zeta_feature"),
                new ArrayList<>(decoded.global().legacyFeatures().keySet()));
    }

    @Test
    public void decoderIgnoresUnknownOptionalFieldsAtEachEnvelopeLevel() {
        RuntimeConfigDocument expected = document(
                5L,
                6L,
                2L,
                Map.of("known_feature", true),
                List.of(ResidencyPresets.create(
                        "com.example.mail", ResidencyMode.BALANCED)));
        String encoded = codec.encode(expected)
                .replace("\"global\":{", "\"futureRoot\":42,\"global\":{")
                .replace(
                        "\"masterEnabled\":true,",
                        "\"futureGlobal\":{\"enabled\":true},\"masterEnabled\":true,")
                .replace(
                        "\"policyRevision\":2,",
                        "\"futureResidency\":\"ignored\",\"policyRevision\":2,");

        assertEquals(expected, codec.decode(encoded));
    }

    @Test
    public void decoderRejectsMalformedTruncatedAndTrailingJson() {
        String encoded = codec.encode(RuntimeConfigDocument.empty());

        assertThrows(RuntimeConfigCodecException.class,
                () -> codec.decode(encoded.substring(0, encoded.length() - 1)));
        assertThrows(RuntimeConfigCodecException.class, () -> codec.decode("{not-json}"));
        assertThrows(RuntimeConfigCodecException.class, () -> codec.decode(encoded + " true"));
        assertThrows(RuntimeConfigCodecException.class,
                () -> codec.decode(encoded.replace("\"global\":{", "\"missing\":{")));
    }

    @Test
    public void decoderRejectsWrongCoreTypesAndStringResidency() {
        String encoded = codec.encode(RuntimeConfigDocument.empty());

        assertThrows(RuntimeConfigCodecException.class, () -> codec.decode(encoded.replace(
                "\"revision\":0", "\"revision\":1.5")));
        assertThrows(RuntimeConfigCodecException.class, () -> codec.decode(encoded.replace(
                "\"masterEnabled\":true", "\"masterEnabled\":\"true\"")));
        assertThrows(RuntimeConfigCodecException.class, () -> codec.decode(encoded.replace(
                "\"legacyFeatures\":{}", "\"legacyFeatures\":{\"feature\":1}")));
        assertThrows(RuntimeConfigCodecException.class, () -> codec.decode(encoded.replace(
                "\"residency\":{", "\"residency\":\"not-an-object\",\"unused\":{")));
    }

    @Test
    public void documentTypeSchemaAndNonNegativeNumbersAreStrict() {
        RuntimeConfigDocument empty = RuntimeConfigDocument.empty();
        assertThrows(RuntimeConfigValidationException.class, () -> codec.encode(
                new RuntimeConfigDocument(
                        "another_document", 1, 0L, 0L,
                        empty.global(), empty.residency())));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.encode(
                new RuntimeConfigDocument(
                        RuntimeConfigDocument.DOCUMENT_TYPE, 2, 0L, 0L,
                        empty.global(), empty.residency())));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.encode(
                new RuntimeConfigDocument(
                        RuntimeConfigDocument.DOCUMENT_TYPE, 1, -1L, 0L,
                        empty.global(), empty.residency())));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.encode(
                new RuntimeConfigDocument(
                        RuntimeConfigDocument.DOCUMENT_TYPE, 1, 0L, -1L,
                        empty.global(), empty.residency())));

        String encoded = codec.encode(empty);
        assertThrows(RuntimeConfigValidationException.class, () -> codec.decode(encoded.replace(
                "bgenhance_runtime_config", "future_runtime_config")));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.decode(encoded.replaceFirst(
                "\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.decode(encoded.replace(
                "\"revision\":0", "\"revision\":-1")));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.decode(encoded.replace(
                "\"updatedAtEpochMs\":0", "\"updatedAtEpochMs\":-1")));
    }

    @Test
    public void rootJsonLimitUsesUtf8Bytes() {
        String base = codec.encode(RuntimeConfigDocument.empty());
        String prefix = base.substring(0, base.length() - 1) + ",\"padding\":\"";
        String suffix = "\"}";
        int paddingSize = RuntimeConfigValidator.MAX_JSON_BYTES
                - prefix.length() - suffix.length();
        String atLimit = prefix + "a".repeat(paddingSize) + suffix;

        assertEquals(RuntimeConfigDocument.empty(), codec.decode(atLimit));
        assertThrows(RuntimeConfigValidationException.class,
                () -> codec.decode(atLimit + " "));
        String multibyte = prefix + "\u4e2d".repeat(paddingSize / 2) + suffix;
        assertThrows(RuntimeConfigValidationException.class, () -> codec.decode(multibyte));
    }

    @Test
    public void configurationObjectsDefensivelyCopyAndExposeImmutableCollections() {
        Map<String, Boolean> mutableLegacy = new LinkedHashMap<>();
        mutableLegacy.put("alpha_feature", true);
        GlobalFeatureConfig global = new GlobalFeatureConfig(
                true, false, true, "custom", mutableLegacy);
        RuntimeConfigDocument document = new RuntimeConfigDocument(
                RuntimeConfigDocument.DOCUMENT_TYPE,
                1,
                3L,
                4L,
                global,
                ResidencyPolicyDocument.empty());

        mutableLegacy.put("later_feature", false);

        assertEquals(Map.of("alpha_feature", true), document.global().legacyFeatures());
        assertThrows(UnsupportedOperationException.class,
                () -> document.global().legacyFeatures().put("other_feature", true));
        assertThrows(UnsupportedOperationException.class,
                () -> document.residency().rules().add(
                        ResidencyPresets.create("com.example.other", ResidencyMode.BALANCED)));
    }

    @Test
    public void emptyActivePresetAndLegacyFeatureKeyAreRejected() {
        RuntimeConfigDocument empty = RuntimeConfigDocument.empty();
        assertThrows(RuntimeConfigValidationException.class, () -> codec.encode(
                new RuntimeConfigDocument(
                        RuntimeConfigDocument.DOCUMENT_TYPE,
                        1,
                        0L,
                        0L,
                        new GlobalFeatureConfig(true, false, false, "", Map.of()),
                        empty.residency())));
        assertThrows(RuntimeConfigValidationException.class, () -> codec.encode(
                new RuntimeConfigDocument(
                        RuntimeConfigDocument.DOCUMENT_TYPE,
                        1,
                        0L,
                        0L,
                        new GlobalFeatureConfig(true, false, false, "balanced", Map.of("", true)),
                        empty.residency())));
    }

    private static RuntimeConfigDocument document(
            long revision,
            long updatedAtEpochMs,
            long policyRevision,
            Map<String, Boolean> legacyFeatures,
            List<AppResidencyPolicy> rules) {
        return new RuntimeConfigDocument(
                RuntimeConfigDocument.DOCUMENT_TYPE,
                RuntimeConfigDocument.CURRENT_SCHEMA_VERSION,
                revision,
                updatedAtEpochMs,
                new GlobalFeatureConfig(true, false, true, "custom", legacyFeatures),
                new ResidencyPolicyDocument(
                        ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                        policyRevision,
                        true,
                        ResidencyMode.BALANCED,
                        rules));
    }
}
