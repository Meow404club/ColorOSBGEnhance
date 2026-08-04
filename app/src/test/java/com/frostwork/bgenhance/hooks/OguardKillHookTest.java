package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OguardKillHookTest {
    @Test
    public void requiresBothVerifiedKillEntrypoints() {
        assertEquals("UNSUPPORTED_CLASS", OguardKillHook.status(-1));
        assertEquals("UNSUPPORTED_SIGNATURE", OguardKillHook.status(0));
        assertEquals(
                "UNSUPPORTED_SIGNATURE:EXPECTED=2:FOUND=1",
                OguardKillHook.status(1));
        assertEquals("INSTALLED:2", OguardKillHook.status(2));
        assertEquals(
                "UNSUPPORTED_SIGNATURE:EXPECTED=2:FOUND=3",
                OguardKillHook.status(3));
    }
}
