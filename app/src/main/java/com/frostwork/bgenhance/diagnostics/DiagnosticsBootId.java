package com.frostwork.bgenhance.diagnostics;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads the kernel boot identity once before diagnostics Provider traffic is accepted. */
public final class DiagnosticsBootId {
    private static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    private DiagnosticsBootId() {}

    public static String readCurrent() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(BOOT_ID_PATH), StandardCharsets.US_ASCII))) {
            String value = reader.readLine();
            return DiagnosticsValues.isStrictId(value, false) ? value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
