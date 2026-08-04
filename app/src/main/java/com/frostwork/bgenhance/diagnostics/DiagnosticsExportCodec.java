package com.frostwork.bgenhance.diagnostics;

import java.time.Instant;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned JSON encoder for an already privacy-filtered export snapshot. */
public final class DiagnosticsExportCodec {
    private DiagnosticsExportCodec() {}

    public static EncodeResult encode(DiagnosticsQuery.ExportSnapshot snapshot) {
        return encode(snapshot, DiagnosticsReportMetadata.empty());
    }

    public static EncodeResult encode(
            DiagnosticsQuery.ExportSnapshot snapshot,
            DiagnosticsReportMetadata requestedMetadata) {
        if (snapshot == null) return EncodeResult.failed("missing_snapshot");
        DiagnosticsReportMetadata metadata = requestedMetadata == null
                ? DiagnosticsReportMetadata.empty() : requestedMetadata;
        try {
            JSONObject root = new JSONObject();
            root.put("format", "bgenhance-residency-diagnostics");
            root.put("formatVersion", snapshot.formatVersion);
            root.put("databaseSchemaVersion", snapshot.databaseSchemaVersion);
            root.put("generatedAt", snapshot.generatedAt);
            root.put("generatedAtUtc", Instant.ofEpochMilli(snapshot.generatedAt).toString());
            root.put("retentionMillis", snapshot.retentionMillis);
            root.put("aggregateBucketMillis", DiagnosticsValues.DAY_MILLIS);
            root.put("retentionWindowApproximate", true);
            JSONObject privacy = new JSONObject();
            privacy.put("packageNamesIncluded", snapshot.packageNamesIncluded);
            privacy.put("uidsIncluded", snapshot.uidsIncluded);
            privacy.put("sensitiveDetailsIncluded", snapshot.sensitiveDetailsIncluded);
            privacy.put("deviceDetailsIncluded", metadata.deviceDetailsIncluded);
            root.put("privacy", privacy);
            root.put("module", module(metadata));
            root.put("protocols", protocols(snapshot, metadata));
            root.put("system", system(metadata));
            root.put("runtime", runtime(metadata));
            root.put("currentBoot", currentBoot(snapshot.exportStatus.currentBoot));
            root.put("exportStatus", exportStatus(snapshot.exportStatus));
            root.put("homeStats", homeStats(snapshot.homeStats));
            root.put("rawHooks", rawHooks(snapshot));
            root.put("hookStates", hookStates(snapshot));
            root.put("aggregates", aggregates(snapshot));
            root.put("recentEvents", events(snapshot));
            if (!snapshot.errorCode.isEmpty()) root.put("errorCode", snapshot.errorCode);
            return new EncodeResult(root.toString(), "");
        } catch (Throwable error) {
            return EncodeResult.failed("export_encoding_failed");
        }
    }

