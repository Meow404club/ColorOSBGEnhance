package com.frostwork.bgenhance.diagnostics;

import android.os.Bundle;
import com.frostwork.bgenhance.BridgeContract;
import java.util.ArrayList;

/** Encodes one typed diagnostics batch using the exact bounded Provider wire contract. */
public final class DiagnosticsWireEncoder {
    private DiagnosticsWireEncoder() {}

    public static Bundle encodeTyped(DiagnosticsWriteBatch batch) {
        if (batch == null || batch.batchIdentity == null) {
            throw new IllegalArgumentException("versioned diagnostics batch required");
        }
        if (batch.countingMode != DiagnosticsWriteBatch.CountingMode.TYPED) {
            throw new IllegalArgumentException("typed diagnostics batch required");
        }
        if (batch.batchIdentity.wireVersion != DiagnosticsWireParser.WIRE_VERSION) {
            throw new IllegalArgumentException("unsupported diagnostics wire version");
        }
        if (batch.isEmpty()
                || batch.size() > DiagnosticsWireParser.MAX_WIRE_ITEMS
                || !batch.legacyFeatureDeltas.isEmpty()) {
            throw new IllegalArgumentException("invalid diagnostics batch size");
        }

        DiagnosticsWriteBatch.BatchIdentity identity = batch.batchIdentity;
        Bundle out = new Bundle();
        out.putInt(DiagnosticsWireParser.KEY_WIRE_VERSION, identity.wireVersion);
        out.putString(DiagnosticsWireParser.KEY_COUNTING_MODE, batch.countingMode.wireValue);
        out.putString(DiagnosticsWireParser.KEY_BOOT_ID, identity.bootId);
        out.putString(BridgeContract.EXTRA_PROCESS_ID, identity.processId);
        out.putString(
                DiagnosticsWireParser.KEY_SENDER_INSTANCE_ID, identity.senderInstanceId);
        out.putLong(DiagnosticsWireParser.KEY_EPOCH, identity.epoch);
        out.putLong(DiagnosticsWireParser.KEY_BATCH_SEQUENCE, identity.batchSequence);
        out.putInt(BridgeContract.EXTRA_CONFIG_REVISION, -1);
        out.putInt(DiagnosticsWireParser.KEY_POLICY_REVISION, -1);

        if (!batch.rawHits.isEmpty()) {
            ArrayList<Bundle> values = new ArrayList<>(batch.rawHits.size());
            for (RawHookHit hit : batch.rawHits) values.add(rawHit(hit));
            out.putParcelableArrayList(DiagnosticsWireParser.KEY_RAW_HITS, values);
        }
        if (!batch.events.isEmpty()) {
            ArrayList<Bundle> values = new ArrayList<>(batch.events.size());
            for (NormalizedDiagnosticsEvent event : batch.events) values.add(event(event));
            out.putParcelableArrayList(DiagnosticsWireParser.KEY_EVENTS, values);
        }
        if (!batch.hookStates.isEmpty()) {
            ArrayList<Bundle> values = new ArrayList<>(batch.hookStates.size());
            for (DiagnosticsHookState state : batch.hookStates) values.add(hookState(state));
            out.putParcelableArrayList(DiagnosticsWireParser.KEY_HOOK_STATES, values);
        }
        String payloadHash = DiagnosticsPayloadFingerprint.compute(out);
        if (!identity.payloadHash.isEmpty() && !identity.payloadHash.equals(payloadHash)) {
            throw new IllegalArgumentException("diagnostics payload hash mismatch");
        }
        out.putString(DiagnosticsWireParser.KEY_PAYLOAD_HASH, payloadHash);
        return out;
    }

