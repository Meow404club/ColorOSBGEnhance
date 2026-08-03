package com.frostwork.bgenhance.diagnostics;

import android.os.Bundle;
import android.os.Parcel;
import com.frostwork.bgenhance.BridgeContract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Defensive, all-or-nothing parser for the restricted diagnostics Provider wire format. */
@SuppressWarnings("deprecation")
public final class DiagnosticsWireParser {
    public static final int WIRE_VERSION = 3;
    public static final int MAX_WIRE_ITEMS = 256;
    public static final int MAX_WIRE_BUNDLE_BYTES = 512 * 1_024;
    public static final int MAX_WIRE_ITEM_BYTES = 16 * 1_024;
    public static final int MAX_WIRE_BUNDLE_KEYS = MAX_WIRE_ITEMS + 16;
    public static final int MAX_WIRE_ITEM_KEYS = 32;
    public static final long MAX_COUNTER_DELTA = 1_000_000L;

    public static final String KEY_WIRE_VERSION = "diagnostics_wire_version";
    public static final String KEY_COUNTING_MODE = "diagnostics_counting_mode";
    public static final String KEY_SENDER_INSTANCE_ID = "diagnostics_sender_instance_id";
    public static final String KEY_EPOCH = "diagnostics_epoch";
    public static final String KEY_BATCH_SEQUENCE = "diagnostics_batch_sequence";
    public static final String KEY_PAYLOAD_HASH = "diagnostics_payload_hash";
    public static final String KEY_RAW_HITS = "diagnostics_raw_hits";
    public static final String KEY_EVENTS = "diagnostics_events";
    public static final String KEY_HOOK_STATES = "diagnostics_hook_states";
    @Deprecated
    public static final String KEY_SKIP_LEGACY_TOTALS = "diagnostics_skip_legacy_totals";
    public static final String KEY_BOOT_ID = "boot_id";
    public static final String KEY_HOOK_TARGET = "hook_target";
    public static final String KEY_BEHAVIOR = "behavior";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_POLICY_REVISION = "policy_revision";
    public static final String KEY_MODE = "mode";
    public static final String KEY_DECISION = "decision";
    public static final String KEY_FAIL_OPEN_REASON = "fail_open_reason";
    public static final String KEY_LATENCY_MICROS = "latency_micros";
    public static final String KEY_RAW_HIT_COUNT = "raw_hit_count";
    public static final String KEY_PARSED = "parsed";
    public static final String KEY_IDENTITY_RESOLVED = "identity_resolved";
    public static final String KEY_EVENT_TOKEN = "event_token";
    public static final String KEY_INSTALL_STATUS = "install_status";
    public static final String KEY_LAST_HOOK_AT = "last_hook_at";
    public static final String KEY_LAST_EVENT_AT = "last_event_at";
    public static final String KEY_RAW_COUNT = "raw_count";
    public static final String KEY_PARSED_COUNT = "parsed_count";
    public static final String KEY_IDENTITY_COUNT = "identity_count";
    public static final String KEY_UPDATED_AT = "updated_at";

    private static final int MAX_EVENT_TOKEN_LENGTH = 192;
    private static final long TIMESTAMP_FUTURE_TOLERANCE_MILLIS = 300_000L;
    private static final String FEATURE_PREFIX = "feature.";

    public ParseResult parseSnapshot(Bundle extras) {
        return parseSnapshot(extras, System.currentTimeMillis());
    }