    private static JSONObject module(DiagnosticsReportMetadata metadata) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("packageName", metadata.modulePackageName);
        out.put("versionName", metadata.moduleVersionName);
        out.put("versionCode", metadata.moduleVersionCode);
        return out;
    }

    private static JSONObject protocols(
            DiagnosticsQuery.ExportSnapshot snapshot,
            DiagnosticsReportMetadata metadata) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("exportFormatVersion", snapshot.formatVersion);
        out.put("databaseSchemaVersion", snapshot.databaseSchemaVersion);
        out.put("diagnosticsBridgeVersion", metadata.bridgeVersion);
        out.put("diagnosticsWireVersion", metadata.wireVersion);
        JSONObject xposed = new JSONObject();
        xposed.put("mode", DiagnosticsReportMetadata.XPOSED_API_MODE);
        xposed.put("minimumApi", DiagnosticsReportMetadata.XPOSED_MIN_API);
        xposed.put("targetApi", DiagnosticsReportMetadata.XPOSED_TARGET_API);
        out.put("xposed", xposed);
        return out;
    }

    private static JSONObject system(DiagnosticsReportMetadata metadata) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("androidSdk", metadata.androidSdk);
        if (metadata.deviceDetailsIncluded) {
            out.put("manufacturer", metadata.deviceManufacturer);
            out.put("model", metadata.deviceModel);
            out.put("product", metadata.deviceProduct);
        }
        out.put("bootCountAvailable", metadata.bootCountAvailable);
        if (metadata.bootCountAvailable) out.put("bootCount", metadata.bootCount);
        return out;
    }

    private static JSONObject runtime(DiagnosticsReportMetadata metadata) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("xposedServiceConnected", metadata.xposedServiceConnected);
        out.put("xposedServiceApiVersion", metadata.xposedServiceApiVersion);
        out.put("localConfigRevision", metadata.localConfigRevision);
        out.put("syncedConfigRevision", metadata.syncedConfigRevision);
        out.put("configSynced", metadata.configSynced);
        out.put("configSyncErrorCode", metadata.configSyncErrorCode);
        if (!metadata.configSyncErrorDetails.isEmpty()) {
            out.put("configSyncErrorDetails", metadata.configSyncErrorDetails);
        }
        JSONObject configuration = new JSONObject();
        configuration.put("source", "remote_preferences");
        configuration.put("group", "config");
        configuration.put("hookReloadLatencyAvailable", false);
        out.put("configuration", configuration);
        return out;
    }

    private static JSONObject currentBoot(DiagnosticsQuery.ExportBootStatus status)
            throws JSONException {
        JSONObject out = new JSONObject();
        out.put("identityAvailable", status.identityAvailable);
        out.put("identityIncluded", status.identityIncluded);
        if (status.identityIncluded) out.put("id", status.currentBootId);
        out.put("stateAvailable", status.currentBootStateAvailable);
        out.put("hookStateCount", status.currentBootHookStateCount);
        out.put("otherBootHookStateCount", status.otherBootHookStateCount);
        out.put("expectedStateCount", status.expectedCurrentBootStateCount);
        out.put("systemServerReported", status.systemServerReported);
        out.put("athenaReported", status.athenaReported);
        out.put("hookStateCoverageComplete", status.hookStateCoverageComplete);
        return out;
    }

    private static JSONObject exportStatus(DiagnosticsQuery.ExportStatus status)
            throws JSONException {
        JSONObject out = new JSONObject();
        out.put("complete", !status.truncated && !status.hasErrors);
        out.put("truncated", status.truncated);
        out.put("hasErrors", status.hasErrors);
        out.put("featureStatsCount", status.featureStatsCount);
        JSONObject collections = new JSONObject();
        collections.put("recentEvents", collection(status.recentEvents));
        collections.put("aggregates", collection(status.aggregates));
        collections.put("rawHooks", collection(status.rawHooks));
        collections.put("hookStates", collection(status.hookStates));
        out.put("collections", collections);
        DiagnosticsQuery.BackendHealth health = status.backendHealth;
        JSONObject backend = new JSONObject();
        backend.put("acceptedWrites", health.acceptedWrites);
        backend.put("droppedWrites", health.droppedWrites);
        backend.put("committedWrites", health.committedWrites);
        backend.put("failedBatches", health.failedBatches);
        backend.put("queuedWrites", health.queuedWrites);
        backend.put("closed", health.closed);
        out.put("backend", backend);
        return out;
    }

    private static JSONObject collection(DiagnosticsQuery.ExportCollectionStatus status)
            throws JSONException {
        JSONObject out = new JSONObject();
        out.put("requestedLimit", status.requestedLimit);
        out.put("returnedCount", status.returnedCount);
        out.put("pagesRead", status.pagesRead);
        out.put("hasMore", status.hasMore);
        out.put("historyTruncated", status.historyTruncated);
        out.put("truncated", status.truncated);
        if (!status.errorCode.isEmpty()) out.put("errorCode", status.errorCode);
        return out;
    }

    private static JSONObject homeStats(DiagnosticsQuery.HomeStatsSnapshot snapshot)
            throws JSONException {
        JSONObject out = new JSONObject();
        out.put("observed", snapshot.observed);
        out.put("blocked", snapshot.blocked);
        out.put("failOpen", snapshot.failOpen);
        JSONArray features = new JSONArray();
        for (Map.Entry<String, DiagnosticsQuery.FeatureStats> entry
                : snapshot.features.entrySet()) {
            DiagnosticsQuery.FeatureStats value = entry.getValue();
            JSONObject item = new JSONObject();
            item.put("featureId", value.featureId);
            item.put("observed", value.observed);
            item.put("blocked", value.blocked);
            item.put("failOpen", value.failOpen);
            item.put("lastAt", value.lastAt);
            item.put("lastPackage", value.lastPackage);
            item.put("lastProcess", value.lastProcess);
            item.put("lastScene", value.lastScene);
            item.put("lastReason", value.lastReason);
            item.put("lastResult", value.lastResult);
            features.put(item);
        }
        out.put("features", features);
        return out;
    }

    private static JSONArray rawHooks(DiagnosticsQuery.ExportSnapshot snapshot)
            throws JSONException {
        JSONArray out = new JSONArray();
        for (DiagnosticsQuery.RawHookSummary value : snapshot.rawHooks) {
            JSONObject item = new JSONObject();
            item.put("processId", value.processId);
            item.put("featureId", value.featureId);
            item.put("hookTargetId", value.hookTargetId);
            item.put("hitCount", value.hitCount);
            item.put("parsedCount", value.parsedCount);
            item.put("identityResolvedCount", value.identityResolvedCount);
            item.put("lastHitAt", value.lastHitAt);
            item.put("lastError", value.lastError);
            out.put(item);
        }
        return out;
    }

    private static JSONArray hookStates(DiagnosticsQuery.ExportSnapshot snapshot)
            throws JSONException {
        JSONArray out = new JSONArray();
        for (DiagnosticsHookState value : snapshot.hookStates) {
            JSONObject item = new JSONObject();
            item.put("processId", value.processId);
            item.put("featureId", value.featureId);
            item.put("hookTargetId", value.hookTargetId);
            item.put("installStatus", value.installStatus);
            item.put("bootId", value.bootId);
            item.put("lastHookAt", value.lastHookAt);
            item.put("lastEventAt", value.lastEventAt);
            item.put("rawHitCount", value.rawHitCount);
            item.put("parsedCount", value.parsedCount);
            item.put("identityResolvedCount", value.identityResolvedCount);
            item.put("configRevision", value.configRevision);
            item.put("policyRevision", value.policyRevision);
            item.put("lastError", value.lastError);
            item.put("updatedAt", value.updatedAt);
            out.put(item);
        }
        return out;
    }

    private static JSONArray aggregates(DiagnosticsQuery.ExportSnapshot snapshot)
            throws JSONException {
        JSONArray out = new JSONArray();
        for (DiagnosticsQuery.AggregateRow value : snapshot.aggregates) {
            JSONObject item = new JSONObject();
            item.put("packageName", value.packageName);
            item.put("uid", value.uid);
            item.put("featureId", value.featureId);
            item.put("hookTargetId", value.hookTargetId);
            item.put("behavior", value.behavior);
            item.put("source", value.source);
            item.put("decision", value.decision);
            item.put("result", value.result);
            item.put("rawHitCount", value.rawHitCount);
            item.put("eventCount", value.eventCount);
            item.put("blockedCount", value.blockedCount);
            item.put("failOpenCount", value.failOpenCount);
            item.put("identityResolvedCount", value.identityResolvedCount);
            item.put("latencyMicrosTotal", value.latencyMicrosTotal);
            item.put("latencyMicrosMax", value.latencyMicrosMax);
            item.put("firstAt", value.firstAt);
            item.put("lastAt", value.lastAt);
            out.put(item);
        }
        return out;
    }

    private static JSONArray events(DiagnosticsQuery.ExportSnapshot snapshot)
            throws JSONException {
        JSONArray out = new JSONArray();
        for (NormalizedDiagnosticsEvent value : snapshot.recentEvents) {
            JSONObject item = new JSONObject();
            item.put("id", value.id);
            item.put("timestamp", value.timestampMillis);
            item.put("bootId", value.bootId);
            item.put("processId", value.processId);
            item.put("featureId", value.featureId);
            item.put("hookTargetId", value.hookTargetId);
            item.put("behavior", value.behavior);
            item.put("source", value.source);
            item.put("packageName", value.packageName);
            item.put("uid", value.uid);
            item.put("scene", value.scene);
            item.put("reasonCode", value.reasonCode);
            item.put("configRevision", value.configRevision);
            item.put("policyRevision", value.policyRevision);
            item.put("mode", value.mode);
            item.put("decision", value.decision);
            item.put("result", value.result);
            item.put("failOpenReason", value.failOpenReason);
            item.put("latencyMicros", value.latencyMicros);
            item.put("rawHitCount", value.rawHitCount);
            item.put("identityResolved", value.identityResolved);
            out.put(item);
        }
        return out;
    }

    public static final class EncodeResult {
        public final String json;
        public final String errorCode;

        EncodeResult(String json, String errorCode) {
            this.json = json == null ? "{}" : json;
            this.errorCode = DiagnosticsValues.id(errorCode);
        }

        static EncodeResult failed(String errorCode) {
            return new EncodeResult("{\"format\":\"bgenhance-residency-diagnostics\","
                    + "\"formatVersion\":2,\"errorCode\":\""
                    + DiagnosticsValues.id(errorCode) + "\"}", errorCode);
        }

        public boolean successful() { return errorCode.isEmpty(); }
    }
}
