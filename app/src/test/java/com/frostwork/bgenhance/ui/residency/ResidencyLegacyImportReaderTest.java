package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class ResidencyLegacyImportReaderTest {
    @Test
    public void readsUtf8WithoutReplacement() throws Exception {
        String json = "{\"name\":\"冻结策略\"}";
        assertEquals(json, ResidencyLegacyImportReader.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void rejectsMalformedUtf8() {
        ResidencyLegacyImportReader.ReadException error = assertThrows(
                ResidencyLegacyImportReader.ReadException.class,
                () -> ResidencyLegacyImportReader.read(
                        new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28})));
        assertEquals(ResidencyLegacyImportReader.Failure.INVALID_UTF8, error.failure());
    }

    @Test
    public void removesOnlyALeadingUtf8Bom() throws Exception {
        byte[] json = "\ufeff{\"value\":\"\ufeff\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"value\":\"\ufeff\"}", ResidencyLegacyImportReader.read(
                new ByteArrayInputStream(json)));
    }

    @Test
    public void acceptsExactLimitAndRejectsOneExtraByte() throws Exception {
        byte[] exact = new byte[ResidencyLegacyImportReader.MAX_BYTES];
        Arrays.fill(exact, (byte) ' ');
        assertEquals(exact.length, ResidencyLegacyImportReader.read(
                new ByteArrayInputStream(exact)).length());

        byte[] excessive = Arrays.copyOf(exact, exact.length + 1);
        ResidencyLegacyImportReader.ReadException error = assertThrows(
                ResidencyLegacyImportReader.ReadException.class,
                () -> ResidencyLegacyImportReader.read(new ByteArrayInputStream(excessive)));
        assertEquals(ResidencyLegacyImportReader.Failure.TOO_LARGE, error.failure());
    }
}
