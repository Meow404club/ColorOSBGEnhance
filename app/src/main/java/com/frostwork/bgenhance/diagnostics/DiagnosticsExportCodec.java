package com.frostwork.bgenhance.diagnostics;

import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned JSON encoder for an already privacy-filtered export snapshot. */
public final class DiagnosticsExportCodec {
    private DiagnosticsExportCodec() {}

    public static EncodeResult encode(DiagnosticsQuery.ExportSnapshot snapshot) {
        if (snapshot == null) return EncodeResult.failed("missing_snapshot");
        try {
            JSONObject root = new JSONObject();
            root.put("format", "bgenhance-residency-diagnostics");
            root.put("formatVersion", snapshot.formatVersion);
            root.put("databaseSchemaVersion", snapshot.databaseSchemaVersion);
            root.put("generatedAt", snapshot.generatedAt);
            root.put("retentionMillis", snapshot.retentionMillis);
            JSONObject privacy = new JSONObject();
            privacy.put("packageNamesIncluded", snapshot.packageNamesIncluded);
            privacy.put("uidsIncluded", snapshot.uidsIncluded);
            privacy.put("sensitiveDetailsIncluded", snapshot.sensitiveDetailsIncluded);
            root.put("privacy", privacy);
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
                    + "\"formatVersion\":1,\"errorCode\":\""
                    + DiagnosticsValues.id(errorCode) + "\"}", errorCode);
        }

        public boolean successful() { return errorCode.isEmpty(); }
    }
}
