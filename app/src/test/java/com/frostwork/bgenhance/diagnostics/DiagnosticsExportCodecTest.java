package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

public final class DiagnosticsExportCodecTest {
    @Test
    public void exportHasStableVersionAndLayeredCollections() throws Exception {
        NormalizedDiagnosticsEvent event = NormalizedDiagnosticsEvent.builder()
                .timestampMillis(100L)
                .processId("system_server")
                .featureId("freeze")
                .hookTargetId("freeze_target")
                .behavior("FREEZE")
                .decision("BLOCK_FREEZE")
                .result("blocked")
                .packageName("app-1")
                .uid(-1)
                .build();
        DiagnosticsQuery.ExportSnapshot snapshot = new DiagnosticsQuery.ExportSnapshot(
                2, 6, 200L, 300L, false, false, false,
                Collections.singletonList(event), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                DiagnosticsQuery.HomeStatsSnapshot.empty(""), "");

        DiagnosticsExportCodec.EncodeResult encoded =
                DiagnosticsExportCodec.encode(snapshot);
        JSONObject root = new JSONObject(encoded.json);

        assertTrue(encoded.successful());
        assertEquals("bgenhance-residency-diagnostics", root.getString("format"));
        assertEquals(2, root.getInt("formatVersion"));
        assertEquals(6, root.getInt("databaseSchemaVersion"));
        assertEquals("1970-01-01T00:00:00.200Z", root.getString("generatedAtUtc"));
        assertEquals(1, root.getJSONArray("recentEvents").length());
        assertEquals(0, root.getJSONArray("rawHooks").length());
        assertFalse(root.getJSONObject("privacy").getBoolean("packageNamesIncluded"));
        assertFalse(root.getJSONObject("privacy").getBoolean("sensitiveDetailsIncluded"));
        assertTrue(root.has("protocols"));
        assertTrue(root.has("exportStatus"));
    }

    @Test
    public void redactedReportMetadataOmitsDeviceAndBootIdentity() throws Exception {
        DiagnosticsQuery.ExportBootStatus boot = new DiagnosticsQuery.ExportBootStatus(
                true, false, "private-boot", 4, 1, 22, true, true, true);
        DiagnosticsQuery.ExportCollectionStatus complete =
                new DiagnosticsQuery.ExportCollectionStatus(10, 4, 1, false, "");
        DiagnosticsQuery.ExportStatus status = new DiagnosticsQuery.ExportStatus(
                complete, complete, complete, complete, 3, boot,
                new DiagnosticsQuery.BackendHealth(8L, 1L, 7L, 0L, 0, false));
        DiagnosticsQuery.ExportSnapshot snapshot = new DiagnosticsQuery.ExportSnapshot(
                2, 6, 200L, 300L, false, false, false,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                DiagnosticsQuery.HomeStatsSnapshot.empty(""), status, "");
        DiagnosticsReportMetadata metadata = new DiagnosticsReportMetadata(
                "com.frostwork.bgenhance", "1.0.0", 4L, 36,
                false, "private-maker", "private-model", "private-product",
                3, 3, true, 102, 9, 9, true,
                "", "private error details", true, 5);

        JSONObject root = new JSONObject(
                DiagnosticsExportCodec.encode(snapshot, metadata).json);

        assertEquals("1.0.0", root.getJSONObject("module").getString("versionName"));
        assertEquals(3, root.getJSONObject("protocols")
                .getInt("diagnosticsBridgeVersion"));
        assertEquals(102, root.getJSONObject("protocols")
                .getJSONObject("xposed").getInt("targetApi"));
        assertFalse(root.getJSONObject("privacy").getBoolean("deviceDetailsIncluded"));
        assertFalse(root.getJSONObject("system").has("model"));
        assertFalse(root.getJSONObject("system").getBoolean("bootCountAvailable"));
        assertFalse(root.getJSONObject("system").has("bootCount"));
        assertFalse(root.getJSONObject("currentBoot").has("id"));
        assertTrue(root.getJSONObject("currentBoot").getBoolean("systemServerReported"));
        assertTrue(root.getJSONObject("currentBoot").getBoolean("athenaReported"));
        assertFalse(root.getJSONObject("runtime").has("configSyncErrorDetails"));
        assertTrue(root.getJSONObject("exportStatus").getBoolean("complete"));
        assertEquals(8L, root.getJSONObject("exportStatus")
                .getJSONObject("backend").getLong("acceptedWrites"));
    }

    @Test
    public void fullReportMetadataIncludesExplicitSensitiveDetailsAndTruncation()
            throws Exception {
        DiagnosticsQuery.ExportBootStatus boot = new DiagnosticsQuery.ExportBootStatus(
                true, true, "full-boot-id", 2, 0, 22, true, false, false);
        DiagnosticsQuery.ExportCollectionStatus truncated =
                new DiagnosticsQuery.ExportCollectionStatus(2, 2, 1, true, "");
        DiagnosticsQuery.ExportCollectionStatus complete =
                new DiagnosticsQuery.ExportCollectionStatus(10, 1, 1, false, "");
        DiagnosticsQuery.ExportStatus status = new DiagnosticsQuery.ExportStatus(
                truncated, complete, complete, complete, 1, boot, null);
        DiagnosticsQuery.ExportSnapshot snapshot = new DiagnosticsQuery.ExportSnapshot(
                2, 6, 200L, 300L, true, true, true,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                DiagnosticsQuery.HomeStatsSnapshot.empty(""), status, "");
        DiagnosticsReportMetadata metadata = new DiagnosticsReportMetadata(
                "com.frostwork.bgenhance", "1.0.0", 4L, 36,
                true, "maker", "model", "product", 3, 3,
                true, 102, 9, 9, true,
                "sync_error", "private sync error", true, 5);

        JSONObject root = new JSONObject(
                DiagnosticsExportCodec.encode(snapshot, metadata).json);

        assertTrue(root.getJSONObject("privacy").getBoolean("deviceDetailsIncluded"));
        assertEquals("model", root.getJSONObject("system").getString("model"));
        assertEquals(5, root.getJSONObject("system").getInt("bootCount"));
        assertEquals("full-boot-id", root.getJSONObject("currentBoot").getString("id"));
        assertEquals("private sync error", root.getJSONObject("runtime")
                .getString("configSyncErrorDetails"));
        assertTrue(root.getJSONObject("exportStatus").getBoolean("truncated"));
        assertFalse(root.getJSONObject("exportStatus").getBoolean("complete"));
        assertTrue(root.getJSONObject("exportStatus").getJSONObject("collections")
                .getJSONObject("recentEvents").getBoolean("hasMore"));
    }

    @Test
    public void nullSnapshotReturnsValidFailOpenDocument() throws Exception {
        DiagnosticsExportCodec.EncodeResult encoded = DiagnosticsExportCodec.encode(null);

        assertFalse(encoded.successful());
        assertEquals("missing_snapshot", new JSONObject(encoded.json).getString("errorCode"));
    }
}
