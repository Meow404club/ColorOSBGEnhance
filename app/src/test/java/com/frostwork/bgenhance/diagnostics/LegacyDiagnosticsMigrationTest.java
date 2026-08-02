package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;
import com.frostwork.bgenhance.FeatureIds;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class LegacyDiagnosticsMigrationTest {
    @Test
    public void readsFeatureTotalsWithoutImportingBootScopedStatus() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences(
                LegacyDiagnosticsMigration.PREFERENCES_NAME, Context.MODE_PRIVATE);
        String prefix = "stats." + FeatureIds.HANS + ".";
        preferences.edit()
                .clear()
                .putInt(prefix + "observed", 9)
                .putInt(prefix + "blocked", 4)
                .putInt(prefix + "fail_open", 2)
                .putLong(prefix + "last_at", 1_700_000_000_000L)
                .putString(prefix + "last_pkg", "com.example.target")
                .putString(prefix + "last_process", "system_server")
                .putString(prefix + "last_scene", "legacy scene")
                .putString(prefix + "last_reason", "7")
                .putString(prefix + "last_result", "blocked")
                .putString("support.hans_freeze_dispatch.status", "INSTALLED:1")
                .putString("process.system_server.stage", "CONFIG_READY")
                .commit();

        DiagnosticsWriteBatch batch = LegacyDiagnosticsMigration.read(context);

        assertNull(batch.batchIdentity);
        assertEquals(DiagnosticsWriteBatch.CountingMode.LEGACY, batch.countingMode);
        assertEquals(1, batch.legacyFeatureDeltas.size());
        assertEquals(0, batch.hookStates.size());
        DiagnosticsWriteBatch.LegacyFeatureDelta value = batch.legacyFeatureDeltas.get(0);
        assertEquals(FeatureIds.HANS, value.featureId);
        assertEquals(9L, value.observedDelta);
        assertEquals(4L, value.blockedDelta);
        assertEquals(2L, value.failOpenDelta);
        assertEquals(1_700_000_000_000L, value.lastAt);
        assertEquals("com.example.target", value.lastPackage);
        assertEquals("legacy scene", value.lastScene);
    }

    @Test
    public void inconsistentAndUnexpectedValuesAreBoundedFailOpen() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences(
                LegacyDiagnosticsMigration.PREFERENCES_NAME, Context.MODE_PRIVATE);
        String prefix = "stats." + FeatureIds.HANS_WAKE + ".";
        preferences.edit()
                .clear()
                .putLong(prefix + "observed", 3L)
                .putLong(prefix + "blocked", 9L)
                .putLong(prefix + "fail_open", 9L)
                .putBoolean(prefix + "last_pkg", true)
                .commit();

        DiagnosticsWriteBatch.LegacyFeatureDelta value =
                LegacyDiagnosticsMigration.read(context).legacyFeatureDeltas.get(0);

        assertEquals(3L, value.observedDelta);
        assertEquals(3L, value.blockedDelta);
        assertEquals(0L, value.failOpenDelta);
        assertEquals("", value.lastPackage);
    }
}