    ParseResult parseSnapshot(Bundle extras, long requestedNow) {
        if (extras == null) return ParseResult.failed("missing_extras", 1);
        int claimedItems = 1;
        long now = Math.max(1L, requestedNow);
        try {
            requireBundleBounds(
                    extras, MAX_WIRE_BUNDLE_BYTES, MAX_WIRE_BUNDLE_KEYS,
                    "wire_bundle_too_large");
            DiagnosticsWriteBatch.BatchIdentity identity = parseIdentity(extras);
            DiagnosticsWriteBatch.CountingMode mode = parseCountingMode(extras);
            int outerConfigRevision = revisionValue(
                    extras, BridgeContract.EXTRA_CONFIG_REVISION, -1,
                    "invalid_provider_payload");
            int outerPolicyRevision = revisionValue(
                    extras, KEY_POLICY_REVISION, -1, "invalid_provider_payload");
            claimedItems = claimedWireItemCount(extras);
            if (extras.containsKey(KEY_SKIP_LEGACY_TOTALS)) {
                throw invalid("obsolete_counting_switch");
            }

            DiagnosticsWriteBatch.Builder out = DiagnosticsWriteBatch.builder()
                    .batchIdentity(identity)
                    .countingMode(mode);
            if (mode == DiagnosticsWriteBatch.CountingMode.TYPED) {
                if (hasLegacyPayload(extras)) throw invalid("mixed_counting_modes");
                ArrayList<Bundle> rawHits = bundleList(extras, KEY_RAW_HITS);
                ArrayList<Bundle> events = bundleList(extras, KEY_EVENTS);
                ArrayList<Bundle> states = bundleList(extras, KEY_HOOK_STATES);
                claimedItems = checkedTotalSize(rawHits, events, states);
                if (claimedItems == 0) throw invalid("empty_batch");
                requireTypedOuterKeys(extras);

                for (Bundle item : rawHits) {
                    requireBundleBounds(item, MAX_WIRE_ITEM_BYTES, MAX_WIRE_ITEM_KEYS,
                            "wire_item_too_large");
                    requireRawHitKeys(item);
                    out.addRawHit(parseRawHit(
                            item, identity, outerConfigRevision, outerPolicyRevision, now));
                }
                for (Bundle item : events) {
                    requireBundleBounds(item, MAX_WIRE_ITEM_BYTES, MAX_WIRE_ITEM_KEYS,
                            "wire_item_too_large");
                    requireEventKeys(item);
                    out.addEvent(parseEvent(
                            item, identity, outerConfigRevision, outerPolicyRevision, now));
                }
                for (Bundle item : states) {
                    requireBundleBounds(item, MAX_WIRE_ITEM_BYTES, MAX_WIRE_ITEM_KEYS,
                            "wire_item_too_large");
                    requireHookStateKeys(item);
                    out.addHookState(parseHookState(
                            item, identity, outerConfigRevision, outerPolicyRevision, now));
                }
            } else {
                if (hasTypedPayload(extras)) throw invalid("mixed_counting_modes");
                ArrayList<String> features = stringList(
                        extras, BridgeContract.EXTRA_FEATURES, "invalid_legacy_delta");
                claimedItems = features.size();
                if (claimedItems == 0) throw invalid("empty_batch");
                if (claimedItems > MAX_WIRE_ITEMS) {
                    throw invalid("too_many_wire_items", claimedItems);
                }
                requireLegacyOuterKeys(extras, features);
                parseLegacyFeatureDeltas(extras, features, out, now);
            }
            DiagnosticsWriteBatch batch = out.build();
            if (batch.size() != claimedItems || batch.isEmpty()) {
                throw invalid("invalid_provider_payload");
            }
            requireMatchingPayloadHash(extras, identity);
            return new ParseResult(batch, claimedItems, 0, "");
        } catch (InvalidPayload error) {
            return ParseResult.failed(error.errorCode,
                    Math.max(claimedItems, error.rejectedItems));
        } catch (Throwable error) {
            return ParseResult.failed("invalid_provider_payload", claimedItems);
        }
    }

    public ParseResult parseLegacyRecordEvent(Bundle extras) {
        return parseLegacyRecordEvent(extras, System.currentTimeMillis());
    }

    ParseResult parseLegacyRecordEvent(Bundle extras, long requestedNow) {
        if (extras == null) return ParseResult.failed("missing_extras", 1);
        long now = Math.max(1L, requestedNow);
        try {
            requireBundleBounds(
                    extras, MAX_WIRE_ITEM_BYTES, MAX_WIRE_ITEM_KEYS,
                    "wire_bundle_too_large");
            DiagnosticsWriteBatch.BatchIdentity identity = parseIdentity(extras);
            DiagnosticsWriteBatch.CountingMode mode = parseCountingMode(extras);
            if (mode != DiagnosticsWriteBatch.CountingMode.LEGACY) {
                throw invalid("mixed_counting_modes");
            }
            if (hasTypedPayload(extras)
                    || extras.containsKey(BridgeContract.EXTRA_FEATURES)
                    || extras.containsKey(KEY_SKIP_LEGACY_TOTALS)) {
                throw invalid("mixed_counting_modes");
            }
            requireLegacyEventKeys(extras);

            String feature = stringValue(
                    extras, BridgeContract.EXTRA_FEATURE, "",
                    DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                    "invalid_legacy_event");
            String result = stringValue(
                    extras, BridgeContract.EXTRA_RESULT, "passed",
                    DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                    "invalid_legacy_event");
            long timestamp = timestampValue(
                    extras, BridgeContract.EXTRA_TIMESTAMP, now, false, now,
                    "invalid_legacy_event");
            String lastProcess = stringValue(
                    extras, BridgeContract.EXTRA_PROCESS, identity.processId,
                    DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                    "invalid_legacy_event");
            DiagnosticsWriteBatch.LegacyFeatureDelta delta =
                    new DiagnosticsWriteBatch.LegacyFeatureDelta(
                            feature,
                            1L,
                            DiagnosticsValues.isBlocked(result) ? 1L : 0L,
                            DiagnosticsValues.isFailOpen(result) ? 1L : 0L,
                            timestamp,
                            stringValue(
                                    extras, BridgeContract.EXTRA_PACKAGE, "",
                                    DiagnosticsValues.MAX_PACKAGE_LENGTH, true,
                                    StringKind.PACKAGE, "invalid_legacy_event"),
                            lastProcess,
                            stringValue(
                                    extras, BridgeContract.EXTRA_SCENE, "",
                                    DiagnosticsValues.MAX_TEXT_LENGTH, true,
                                    StringKind.TEXT, "invalid_legacy_event"),
                            String.valueOf(intValue(
                                    extras, BridgeContract.EXTRA_CODE, 0,
                                    Integer.MIN_VALUE, Integer.MAX_VALUE,
                                    "invalid_legacy_event")),
                            result);
            if (!delta.isStorable()) throw invalid("invalid_legacy_event");
            DiagnosticsWriteBatch batch = DiagnosticsWriteBatch.builder()
                    .batchIdentity(identity)
                    .countingMode(DiagnosticsWriteBatch.CountingMode.LEGACY)
                    .addLegacyFeatureDelta(delta)
                    .build();
            requireMatchingPayloadHash(extras, identity);
            return new ParseResult(batch, 1, 0, "");
        } catch (InvalidPayload error) {
            return ParseResult.failed(error.errorCode, error.rejectedItems);
        } catch (Throwable error) {
            return ParseResult.failed("invalid_legacy_event", 1);
        }
    }

