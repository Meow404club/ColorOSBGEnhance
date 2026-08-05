package com.frostwork.bgenhance.residency.codec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.RefreezeMode;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.ResidencyPresets;
import com.frostwork.bgenhance.residency.model.ResourcePolicy;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import com.frostwork.bgenhance.residency.model.WakePolicy;
import com.frostwork.bgenhance.residency.model.WakeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public final class ResidencyPolicyCodecTest {
    private final ResidencyPolicyCodec codec = new ResidencyPolicyCodec();

    @Test
    public void emptyDocumentHasStableCanonicalEncoding() {
        String expected = "{\"schemaVersion\":1,\"policyRevision\":0,\"enabled\":true,"
                + "\"defaultNewRuleMode\":\"BALANCED\",\"rules\":[]}";

        assertEquals(expected, codec.encode(ResidencyPolicyDocument.empty()));
        assertEquals(expected, codec.encode(codec.decode(expected)));
    }

    @Test
    public void completePolicyRoundTripsWithoutLosingFields() {
        ResidencyPolicyDocument original = document(19L, completePolicy("com.example.reader"));

        ResidencyPolicyDocument decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded);
    }

    @Test
    public void canonicalEncodingSortsRulesAndEnumSets() {
        ResidencyPolicyDocument unsorted = new ResidencyPolicyDocument(
                1,
                3L,
                true,
                ResidencyMode.BALANCED,
                List.of(
                        completePolicy("com.example.zeta"),
                        completePolicy("com.example.alpha")));

        String first = codec.encode(unsorted);
        String second = codec.encode(unsorted);
        ResidencyPolicyDocument decoded = codec.decode(first);

        assertEquals(first, second);
        assertTrue(first.indexOf("com.example.alpha") < first.indexOf("com.example.zeta"));
        assertTrue(first.contains(
                "\"blockedSources\":[\"ASYNC_BINDER\",\"OTHER\",\"SERVICE\"]"));
        assertTrue(first.contains(
                "\"keptResources\":[\"AUDIO\",\"GPS\",\"SERVICE\"]"));
        assertEquals("com.example.alpha", decoded.rules().get(0).packageName());
        assertEquals("com.example.zeta", decoded.rules().get(1).packageName());
    }

    @Test
    public void decoderIgnoresUnknownOptionalFieldsAtEveryLevel() {
        ResidencyPolicyDocument expected = document(2L, completePolicy("com.example.mail"));
        String encoded = codec.encode(expected)
                .replace("\"rules\":[", "\"futureRoot\":{\"enabled\":true},\"rules\":[")
                .replace(
                        "\"enabled\":true,\"mode\":\"CUSTOM\"",
                        "\"enabled\":true,\"futureRule\":42,\"mode\":\"CUSTOM\"")
                .replace(
                        "\"keepNetwork\":true,\"keptResources\"",
                        "\"keepNetwork\":true,\"futureResource\":\"ignored\","
                                + "\"keptResources\"");

        assertEquals(expected, codec.decode(encoded));
    }

    @Test
    public void decoderRejectsTruncatedMalformedAndTrailingJson() {
        String encoded = codec.encode(document(1L, completePolicy("com.example.maps")));

        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode(encoded.substring(0, encoded.length() - 1)));
        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode("{not-json}"));
        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode(encoded + " true"));
    }

    @Test
    public void decoderRejectsUnknownCoreEnums() {
        String encoded = codec.encode(document(1L, completePolicy("com.example.notes")));

        assertThrows(ResidencyPolicyCodecException.class, () -> codec.decode(encoded.replace(
                "\"defaultNewRuleMode\":\"BALANCED\"",
                "\"defaultNewRuleMode\":\"FUTURE\"")));
        assertThrows(ResidencyPolicyCodecException.class, () -> codec.decode(encoded.replace(
                "\"mode\":\"THROTTLE\",\"cooldownMs\":1000",
                "\"mode\":\"FUTURE\",\"cooldownMs\":1000")));
        assertThrows(ResidencyPolicyCodecException.class, () -> codec.decode(encoded.replace(
                "\"blockedSources\":[\"ASYNC_BINDER\",\"OTHER\",\"SERVICE\"]",
                "\"blockedSources\":[\"FUTURE\"]")));
    }

    @Test
    public void duplicatePackagesAreRejectedForEncodingAndDecoding() {
        AppResidencyPolicy rule = completePolicy("com.example.duplicate");
        ResidencyPolicyDocument duplicateDocument = new ResidencyPolicyDocument(
                1, 1L, true, ResidencyMode.BALANCED, List.of(rule, rule));
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.encode(duplicateDocument));

        String single = codec.encode(document(1L, rule));
        int rulesStart = single.indexOf("\"rules\":[") + "\"rules\":[".length();
        int rulesEnd = single.lastIndexOf(']');
        String ruleJson = single.substring(rulesStart, rulesEnd);
        String duplicateJson = single.substring(0, rulesEnd) + "," + ruleJson
                + single.substring(rulesEnd);
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.decode(duplicateJson));
    }

    @Test
    public void delayBoundariesAllowZeroOrOneSecondThroughTwentyFourHours() {
        long[] accepted = {0L, 1_000L, 86_400_000L};
        for (long delay : accepted) {
            ResidencyPolicyDocument document = document(
                    delay, policyWithAllDelays("com.example.timer", delay));
            assertEquals(document, codec.decode(codec.encode(document)));
        }

        long[] rejected = {-1L, 1L, 999L, 86_400_001L, Long.MAX_VALUE};
        for (long delay : rejected) {
            ResidencyPolicyDocument document = document(
                    1L, policyWithAllDelays("com.example.timer", delay));
            assertThrows("delay=" + delay, ResidencyPolicyValidationException.class,
                    () -> codec.encode(document));
        }
    }

    @Test
    public void enabledTimingAndThrottleRequireNonZeroDelays() {
        AppResidencyPolicy base = completePolicy("com.example.timer");
        AppResidencyPolicy zeroTiming = new AppResidencyPolicy(
                base.packageName(), true, base.mode(), base.killPolicy(),
                new FreezePolicy(
                        true, true, true, true, true, 0L, 1_000L,
                        false, false, false),
                base.wakePolicy(), base.resourcePolicy(), base.refreezePolicy());
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.encode(document(1L, zeroTiming)));

        AppResidencyPolicy zeroThrottle = new AppResidencyPolicy(
                base.packageName(), true, base.mode(), base.killPolicy(), base.freezePolicy(),
                new WakePolicy(
                        new WakeChannelPolicy(WakeMode.THROTTLE, 0L),
                        WakeChannelPolicy.allow(),
                        Collections.emptySet()),
                base.resourcePolicy(), base.refreezePolicy());
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.encode(document(1L, zeroThrottle)));
    }

    @Test
    public void revisionMustBeANonNegativeSignedInteger() {
        ResidencyPolicyDocument maximum = new ResidencyPolicyDocument(
                1, Long.MAX_VALUE, true, ResidencyMode.BALANCED, Collections.emptyList());
        assertEquals(maximum, codec.decode(codec.encode(maximum)));

        ResidencyPolicyDocument negative = new ResidencyPolicyDocument(
                1, -1L, true, ResidencyMode.BALANCED, Collections.emptyList());
        assertThrows(ResidencyPolicyValidationException.class, () -> codec.encode(negative));

        String empty = codec.encode(ResidencyPolicyDocument.empty());
        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode(empty.replace("\"policyRevision\":0", "\"policyRevision\":1.5")));
        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode(empty.replace(
                        "\"policyRevision\":0", "\"policyRevision\":9223372036854775808")));
    }

    @Test
    public void schemaTypesRequiredFieldsAndPackageNamesAreStrict() {
        ResidencyPolicyDocument unsupported = new ResidencyPolicyDocument(
                2, 0L, true, ResidencyMode.BALANCED, Collections.emptyList());
        assertThrows(ResidencyPolicyValidationException.class, () -> codec.encode(unsupported));
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.encode(document(1L, completePolicy("invalid"))));
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.encode(document(1L, completePolicy("com..invalid"))));

        String empty = codec.encode(ResidencyPolicyDocument.empty());
        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode(empty.replace("\"enabled\":true", "\"enabled\":\"true\"")));
        assertThrows(ResidencyPolicyCodecException.class,
                () -> codec.decode(empty.replace("\"rules\":[]", "\"future\":[]")));
    }

    @Test
    public void ruleLimitAcceptsFiveHundredTwelveAndRejectsMore() {
        List<AppResidencyPolicy> rules = new ArrayList<>();
        for (int index = 0; index < ResidencyPolicyValidator.MAX_RULES; index++) {
            rules.add(ResidencyPresets.create(
                    String.format(Locale.ROOT, "com.example.app%03d", index),
                    ResidencyMode.BALANCED));
        }
        ResidencyPolicyDocument atLimit = new ResidencyPolicyDocument(
                1, 1L, true, ResidencyMode.BALANCED, rules);
        assertEquals(ResidencyPolicyValidator.MAX_RULES,
                codec.decode(codec.encode(atLimit)).rules().size());

        rules.add(ResidencyPresets.create("com.example.overflow", ResidencyMode.BALANCED));
        ResidencyPolicyDocument overLimit = new ResidencyPolicyDocument(
                1, 2L, true, ResidencyMode.BALANCED, rules);
        assertThrows(ResidencyPolicyValidationException.class, () -> codec.encode(overLimit));
    }

    @Test
    public void jsonSizeLimitUsesUtf8BytesAndIncludesExactBoundary() {
        String prefix = "{\"schemaVersion\":1,\"policyRevision\":0,\"enabled\":true,"
                + "\"defaultNewRuleMode\":\"BALANCED\",\"rules\":[],\"padding\":\"";
        String suffix = "\"}";
        int paddingSize = ResidencyPolicyValidator.MAX_JSON_BYTES
                - prefix.length() - suffix.length();
        String atLimit = prefix + "a".repeat(paddingSize) + suffix;

        assertEquals(ResidencyPolicyDocument.empty(), codec.decode(atLimit));
        assertThrows(ResidencyPolicyValidationException.class,
                () -> codec.decode(atLimit + " "));

        String multibyte = prefix + "\u4e2d".repeat(paddingSize / 2) + suffix;
        assertThrows(ResidencyPolicyValidationException.class, () -> codec.decode(multibyte));
    }

    @Test
    public void decodedDocumentAndNestedSetsAreImmutable() {
        ResidencyPolicyDocument decoded = codec.decode(codec.encode(
                document(1L, completePolicy("com.example.immutable"))));

        assertThrows(UnsupportedOperationException.class,
                () -> decoded.rules().add(completePolicy("com.example.other")));
        assertThrows(UnsupportedOperationException.class,
                () -> decoded.rules().get(0).wakePolicy().blockedSources().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> decoded.rules().get(0).resourcePolicy().keptResources().clear());
    }

    private static ResidencyPolicyDocument document(
            long revision, AppResidencyPolicy policy) {
        return new ResidencyPolicyDocument(
                1, revision, true, ResidencyMode.BALANCED, List.of(policy));
    }

    private static AppResidencyPolicy completePolicy(String packageName) {
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                new KillPolicy(true, false, true, false, true, false, true, false),
                new FreezePolicy(
                        true, false, true, false, true, 1_000L, 86_400_000L,
                        false, true, true),
                new WakePolicy(
                        new WakeChannelPolicy(WakeMode.THROTTLE, 1_000L),
                        new WakeChannelPolicy(WakeMode.BLOCK, 0L),
                        EnumSet.of(
                                WakeSource.SERVICE, WakeSource.ASYNC_BINDER, WakeSource.OTHER)),
                new ResourcePolicy(
                        true, EnumSet.of(ResourceType.SERVICE, ResourceType.GPS, ResourceType.AUDIO)),
                new RefreezePolicy(
                        RefreezeMode.AFTER_WAKE,
                        0L,
                        1_000L,
                        86_400_000L,
                        2_000L,
                        3_000L,
                        4_000L,
                        5_000L));
    }

    private static AppResidencyPolicy policyWithAllDelays(String packageName, long delay) {
        return new AppResidencyPolicy(
                packageName,
                true,
                ResidencyMode.CUSTOM,
                KillPolicy.none(),
                new FreezePolicy(
                        true, true, true, true, false, delay, delay,
                        false, false, false),
                new WakePolicy(
                        new WakeChannelPolicy(WakeMode.ALLOW, delay),
                        new WakeChannelPolicy(WakeMode.ALLOW, delay),
                        Collections.emptySet()),
                ResourcePolicy.followSystem(),
                new RefreezePolicy(
                        RefreezeMode.SYSTEM,
                        delay,
                        delay,
                        delay,
                        delay,
                        delay,
                        delay,
                        delay));
    }
}
