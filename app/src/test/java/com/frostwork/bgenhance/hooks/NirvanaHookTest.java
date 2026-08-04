package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NirvanaHookTest {
    @Test
    public void requiresExactlyOneVerifiedSceneEntrypoint() {
        assertEquals("UNSUPPORTED_CLASS", NirvanaHook.status(-1));
        assertEquals("UNSUPPORTED_SIGNATURE", NirvanaHook.status(0));
        assertEquals("INSTALLED:1", NirvanaHook.status(1));
        assertEquals(
                "UNSUPPORTED_SIGNATURE:EXPECTED=1:FOUND=2",
                NirvanaHook.status(2));
    }
}