    private static DiagnosticsWriteBatch.BatchIdentity parseIdentity(Bundle extras) {
        int wireVersion = requiredInt(
                extras, KEY_WIRE_VERSION, "invalid_batch_identity");
        if (wireVersion != WIRE_VERSION) throw invalid("unsupported_wire_version");
        String bootId = stringValue(
                extras, KEY_BOOT_ID, null,
                DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                "invalid_batch_identity");
        String processId = stringValue(
                extras, BridgeContract.EXTRA_PROCESS_ID, null,
                DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                "invalid_batch_identity");
        String senderInstanceId = stringValue(
                extras, KEY_SENDER_INSTANCE_ID, null,
                DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                "invalid_batch_identity");
        long epoch = requiredLong(extras, KEY_EPOCH, "invalid_batch_identity");
        long sequence = requiredLong(
                extras, KEY_BATCH_SEQUENCE, "invalid_batch_identity");
        String payloadHash = stringValue(
                extras, KEY_PAYLOAD_HASH, null,
                64, false, StringKind.TEXT, "invalid_batch_identity");
        if (epoch < 0L || sequence < 0L) throw invalid("invalid_batch_identity");
        if (!DiagnosticsValues.isSha256(payloadHash, false)) {
            throw invalid("invalid_batch_identity");
        }
        return new DiagnosticsWriteBatch.BatchIdentity(
                wireVersion, bootId, processId, senderInstanceId,
                epoch, sequence, payloadHash);
    }

    private static void requireMatchingPayloadHash(
            Bundle extras, DiagnosticsWriteBatch.BatchIdentity identity) {
        if (identity == null
                || !identity.payloadHash.equals(DiagnosticsPayloadFingerprint.compute(extras))) {
            throw invalid("payload_hash_mismatch");
        }
    }

    private static DiagnosticsWriteBatch.CountingMode parseCountingMode(Bundle extras) {
        String value = stringValue(
                extras, KEY_COUNTING_MODE, null,
                DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID,
                "missing_counting_mode");
        DiagnosticsWriteBatch.CountingMode mode =
                DiagnosticsWriteBatch.CountingMode.fromWireValue(value);
        if (mode == null) throw invalid("invalid_counting_mode");
        return mode;
    }

