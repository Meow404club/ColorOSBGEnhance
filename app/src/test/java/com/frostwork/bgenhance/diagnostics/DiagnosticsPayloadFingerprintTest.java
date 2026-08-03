package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DiagnosticsPayloadFingerprintTest {
    @Test
    public void canonicalFingerprintIgnoresBundleInsertionOrder() {
        Bundle first = new Bundle();
        first.putString("text", "value");
        first.putLong("number", 7L);
        Bundle second = new Bundle();
        second.putLong("number", 7L);
        second.putString("text", "value");

        assertEquals(
                DiagnosticsPayloadFingerprint.compute(first),
                DiagnosticsPayloadFingerprint.compute(second));
    }

    @Test
    public void fingerprintPreservesTypesAndListOrder() {
        Bundle integer = new Bundle();
        integer.putInt("number", 7);
        integer.putStringArrayList(
                "items", new ArrayList<>(Arrays.asList("a", "b")));
        Bundle longValue = new Bundle();
        longValue.putLong("number", 7L);
        longValue.putStringArrayList(
                "items", new ArrayList<>(Arrays.asList("a", "b")));
        Bundle reversed = new Bundle(integer);
        reversed.putStringArrayList(
                "items", new ArrayList<>(Arrays.asList("b", "a")));

        assertNotEquals(
                DiagnosticsPayloadFingerprint.compute(integer),
                DiagnosticsPayloadFingerprint.compute(longValue));
        assertNotEquals(
                DiagnosticsPayloadFingerprint.compute(integer),
                DiagnosticsPayloadFingerprint.compute(reversed));
    }

    @Test
    public void cyclicPayloadIsRejected() {
        Bundle payload = new Bundle();
        payload.putBundle("self", payload);

        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticsPayloadFingerprint.compute(payload));
    }
}