    private static Bundle rawHit(RawHookHit hit) {
        Bundle out = new Bundle();
        out.putLong(BridgeContract.EXTRA_TIMESTAMP, hit.timestampMillis);
        out.putString(BridgeContract.EXTRA_FEATURE, hit.featureId);
        out.putString(DiagnosticsWireParser.KEY_HOOK_TARGET, hit.hookTargetId);
        out.putInt(BridgeContract.EXTRA_CONFIG_REVISION, hit.configRevision);
        out.putInt(DiagnosticsWireParser.KEY_POLICY_REVISION, hit.policyRevision);
        out.putBoolean(DiagnosticsWireParser.KEY_PARSED, hit.parsed);
        out.putBoolean(DiagnosticsWireParser.KEY_IDENTITY_RESOLVED, hit.identityResolved);
        out.putString(BridgeContract.EXTRA_ERROR, hit.errorCode);
        return out;
    }

    private static Bundle event(NormalizedDiagnosticsEvent event) {
        Bundle out = new Bundle();
        out.putLong(BridgeContract.EXTRA_TIMESTAMP, event.timestampMillis);
        out.putString(BridgeContract.EXTRA_FEATURE, event.featureId);
        out.putString(DiagnosticsWireParser.KEY_HOOK_TARGET, event.hookTargetId);
        out.putString(DiagnosticsWireParser.KEY_BEHAVIOR, event.behavior);
        out.putString(DiagnosticsWireParser.KEY_SOURCE, event.source);
        out.putString(BridgeContract.EXTRA_PACKAGE, event.packageName);
        out.putInt(BridgeContract.EXTRA_UID, event.uid);
        out.putString(BridgeContract.EXTRA_SCENE, event.scene);
        out.putInt(BridgeContract.EXTRA_CODE, event.reasonCode);
        out.putInt(BridgeContract.EXTRA_CONFIG_REVISION, event.configRevision);
        out.putInt(DiagnosticsWireParser.KEY_POLICY_REVISION, event.policyRevision);
        out.putString(DiagnosticsWireParser.KEY_MODE, event.mode);
        out.putString(DiagnosticsWireParser.KEY_DECISION, event.decision);
        out.putString(BridgeContract.EXTRA_RESULT, event.result);
        out.putString(DiagnosticsWireParser.KEY_FAIL_OPEN_REASON, event.failOpenReason);
        out.putLong(DiagnosticsWireParser.KEY_LATENCY_MICROS, event.latencyMicros);
        out.putInt(DiagnosticsWireParser.KEY_RAW_HIT_COUNT, event.rawHitCount);
        out.putBoolean(DiagnosticsWireParser.KEY_IDENTITY_RESOLVED, event.identityResolved);
        out.putString(DiagnosticsWireParser.KEY_EVENT_TOKEN, event.eventToken);
        return out;
    }

    private static Bundle hookState(DiagnosticsHookState state) {
        Bundle out = new Bundle();
        out.putString(BridgeContract.EXTRA_FEATURE, state.featureId);
        out.putString(DiagnosticsWireParser.KEY_HOOK_TARGET, state.hookTargetId);
        out.putString(DiagnosticsWireParser.KEY_INSTALL_STATUS, state.installStatus);
        out.putLong(DiagnosticsWireParser.KEY_LAST_HOOK_AT, state.lastHookAt);
        out.putLong(DiagnosticsWireParser.KEY_LAST_EVENT_AT, state.lastEventAt);
        out.putLong(DiagnosticsWireParser.KEY_RAW_COUNT, state.rawHitCount);
        out.putLong(DiagnosticsWireParser.KEY_PARSED_COUNT, state.parsedCount);
        out.putLong(DiagnosticsWireParser.KEY_IDENTITY_COUNT, state.identityResolvedCount);
        out.putInt(BridgeContract.EXTRA_CONFIG_REVISION, state.configRevision);
        out.putInt(DiagnosticsWireParser.KEY_POLICY_REVISION, state.policyRevision);
        out.putString(BridgeContract.EXTRA_ERROR, state.lastError);
        out.putLong(DiagnosticsWireParser.KEY_UPDATED_AT, state.updatedAt);
        return out;
    }
}