    private static RawHookHit parseRawHit(
            Bundle item,
            DiagnosticsWriteBatch.BatchIdentity identity,
            int outerConfigRevision,
            int outerPolicyRevision,
            long now) {
        String error = "invalid_raw_hit";
        requireMatchingIdentity(item, identity, error);
        RawHookHit value = RawHookHit.builder()
                .timestampMillis(timestampValue(
                        item, BridgeContract.EXTRA_TIMESTAMP, now, false, now, error))
                .bootId(identity.bootId)
                .processId(identity.processId)
                .featureId(stringValue(
                        item, BridgeContract.EXTRA_FEATURE, "",
                        DiagnosticsValues.MAX_ID_LENGTH, true, StringKind.ID, error))
                .hookTargetId(stringValue(
                        item, KEY_HOOK_TARGET, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .configRevision(revisionValue(
                        item, BridgeContract.EXTRA_CONFIG_REVISION,
                        outerConfigRevision, error))
                .policyRevision(revisionValue(
                        item, KEY_POLICY_REVISION, outerPolicyRevision, error))
                .parsed(booleanValue(item, KEY_PARSED, false, error))
                .identityResolved(booleanValue(
                        item, KEY_IDENTITY_RESOLVED, false, error))
                .errorCode(stringValue(
                        item, BridgeContract.EXTRA_ERROR, "",
                        DiagnosticsValues.MAX_ERROR_LENGTH, true, StringKind.TEXT, error))
                .build();
        if (!value.isStorable()) throw invalid(error);
        return value;
    }

    private static NormalizedDiagnosticsEvent parseEvent(
            Bundle item,
            DiagnosticsWriteBatch.BatchIdentity identity,
            int outerConfigRevision,
            int outerPolicyRevision,
            long now) {
        String error = "invalid_event";
        requireMatchingIdentity(item, identity, error);
        NormalizedDiagnosticsEvent value = NormalizedDiagnosticsEvent.builder()
                .timestampMillis(timestampValue(
                        item, BridgeContract.EXTRA_TIMESTAMP, now, false, now, error))
                .bootId(identity.bootId)
                .processId(identity.processId)
                .featureId(stringValue(
                        item, BridgeContract.EXTRA_FEATURE, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .hookTargetId(stringValue(
                        item, KEY_HOOK_TARGET, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .behavior(stringValue(
                        item, KEY_BEHAVIOR, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .source(stringValue(
                        item, KEY_SOURCE, "",
                        DiagnosticsValues.MAX_ID_LENGTH, true, StringKind.ID, error))
                .packageName(stringValue(
                        item, BridgeContract.EXTRA_PACKAGE, "",
                        DiagnosticsValues.MAX_PACKAGE_LENGTH, true,
                        StringKind.PACKAGE, error))
                .uid(intValue(
                        item, BridgeContract.EXTRA_UID, -1, -1, Integer.MAX_VALUE, error))
                .scene(stringValue(
                        item, BridgeContract.EXTRA_SCENE, "",
                        DiagnosticsValues.MAX_TEXT_LENGTH, true, StringKind.TEXT, error))
                .reasonCode(intValue(
                        item, BridgeContract.EXTRA_CODE, 0,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, error))
                .configRevision(revisionValue(
                        item, BridgeContract.EXTRA_CONFIG_REVISION,
                        outerConfigRevision, error))
                .policyRevision(revisionValue(
                        item, KEY_POLICY_REVISION, outerPolicyRevision, error))
                .mode(stringValue(
                        item, KEY_MODE, "",
                        DiagnosticsValues.MAX_ID_LENGTH, true, StringKind.ID, error))
                .decision(stringValue(
                        item, KEY_DECISION, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .result(stringValue(
                        item, BridgeContract.EXTRA_RESULT, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .failOpenReason(stringValue(
                        item, KEY_FAIL_OPEN_REASON, "",
                        DiagnosticsValues.MAX_ERROR_LENGTH, true, StringKind.TEXT, error))
                .latencyMicros(longValue(
                        item, KEY_LATENCY_MICROS, 0L, 0L, Long.MAX_VALUE, error))
                .rawHitCount(intValue(
                        item, KEY_RAW_HIT_COUNT, 1, 1,
                        (int) MAX_COUNTER_DELTA, error))
                .identityResolved(booleanValue(
                        item, KEY_IDENTITY_RESOLVED, false, error))
                .eventToken(stringValue(
                        item, KEY_EVENT_TOKEN, "", MAX_EVENT_TOKEN_LENGTH,
                        true, StringKind.TEXT, error))
                .build();
        if (!value.isStorable()) throw invalid(error);
        return value;
    }

    private static DiagnosticsHookState parseHookState(
            Bundle item,
            DiagnosticsWriteBatch.BatchIdentity identity,
            int outerConfigRevision,
            int outerPolicyRevision,
            long now) {
        String error = "invalid_hook_state";
        requireMatchingIdentity(item, identity, error);
        if (item.containsKey(KEY_INSTALL_STATUS)
                && item.containsKey(BridgeContract.EXTRA_STATUS)) {
            throw invalid(error);
        }
        String installStatus = item.containsKey(KEY_INSTALL_STATUS)
                ? stringValue(
                        item, KEY_INSTALL_STATUS, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error)
                : stringValue(
                        item, BridgeContract.EXTRA_STATUS, "UNKNOWN",
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error);
        DiagnosticsHookState value = DiagnosticsHookState.builder()
                .processId(identity.processId)
                .featureId(stringValue(
                        item, BridgeContract.EXTRA_FEATURE, "",
                        DiagnosticsValues.MAX_ID_LENGTH, true, StringKind.ID, error))
                .hookTargetId(stringValue(
                        item, KEY_HOOK_TARGET, null,
                        DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, error))
                .installStatus(installStatus)
                .bootId(identity.bootId)
                .lastHookAt(timestampValue(
                        item, KEY_LAST_HOOK_AT, 0L, true, now, error))
                .lastEventAt(timestampValue(
                        item, KEY_LAST_EVENT_AT, 0L, true, now, error))
                .rawHitCount(longValue(
                        item, KEY_RAW_COUNT, 0L, 0L, MAX_COUNTER_DELTA, error))
                .parsedCount(longValue(
                        item, KEY_PARSED_COUNT, 0L, 0L, MAX_COUNTER_DELTA, error))
                .identityResolvedCount(longValue(
                        item, KEY_IDENTITY_COUNT, 0L, 0L, MAX_COUNTER_DELTA, error))
                .configRevision(revisionValue(
                        item, BridgeContract.EXTRA_CONFIG_REVISION,
                        outerConfigRevision, error))
                .policyRevision(revisionValue(
                        item, KEY_POLICY_REVISION, outerPolicyRevision, error))
                .lastError(stringValue(
                        item, BridgeContract.EXTRA_ERROR, "",
                        DiagnosticsValues.MAX_ERROR_LENGTH, true, StringKind.TEXT, error))
                .updatedAt(timestampValue(
                        item, KEY_UPDATED_AT, now, false, now, error))
                .build();
        if (!value.isStorable() || !value.matchesBatchIdentity(identity)) {
            throw invalid(error);
        }
        return value;
    }

    private static void parseLegacyFeatureDeltas(
            Bundle extras,
            ArrayList<String> features,
            DiagnosticsWriteBatch.Builder out,
            long now) {
        Set<String> seen = new HashSet<>();
        for (String featureId : features) {
            if (!DiagnosticsValues.isStrictId(featureId, false) || !seen.add(featureId)) {
                throw invalid("invalid_legacy_delta");
            }
            Object rawFeature = extras.get(FEATURE_PREFIX + featureId);
            if (!(rawFeature instanceof Bundle)) throw invalid("invalid_legacy_delta");
            Bundle feature = (Bundle) rawFeature;
            requireBundleBounds(feature, MAX_WIRE_ITEM_BYTES, MAX_WIRE_ITEM_KEYS,
                    "wire_item_too_large");
            requireLegacyDeltaKeys(feature);
            int observed = intValue(
                    feature, "observed_delta", 0, 0,
                    (int) MAX_COUNTER_DELTA, "invalid_legacy_delta");
            int blocked = intValue(
                    feature, "blocked_delta", 0, 0,
                    (int) MAX_COUNTER_DELTA, "invalid_legacy_delta");
            int failOpen = intValue(
                    feature, "fail_open_delta", 0, 0,
                    (int) MAX_COUNTER_DELTA, "invalid_legacy_delta");
            DiagnosticsWriteBatch.LegacyFeatureDelta delta =
                    new DiagnosticsWriteBatch.LegacyFeatureDelta(
                            featureId,
                            observed,
                            blocked,
                            failOpen,
                            timestampValue(
                                    feature, "last_at", 0L, true, now,
                                    "invalid_legacy_delta"),
                            stringValue(
                                    feature, "last_pkg", "",
                                    DiagnosticsValues.MAX_PACKAGE_LENGTH, true,
                                    StringKind.PACKAGE, "invalid_legacy_delta"),
                            stringValue(
                                    feature, "last_process", "",
                                    DiagnosticsValues.MAX_ID_LENGTH, true,
                                    StringKind.ID, "invalid_legacy_delta"),
                            stringValue(
                                    feature, "last_scene", "",
                                    DiagnosticsValues.MAX_TEXT_LENGTH, true,
                                    StringKind.TEXT, "invalid_legacy_delta"),
                            stringValue(
                                    feature, "last_reason", "",
                                    DiagnosticsValues.MAX_TEXT_LENGTH, true,
                                    StringKind.TEXT, "invalid_legacy_delta"),
                            stringValue(
                                    feature, "last_result", "",
                                    DiagnosticsValues.MAX_ID_LENGTH, true,
                                    StringKind.ID, "invalid_legacy_delta"));
            if (!delta.isStorable()) throw invalid("invalid_legacy_delta");
            out.addLegacyFeatureDelta(delta);
        }
    }

    private static void requireMatchingIdentity(
            Bundle item,
            DiagnosticsWriteBatch.BatchIdentity identity,
            String errorCode) {
        String bootId = stringValue(
                item, KEY_BOOT_ID, identity.bootId,
                DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, errorCode);
        String processId = stringValue(
                item, BridgeContract.EXTRA_PROCESS_ID, identity.processId,
                DiagnosticsValues.MAX_ID_LENGTH, false, StringKind.ID, errorCode);
        if (!identity.bootId.equals(bootId) || !identity.processId.equals(processId)) {
            throw invalid("batch_identity_mismatch");
        }
    }

    private static int claimedWireItemCount(Bundle extras) {
        long total = collectionSize(extras, KEY_RAW_HITS)
                + collectionSize(extras, KEY_EVENTS)
                + collectionSize(extras, KEY_HOOK_STATES)
                + collectionSize(extras, BridgeContract.EXTRA_FEATURES);
        if (total <= 0L) return 1;
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private static long collectionSize(Bundle extras, String key) {
        if (!extras.containsKey(key)) return 0L;
        Object value = extras.get(key);
        return value instanceof ArrayList<?> ? ((ArrayList<?>) value).size() : 1L;
    }

    @SafeVarargs
    private static int checkedTotalSize(ArrayList<Bundle>... lists) {
        long total = 0L;
        for (ArrayList<Bundle> list : lists) total += list.size();
        if (total > MAX_WIRE_ITEMS) {
            throw invalid("too_many_wire_items", (int) Math.min(total, Integer.MAX_VALUE));
        }
        return (int) total;
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Bundle> bundleList(Bundle extras, String key) {
        if (!extras.containsKey(key)) return new ArrayList<>();
        Object raw = extras.get(key);
        if (!(raw instanceof ArrayList<?>)) throw invalid("invalid_typed_items");
        ArrayList<?> values = (ArrayList<?>) raw;
        if (values.size() > MAX_WIRE_ITEMS) {
            throw invalid("too_many_wire_items", values.size());
        }
        ArrayList<Bundle> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Bundle)) throw invalid("invalid_typed_items", values.size());
            result.add((Bundle) value);
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<String> stringList(
            Bundle extras, String key, String errorCode) {
        if (!extras.containsKey(key)) throw invalid(errorCode);
        Object raw = extras.get(key);
        if (!(raw instanceof ArrayList<?>)) throw invalid(errorCode);
        ArrayList<?> values = (ArrayList<?>) raw;
        if (values.size() > MAX_WIRE_ITEMS) {
            throw invalid("too_many_wire_items", values.size());
        }
        ArrayList<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String)) throw invalid(errorCode, values.size());
            result.add((String) value);
        }
        return result;
    }

    private static void requireBundleBounds(
            Bundle value, int maxBytes, int maxKeys, String sizeError) {
        if (value == null) throw invalid("invalid_provider_payload");
        if (value.size() > maxKeys) throw invalid("too_many_bundle_fields");
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(value);
            if (parcel.dataSize() > maxBytes) throw invalid(sizeError);
        } finally {
            parcel.recycle();
        }
    }

    private static boolean hasTypedPayload(Bundle extras) {
        return extras.containsKey(KEY_RAW_HITS)
                || extras.containsKey(KEY_EVENTS)
                || extras.containsKey(KEY_HOOK_STATES);
    }

    private static boolean hasLegacyPayload(Bundle extras) {
        if (extras.containsKey(BridgeContract.EXTRA_FEATURES)) return true;
        for (String key : extras.keySet()) {
            if (key != null && key.startsWith(FEATURE_PREFIX)) return true;
        }
        return false;
    }

    private static void requireTypedOuterKeys(Bundle extras) {
        for (String key : extras.keySet()) {
            if (!isIdentityKey(key)
                    && !BridgeContract.EXTRA_CONFIG_REVISION.equals(key)
                    && !KEY_POLICY_REVISION.equals(key)
                    && !KEY_RAW_HITS.equals(key)
                    && !KEY_EVENTS.equals(key)
                    && !KEY_HOOK_STATES.equals(key)) {
                throw invalid("unknown_wire_field");
            }
        }
    }

    private static void requireLegacyOuterKeys(
            Bundle extras, ArrayList<String> features) {
        Set<String> featureKeys = new HashSet<>();
        for (String feature : features) featureKeys.add(FEATURE_PREFIX + feature);
        for (String key : extras.keySet()) {
            if (!isIdentityKey(key)
                    && !BridgeContract.EXTRA_CONFIG_REVISION.equals(key)
                    && !KEY_POLICY_REVISION.equals(key)
                    && !BridgeContract.EXTRA_FEATURES.equals(key)
                    && !featureKeys.contains(key)) {
                throw invalid("unknown_wire_field");
            }
        }
    }

    private static boolean isIdentityKey(String key) {
        return KEY_WIRE_VERSION.equals(key)
                || KEY_COUNTING_MODE.equals(key)
                || KEY_BOOT_ID.equals(key)
                || BridgeContract.EXTRA_PROCESS_ID.equals(key)
                || KEY_SENDER_INSTANCE_ID.equals(key)
                || KEY_EPOCH.equals(key)
                || KEY_BATCH_SEQUENCE.equals(key)
                || KEY_PAYLOAD_HASH.equals(key);
    }

    private static void requireRawHitKeys(Bundle item) {
        for (String key : item.keySet()) {
            if (!BridgeContract.EXTRA_TIMESTAMP.equals(key)
                    && !KEY_BOOT_ID.equals(key)
                    && !BridgeContract.EXTRA_PROCESS_ID.equals(key)
                    && !BridgeContract.EXTRA_FEATURE.equals(key)
                    && !KEY_HOOK_TARGET.equals(key)
                    && !BridgeContract.EXTRA_CONFIG_REVISION.equals(key)
                    && !KEY_POLICY_REVISION.equals(key)
                    && !KEY_PARSED.equals(key)
                    && !KEY_IDENTITY_RESOLVED.equals(key)
                    && !BridgeContract.EXTRA_ERROR.equals(key)) {
                throw invalid("invalid_raw_hit");
            }
        }
    }

    private static void requireEventKeys(Bundle item) {
        for (String key : item.keySet()) {
            if (!BridgeContract.EXTRA_TIMESTAMP.equals(key)
                    && !KEY_BOOT_ID.equals(key)
                    && !BridgeContract.EXTRA_PROCESS_ID.equals(key)
                    && !BridgeContract.EXTRA_FEATURE.equals(key)
                    && !KEY_HOOK_TARGET.equals(key)
                    && !KEY_BEHAVIOR.equals(key)
                    && !KEY_SOURCE.equals(key)
                    && !BridgeContract.EXTRA_PACKAGE.equals(key)
                    && !BridgeContract.EXTRA_UID.equals(key)
                    && !BridgeContract.EXTRA_SCENE.equals(key)
                    && !BridgeContract.EXTRA_CODE.equals(key)
                    && !BridgeContract.EXTRA_CONFIG_REVISION.equals(key)
                    && !KEY_POLICY_REVISION.equals(key)
                    && !KEY_MODE.equals(key)
                    && !KEY_DECISION.equals(key)
                    && !BridgeContract.EXTRA_RESULT.equals(key)
                    && !KEY_FAIL_OPEN_REASON.equals(key)
                    && !KEY_LATENCY_MICROS.equals(key)
                    && !KEY_RAW_HIT_COUNT.equals(key)
                    && !KEY_IDENTITY_RESOLVED.equals(key)
                    && !KEY_EVENT_TOKEN.equals(key)) {
                throw invalid("invalid_event");
            }
        }
    }

    private static void requireHookStateKeys(Bundle item) {
        for (String key : item.keySet()) {
            if (!KEY_BOOT_ID.equals(key)
                    && !BridgeContract.EXTRA_PROCESS_ID.equals(key)
                    && !BridgeContract.EXTRA_FEATURE.equals(key)
                    && !KEY_HOOK_TARGET.equals(key)
                    && !KEY_INSTALL_STATUS.equals(key)
                    && !BridgeContract.EXTRA_STATUS.equals(key)
                    && !KEY_LAST_HOOK_AT.equals(key)
                    && !KEY_LAST_EVENT_AT.equals(key)
                    && !KEY_RAW_COUNT.equals(key)
                    && !KEY_PARSED_COUNT.equals(key)
                    && !KEY_IDENTITY_COUNT.equals(key)
                    && !BridgeContract.EXTRA_CONFIG_REVISION.equals(key)
                    && !KEY_POLICY_REVISION.equals(key)
                    && !BridgeContract.EXTRA_ERROR.equals(key)
                    && !KEY_UPDATED_AT.equals(key)) {
                throw invalid("invalid_hook_state");
            }
        }
    }

    private static void requireLegacyDeltaKeys(Bundle feature) {
        for (String key : feature.keySet()) {
            if (!"observed_delta".equals(key)
                    && !"blocked_delta".equals(key)
                    && !"fail_open_delta".equals(key)
                    && !"last_at".equals(key)
                    && !"last_pkg".equals(key)
                    && !"last_process".equals(key)
                    && !"last_scene".equals(key)
                    && !"last_reason".equals(key)
                    && !"last_result".equals(key)) {
                throw invalid("invalid_legacy_delta");
            }
        }
    }

    private static void requireLegacyEventKeys(Bundle extras) {
        for (String key : extras.keySet()) {
            if (!isIdentityKey(key)
                    && !BridgeContract.EXTRA_FEATURE.equals(key)
                    && !BridgeContract.EXTRA_RESULT.equals(key)
                    && !BridgeContract.EXTRA_TIMESTAMP.equals(key)
                    && !BridgeContract.EXTRA_PACKAGE.equals(key)
                    && !BridgeContract.EXTRA_PROCESS.equals(key)
                    && !BridgeContract.EXTRA_SCENE.equals(key)
                    && !BridgeContract.EXTRA_CODE.equals(key)) {
                throw invalid("invalid_legacy_event");
            }
        }
    }

    private static int requiredInt(Bundle values, String key, String errorCode) {
        if (!values.containsKey(key)) throw invalid(errorCode);
        Object raw = values.get(key);
        if (!(raw instanceof Integer)) throw invalid(errorCode);
        return (Integer) raw;
    }

    private static long requiredLong(Bundle values, String key, String errorCode) {
        if (!values.containsKey(key)) throw invalid(errorCode);
        Object raw = values.get(key);
        if (!(raw instanceof Long)) throw invalid(errorCode);
        return (Long) raw;
    }

    private static int intValue(
            Bundle values,
            String key,
            int fallback,
            int minimum,
            int maximum,
            String errorCode) {
        if (!values.containsKey(key)) return fallback;
        Object raw = values.get(key);
        if (!(raw instanceof Integer)) throw invalid(errorCode);
        int value = (Integer) raw;
        if (value < minimum || value > maximum) throw invalid(errorCode);
        return value;
    }

    private static long longValue(
            Bundle values,
            String key,
            long fallback,
            long minimum,
            long maximum,
            String errorCode) {
        if (!values.containsKey(key)) return fallback;
        Object raw = values.get(key);
        if (!(raw instanceof Long)) throw invalid(errorCode);
        long value = (Long) raw;
        if (value < minimum || value > maximum) throw invalid(errorCode);
        return value;
    }

    private static boolean booleanValue(
            Bundle values, String key, boolean fallback, String errorCode) {
        if (!values.containsKey(key)) return fallback;
        Object raw = values.get(key);
        if (!(raw instanceof Boolean)) throw invalid(errorCode);
        return (Boolean) raw;
    }

    private static int revisionValue(
            Bundle values, String key, int fallback, String errorCode) {
        return intValue(values, key, fallback, -1, Integer.MAX_VALUE, errorCode);
    }

    private static long timestampValue(
            Bundle values,
            String key,
            long fallback,
            boolean allowZero,
            long now,
            String errorCode) {
        long value = longValue(values, key, fallback, 0L, Long.MAX_VALUE, errorCode);
        if (allowZero && value == 0L) return 0L;
        long maximum = now > Long.MAX_VALUE - TIMESTAMP_FUTURE_TOLERANCE_MILLIS
                ? Long.MAX_VALUE : now + TIMESTAMP_FUTURE_TOLERANCE_MILLIS;
        if (value <= 0L || value > maximum) throw invalid(errorCode);
        return value;
    }

    private static String stringValue(
            Bundle values,
            String key,
            String fallback,
            int maxLength,
            boolean allowEmpty,
            StringKind kind,
            String errorCode) {
        if (!values.containsKey(key)) {
            if (fallback == null) throw invalid(errorCode);
            return fallback;
        }
        Object raw = values.get(key);
        if (!(raw instanceof String)) throw invalid(errorCode);
        String value = (String) raw;
        boolean valid;
        if (kind == StringKind.ID) {
            valid = maxLength == DiagnosticsValues.MAX_ID_LENGTH
                    && DiagnosticsValues.isStrictId(value, allowEmpty);
        } else if (kind == StringKind.PACKAGE) {
            valid = maxLength == DiagnosticsValues.MAX_PACKAGE_LENGTH
                    && DiagnosticsValues.isStrictPackageName(value, allowEmpty);
        } else {
            valid = DiagnosticsValues.isStrictText(value, maxLength, allowEmpty);
        }
        if (!valid) throw invalid(errorCode);
        return value;
    }

    private enum StringKind { ID, PACKAGE, TEXT }

    private static InvalidPayload invalid(String errorCode) {
        return new InvalidPayload(errorCode, 1);
    }

    private static InvalidPayload invalid(String errorCode, int rejectedItems) {
        return new InvalidPayload(errorCode, rejectedItems);
    }

    private static final class InvalidPayload extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final String errorCode;
        final int rejectedItems;

        InvalidPayload(String errorCode, int rejectedItems) {
            this.errorCode = errorCode;
            this.rejectedItems = Math.max(1, rejectedItems);
        }
    }

    public static final class ParseResult {
        public final DiagnosticsWriteBatch batch;
        public final int acceptedItems;
        public final int droppedItems;
        public final String errorCode;

        ParseResult(
                DiagnosticsWriteBatch batch,
                int acceptedItems,
                int droppedItems,
                String errorCode) {
            this.batch = batch == null ? DiagnosticsWriteBatch.builder().build() : batch;
            this.acceptedItems = Math.max(0, acceptedItems);
            this.droppedItems = Math.max(0, droppedItems);
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        static ParseResult failed(String errorCode, int rejectedItems) {
            return new ParseResult(
                    DiagnosticsWriteBatch.builder().build(),
                    0,
                    Math.max(1, rejectedItems),
                    errorCode);
        }

        public boolean hasData() { return !batch.isEmpty(); }

        public boolean accepted() { return errorCode.isEmpty(); }
    }
}
