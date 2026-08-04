package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OguardForceStopHookTest {
    @Test
    public void requiresExactlyOneVerifiedForceStopEntrypoint() {
        assertEquals("UNSUPPORTED_CLASS", OguardForceStopHook.status(-1));
        assertEquals("UNSUPPORTED_SIGNATURE", OguardForceStopHook.status(0));
        assertEquals("INSTALLED:1", OguardForceStopHook.status(1));
        assertEquals(
                "UNSUPPORTED_SIGNATURE:EXPECTED=1:FOUND=2",
                OguardForceStopHook.status(2));
    }
}
