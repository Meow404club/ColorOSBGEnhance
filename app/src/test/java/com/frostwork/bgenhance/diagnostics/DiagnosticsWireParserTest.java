package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import com.frostwork.bgenhance.BridgeContract;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DiagnosticsWireParserTest {
    private static final long NOW = 1_700_000_000_000L;
    private final DiagnosticsWireParser parser = new DiagnosticsWireParser();

    @Test
    public void parsesVersionedTypedBatchAndExposesIdentityForDeduplication() {
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, 17L);
        payload.putInt(BridgeContract.EXTRA_CONFIG_REVISION, 7);
        payload.putInt(DiagnosticsWireParser.KEY_POLICY_REVISION, 11);
        payload.putParcelableArrayList(
                DiagnosticsWireParser.KEY_RAW_HITS, list(validRawHit()));
        payload.putParcelableArrayList(
                DiagnosticsWireParser.KEY_EVENTS, list(validEvent()));
        payload.putParcelableArrayList(
                DiagnosticsWireParser.KEY_HOOK_STATES, list(validHookState()));
        DiagnosticsPayloadFingerprint.seal(payload);

        DiagnosticsWireParser.ParseResult result = parser.parseSnapshot(payload, NOW);

        assertAccepted(result, 3);
        assertEquals(DiagnosticsWriteBatch.CountingMode.TYPED, result.batch.countingMode);
        DiagnosticsWriteBatch.BatchIdentity identity = result.batch.batchIdentity;
        assertEquals(DiagnosticsWireParser.WIRE_VERSION, identity.wireVersion);
        assertEquals("boot-42", identity.bootId);
        assertEquals("system_server", identity.processId);
        assertEquals("sender-instance-9", identity.senderInstanceId);
        assertEquals(5L, identity.epoch);
        assertEquals(17L, identity.batchSequence);
        assertEquals("boot-42", result.batch.rawHits.get(0).bootId);
        assertEquals("system_server", result.batch.events.get(0).processId);
        assertEquals(7, result.batch.events.get(0).configRevision);
        assertEquals(11, result.batch.events.get(0).policyRevision);
        assertTrue(result.batch.hookStates.get(0).matchesBatchIdentity(identity));

        DiagnosticsWriteBatch.BatchIdentity retry =
                parser.parseSnapshot(payload, NOW).batch.batchIdentity;
        DiagnosticsWriteBatch.BatchIdentity next =
                parser.parseSnapshot(
                        typedPayloadWithRawHit(18L, validRawHit()), NOW)
                        .batch.batchIdentity;
        assertEquals(identity, retry);
        assertEquals(identity.deduplicationKey(), retry.deduplicationKey());
        assertNotEquals(identity, next);
    }

    @Test
    public void rejectsAnyInvalidTypedItemWithoutReturningPartialData() {
        Bundle invalid = validEvent();
        invalid.putString(
                DiagnosticsWireParser.KEY_BEHAVIOR,
                repeated('x', DiagnosticsValues.MAX_ID_LENGTH + 1));
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, 1L);
        payload.putParcelableArrayList(
                DiagnosticsWireParser.KEY_EVENTS,
                list(validEvent(), invalid));

        DiagnosticsWireParser.ParseResult result = parser.parseSnapshot(payload, NOW);

        assertRejected(result, "invalid_event", 2);
        assertEquals(DiagnosticsWriteBatch.CountingMode.NONE, result.batch.countingMode);
        assertFalse(result.batch.hasBatchIdentity());
    }

    @Test
    public void rejectsTypedAndLegacySourcesInEitherDeclaredMode() {
        Bundle typed = typedPayloadWithRawHit(1L, validRawHit());
        addLegacyFeature(typed, "freeze", 1, 1, 0);
        Bundle legacy = legacyPayload(2L, "freeze", 1, 1, 0);
        legacy.putParcelableArrayList(
                DiagnosticsWireParser.KEY_RAW_HITS, list(validRawHit()));

        assertRejected(
                parser.parseSnapshot(typed, NOW), "mixed_counting_modes", 2);
        assertRejected(
                parser.parseSnapshot(legacy, NOW), "mixed_counting_modes", 2);
    }

    @Test
    public void obsoleteSkipFlagCannotReenableDualCounting() {
        Bundle payload = typedPayloadWithRawHit(1L, validRawHit());
        payload.putBoolean(DiagnosticsWireParser.KEY_SKIP_LEGACY_TOTALS, true);

        assertRejected(
                parser.parseSnapshot(payload, NOW), "obsolete_counting_switch", 1);
    }

    @Test
    public void parsesExplicitLegacySnapshotWithoutTypedRows() {
        Bundle payload = legacyPayload(3L, "freeze", 4, 3, 1);

        DiagnosticsWireParser.ParseResult result = parser.parseSnapshot(payload, NOW);

        assertAccepted(result, 1);
        assertEquals(DiagnosticsWriteBatch.CountingMode.LEGACY, result.batch.countingMode);
        assertTrue(result.batch.rawHits.isEmpty());
        assertTrue(result.batch.events.isEmpty());
        assertTrue(result.batch.hookStates.isEmpty());
        DiagnosticsWriteBatch.LegacyFeatureDelta delta =
                result.batch.legacyFeatureDeltas.get(0);
        assertEquals("freeze", delta.featureId);
        assertEquals(4L, delta.observedDelta);
        assertEquals(3L, delta.blockedDelta);
        assertEquals(1L, delta.failOpenDelta);
    }

    @Test
    public void legacyCompatibilityRequiresExplicitModeAndVersionedIdentity() {
        Bundle missingMode = legacyPayload(1L, "freeze", 1, 1, 0);
        missingMode.remove(DiagnosticsWireParser.KEY_COUNTING_MODE);
        Bundle missingEpoch = legacyPayload(2L, "freeze", 1, 1, 0);
        missingEpoch.remove(DiagnosticsWireParser.KEY_EPOCH);
        Bundle missingSender = legacyPayload(3L, "freeze", 1, 1, 0);
        missingSender.remove(DiagnosticsWireParser.KEY_SENDER_INSTANCE_ID);
        Bundle unsupported = legacyPayload(4L, "freeze", 1, 1, 0);
        unsupported.putInt(
                DiagnosticsWireParser.KEY_WIRE_VERSION,
                DiagnosticsWireParser.WIRE_VERSION - 1);

        assertRejected(
                parser.parseSnapshot(missingMode, NOW), "missing_counting_mode", 1);
        assertRejected(
                parser.parseSnapshot(missingEpoch, NOW), "invalid_batch_identity", 1);
        assertRejected(
                parser.parseSnapshot(missingSender, NOW), "invalid_batch_identity", 1);
        assertRejected(
                parser.parseSnapshot(unsupported, NOW), "unsupported_wire_version", 1);
    }

    @Test
    public void explicitLegacySingleEventRetainsCompatibilityWithoutTypedMixing() {
        Bundle event = envelope(DiagnosticsWriteBatch.CountingMode.LEGACY, 4L);
        event.putString(BridgeContract.EXTRA_FEATURE, "freeze");
        event.putString(BridgeContract.EXTRA_RESULT, "blocked");
        event.putLong(BridgeContract.EXTRA_TIMESTAMP, NOW);
        event.putString(BridgeContract.EXTRA_PACKAGE, "com.example.target");
        event.putString(BridgeContract.EXTRA_PROCESS, "system_server");
        event.putString(BridgeContract.EXTRA_SCENE, "FAST");
        event.putInt(BridgeContract.EXTRA_CODE, 7);
        DiagnosticsPayloadFingerprint.seal(event);

        DiagnosticsWireParser.ParseResult accepted =
                parser.parseLegacyRecordEvent(event, NOW);
        Bundle typed = new Bundle(event);
        typed.putString(
                DiagnosticsWireParser.KEY_COUNTING_MODE,
                DiagnosticsWriteBatch.CountingMode.TYPED.wireValue);

        assertAccepted(accepted, 1);
        assertEquals(1L, accepted.batch.legacyFeatureDeltas.get(0).blockedDelta);
        assertRejected(
                parser.parseLegacyRecordEvent(typed, NOW), "mixed_counting_modes", 1);
    }

    @Test
    public void totalItemLimitAppliesAcrossAllTypedListsWithoutTruncation() {
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, 1L);
        ArrayList<Bundle> raw = new ArrayList<>();
        ArrayList<Bundle> states = new ArrayList<>();
        for (int index = 0; index < DiagnosticsWireParser.MAX_WIRE_ITEMS / 2; index++) {
            raw.add(validRawHit());
        }
        for (int index = 0; index <= DiagnosticsWireParser.MAX_WIRE_ITEMS / 2; index++) {
            states.add(validHookState());
        }
        payload.putParcelableArrayList(DiagnosticsWireParser.KEY_RAW_HITS, raw);
        payload.putParcelableArrayList(DiagnosticsWireParser.KEY_HOOK_STATES, states);

        assertRejected(
                parser.parseSnapshot(payload, NOW),
                "too_many_wire_items",
                DiagnosticsWireParser.MAX_WIRE_ITEMS + 1);
    }

    @Test
    public void exactItemLimitIsAcceptedAndEmptyBatchesAreRejected() {
        Bundle maximum = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, 1L);
        ArrayList<Bundle> raw = new ArrayList<>();
        for (int index = 0; index < DiagnosticsWireParser.MAX_WIRE_ITEMS; index++) {
            raw.add(validRawHit());
        }
        maximum.putParcelableArrayList(DiagnosticsWireParser.KEY_RAW_HITS, raw);
        DiagnosticsPayloadFingerprint.seal(maximum);
        Bundle empty = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, 2L);

        assertAccepted(parser.parseSnapshot(maximum, NOW),
                DiagnosticsWireParser.MAX_WIRE_ITEMS);
        assertRejected(parser.parseSnapshot(empty, NOW), "empty_batch", 1);
    }

    @Test
    public void bundleFieldCountsAreBoundedBeforeSchemaTraversal() {
        Bundle payload = typedPayloadWithRawHit(1L, validRawHit());
        for (int index = 0; index < DiagnosticsWireParser.MAX_WIRE_BUNDLE_KEYS; index++) {
            payload.putInt("unknown." + index, index);
        }

        assertRejected(
                parser.parseSnapshot(payload, NOW), "too_many_bundle_fields", 1);
    }

    @Test
    public void rejectsWrongScalarAndListTypes() {
        Bundle wrongEpoch = typedPayloadWithRawHit(1L, validRawHit());
        wrongEpoch.putInt(DiagnosticsWireParser.KEY_EPOCH, 5);
        Bundle wrongBoolean = typedPayloadWithRawHit(2L, validRawHit());
        ArrayList<Bundle> rawHits = wrongBoolean.getParcelableArrayList(
                DiagnosticsWireParser.KEY_RAW_HITS);
        rawHits.get(0).putLong(DiagnosticsWireParser.KEY_PARSED, 1L);
        Bundle wrongListEntry = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, 3L);
        wrongListEntry.putSerializable(
                DiagnosticsWireParser.KEY_EVENTS,
                new ArrayList<>(Arrays.asList("not-a-bundle")));

        assertRejected(
                parser.parseSnapshot(wrongEpoch, NOW), "invalid_batch_identity", 1);
        assertRejected(
                parser.parseSnapshot(wrongBoolean, NOW), "invalid_raw_hit", 1);
        assertRejected(
                parser.parseSnapshot(wrongListEntry, NOW), "invalid_typed_items", 1);
    }

    @Test
    public void rejectsOversizedOuterAndNestedBundlesBeforeParsingValues() {
        Bundle outer = typedPayloadWithRawHit(1L, validRawHit());
        outer.putString(
                DiagnosticsWireParser.KEY_BOOT_ID,
                repeated('x', DiagnosticsWireParser.MAX_WIRE_BUNDLE_BYTES));
        Bundle oversizedItem = validRawHit();
        oversizedItem.putString(
                BridgeContract.EXTRA_ERROR,
                repeated('x', DiagnosticsWireParser.MAX_WIRE_ITEM_BYTES));

        assertRejected(
                parser.parseSnapshot(outer, NOW), "wire_bundle_too_large", 1);
        assertRejected(
                parser.parseSnapshot(
                        typedPayloadWithRawHit(2L, oversizedItem), NOW),
                "wire_item_too_large",
                1);
    }

    @Test
    public void rejectsStringsThatWouldOtherwiseBeTrimmedTruncatedOrRepaired() {
        Bundle leadingSpace = typedPayloadWithRawHit(1L, validRawHit());
        leadingSpace.putString(BridgeContract.EXTRA_PROCESS_ID, " system_server");
        Bundle malformedUtf16 = validEvent();
        malformedUtf16.putString(BridgeContract.EXTRA_SCENE, "bad\uD800text");
        Bundle controlCharacter = validRawHit();
        controlCharacter.putString(BridgeContract.EXTRA_ERROR, "bad\u0001error");

        assertRejected(
                parser.parseSnapshot(leadingSpace, NOW), "invalid_batch_identity", 1);
        assertRejected(
                parser.parseSnapshot(
                        typedPayloadWithEvent(2L, malformedUtf16), NOW),
                "invalid_event",
                1);
        assertRejected(
                parser.parseSnapshot(
                        typedPayloadWithRawHit(3L, controlCharacter), NOW),
                "invalid_raw_hit",
                1);
    }

    @Test
    public void exactStringLimitsRemainAccepted() {
        Bundle payload = typedPayloadWithRawHit(1L, validRawHit());
        payload.putString(
                DiagnosticsWireParser.KEY_BOOT_ID,
                repeated('b', DiagnosticsValues.MAX_ID_LENGTH));
        DiagnosticsPayloadFingerprint.seal(payload);

        DiagnosticsWireParser.ParseResult result = parser.parseSnapshot(payload, NOW);

        assertAccepted(result, 1);
        assertEquals(DiagnosticsValues.MAX_ID_LENGTH,
                result.batch.batchIdentity.bootId.length());
    }

    @Test
    public void itemIdentityOverridesMustMatchTheBatchIdentity() {
        Bundle raw = validRawHit();
        raw.putString(BridgeContract.EXTRA_PROCESS_ID, "athena");

        assertRejected(
                parser.parseSnapshot(typedPayloadWithRawHit(1L, raw), NOW),
                "batch_identity_mismatch",
                1);
    }

    @Test
    public void payloadMutationAfterSealingRejectsTheWholeBatch() {
        Bundle payload = typedPayloadWithEvent(1L, validEvent());
        ArrayList<Bundle> events = payload.getParcelableArrayList(
                DiagnosticsWireParser.KEY_EVENTS);
        events.get(0).putString(BridgeContract.EXTRA_PACKAGE, "com.example.changed");

        assertRejected(
                parser.parseSnapshot(payload, NOW),
                "payload_hash_mismatch",
                1);
    }

    @Test
    public void hookAndLegacyCountersHaveStrictBoundsAndConsistency() {
        Bundle state = validHookState();
        state.putLong(DiagnosticsWireParser.KEY_RAW_COUNT, 1L);
        state.putLong(DiagnosticsWireParser.KEY_PARSED_COUNT, 2L);
        Bundle inconsistentLegacy = legacyPayload(2L, "freeze", 1, 1, 1);
        Bundle excessiveLegacy = legacyPayload(3L, "freeze", 1, 0, 0);
        excessiveLegacy.getBundle("feature.freeze").putInt(
                "observed_delta", (int) DiagnosticsWireParser.MAX_COUNTER_DELTA + 1);

        assertRejected(
                parser.parseSnapshot(
                        typedPayloadWithHookState(1L, state), NOW),
                "invalid_hook_state",
                1);
        assertRejected(
                parser.parseSnapshot(inconsistentLegacy, NOW),
                "invalid_legacy_delta",
                1);
        assertRejected(
                parser.parseSnapshot(excessiveLegacy, NOW),
                "invalid_legacy_delta",
                1);
    }

    @Test
    public void duplicateLegacyFeatureEntriesRejectTheWholeBatch() {
        Bundle payload = legacyPayload(1L, "freeze", 1, 1, 0);
        payload.putStringArrayList(
                BridgeContract.EXTRA_FEATURES,
                new ArrayList<>(Arrays.asList("freeze", "freeze")));

        assertRejected(
                parser.parseSnapshot(payload, NOW), "invalid_legacy_delta", 2);
    }

    private static Bundle envelope(
            DiagnosticsWriteBatch.CountingMode mode, long sequence) {
        Bundle payload = new Bundle();
        payload.putInt(
                DiagnosticsWireParser.KEY_WIRE_VERSION,
                DiagnosticsWireParser.WIRE_VERSION);
        payload.putString(DiagnosticsWireParser.KEY_COUNTING_MODE, mode.wireValue);
        payload.putString(DiagnosticsWireParser.KEY_BOOT_ID, "boot-42");
        payload.putString(BridgeContract.EXTRA_PROCESS_ID, "system_server");
        payload.putString(
                DiagnosticsWireParser.KEY_SENDER_INSTANCE_ID, "sender-instance-9");
        payload.putLong(DiagnosticsWireParser.KEY_EPOCH, 5L);
        payload.putLong(DiagnosticsWireParser.KEY_BATCH_SEQUENCE, sequence);
        payload.putString(DiagnosticsWireParser.KEY_PAYLOAD_HASH, repeated('0', 64));
        return payload;
    }

    private static Bundle typedPayloadWithRawHit(long sequence, Bundle raw) {
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, sequence);
        payload.putParcelableArrayList(DiagnosticsWireParser.KEY_RAW_HITS, list(raw));
        DiagnosticsPayloadFingerprint.seal(payload);
        return payload;
    }

    private static Bundle typedPayloadWithEvent(long sequence, Bundle event) {
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, sequence);
        payload.putParcelableArrayList(DiagnosticsWireParser.KEY_EVENTS, list(event));
        DiagnosticsPayloadFingerprint.seal(payload);
        return payload;
    }

    private static Bundle typedPayloadWithHookState(long sequence, Bundle state) {
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.TYPED, sequence);
        payload.putParcelableArrayList(DiagnosticsWireParser.KEY_HOOK_STATES, list(state));
        DiagnosticsPayloadFingerprint.seal(payload);
        return payload;
    }

    private static Bundle legacyPayload(
            long sequence,
            String feature,
            int observed,
            int blocked,
            int failOpen) {
        Bundle payload = envelope(DiagnosticsWriteBatch.CountingMode.LEGACY, sequence);
        addLegacyFeature(payload, feature, observed, blocked, failOpen);
        DiagnosticsPayloadFingerprint.seal(payload);
        return payload;
    }

    private static void addLegacyFeature(
            Bundle payload,
            String feature,
            int observed,
            int blocked,
            int failOpen) {
        payload.putStringArrayList(
                BridgeContract.EXTRA_FEATURES,
                new ArrayList<>(Arrays.asList(feature)));
        Bundle delta = new Bundle();
        delta.putInt("observed_delta", observed);
        delta.putInt("blocked_delta", blocked);
        delta.putInt("fail_open_delta", failOpen);
        delta.putLong("last_at", NOW);
        delta.putString("last_pkg", "com.example.target");
        delta.putString("last_process", "system_server");
        delta.putString("last_scene", "FAST");
        delta.putString("last_reason", "7");
        delta.putString("last_result", blocked > 0 ? "blocked" : "passed");
        payload.putBundle("feature." + feature, delta);
    }

    private static Bundle validRawHit() {
        Bundle item = new Bundle();
        item.putLong(BridgeContract.EXTRA_TIMESTAMP, NOW);
        item.putString(BridgeContract.EXTRA_FEATURE, "freeze");
        item.putString(DiagnosticsWireParser.KEY_HOOK_TARGET, "freeze_target");
        item.putBoolean(DiagnosticsWireParser.KEY_PARSED, true);
        item.putBoolean(DiagnosticsWireParser.KEY_IDENTITY_RESOLVED, true);
        return item;
    }

    private static Bundle validEvent() {
        Bundle item = new Bundle();
        item.putLong(BridgeContract.EXTRA_TIMESTAMP, NOW);
        item.putString(BridgeContract.EXTRA_FEATURE, "freeze");
        item.putString(DiagnosticsWireParser.KEY_HOOK_TARGET, "freeze_target");
        item.putString(DiagnosticsWireParser.KEY_BEHAVIOR, "FREEZE");
        item.putString(DiagnosticsWireParser.KEY_SOURCE, "FAST");
        item.putString(BridgeContract.EXTRA_PACKAGE, "com.example.target");
        item.putInt(BridgeContract.EXTRA_UID, 10123);
        item.putString(BridgeContract.EXTRA_SCENE, "FAST");
        item.putInt(BridgeContract.EXTRA_CODE, 7);
        item.putString(DiagnosticsWireParser.KEY_MODE, "CUSTOM");
        item.putString(DiagnosticsWireParser.KEY_DECISION, "BLOCK_FREEZE");
        item.putString(BridgeContract.EXTRA_RESULT, "blocked");
        item.putLong(DiagnosticsWireParser.KEY_LATENCY_MICROS, 25L);
        item.putInt(DiagnosticsWireParser.KEY_RAW_HIT_COUNT, 1);
        item.putBoolean(DiagnosticsWireParser.KEY_IDENTITY_RESOLVED, true);
        item.putString(DiagnosticsWireParser.KEY_EVENT_TOKEN, "event-1");
        return item;
    }

    private static Bundle validHookState() {
        Bundle item = new Bundle();
        item.putString(BridgeContract.EXTRA_FEATURE, "freeze");
        item.putString(DiagnosticsWireParser.KEY_HOOK_TARGET, "freeze_target");
        item.putString(DiagnosticsWireParser.KEY_INSTALL_STATUS, "INSTALLED");
        item.putLong(DiagnosticsWireParser.KEY_LAST_HOOK_AT, NOW);
        item.putLong(DiagnosticsWireParser.KEY_LAST_EVENT_AT, NOW);
        item.putLong(DiagnosticsWireParser.KEY_RAW_COUNT, 3L);
        item.putLong(DiagnosticsWireParser.KEY_PARSED_COUNT, 2L);
        item.putLong(DiagnosticsWireParser.KEY_IDENTITY_COUNT, 1L);
        item.putLong(DiagnosticsWireParser.KEY_UPDATED_AT, NOW);
        return item;
    }

    @SafeVarargs
    private static <T> ArrayList<T> list(T... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String repeated(char value, int count) {
        char[] output = new char[count];
        Arrays.fill(output, value);
        return new String(output);
    }

    private static void assertAccepted(
            DiagnosticsWireParser.ParseResult result, int acceptedItems) {
        assertTrue(result.accepted());
        assertTrue(result.hasData());
        assertEquals("", result.errorCode);
        assertEquals(acceptedItems, result.acceptedItems);
        assertEquals(0, result.droppedItems);
    }

    private static void assertRejected(
            DiagnosticsWireParser.ParseResult result,
            String errorCode,
            int droppedItems) {
        assertFalse(result.accepted());
        assertFalse(result.hasData());
        assertEquals(errorCode, result.errorCode);
        assertEquals(0, result.acceptedItems);
        assertEquals(droppedItems, result.droppedItems);
        assertTrue(result.batch.rawHits.isEmpty());
        assertTrue(result.batch.events.isEmpty());
        assertTrue(result.batch.hookStates.isEmpty());
        assertTrue(result.batch.legacyFeatureDeltas.isEmpty());
    }
}
