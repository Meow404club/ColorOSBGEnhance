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
                1, 3, 200L, 300L, false, false, false,
                Collections.singletonList(event), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                DiagnosticsQuery.HomeStatsSnapshot.empty(""), "");

        DiagnosticsExportCodec.EncodeResult encoded =
                DiagnosticsExportCodec.encode(snapshot);
        JSONObject root = new JSONObject(encoded.json);

        assertTrue(encoded.successful());
        assertEquals("bgenhance-residency-diagnostics", root.getString("format"));
        assertEquals(1, root.getInt("formatVersion"));
        assertEquals(3, root.getInt("databaseSchemaVersion"));
        assertEquals(1, root.getJSONArray("recentEvents").length());
        assertEquals(0, root.getJSONArray("rawHooks").length());
        assertFalse(root.getJSONObject("privacy").getBoolean("packageNamesIncluded"));
        assertFalse(root.getJSONObject("privacy").getBoolean("sensitiveDetailsIncluded"));
    }

    @Test
    public void nullSnapshotReturnsValidFailOpenDocument() throws Exception {
        DiagnosticsExportCodec.EncodeResult encoded = DiagnosticsExportCodec.encode(null);

        assertFalse(encoded.successful());
        assertEquals("missing_snapshot", new JSONObject(encoded.json).getString("errorCode"));
    }
}
