package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
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

    @Test
    public void recordsOneRawHitWithoutInventingIdentityAtTheSceneEntrypoint() {
        Bundle bundle = new Bundle();
        bundle.putString("scene", "CamLowFree");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> target = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        boolean[] parsed = { false };
        boolean[] identity = { true };

        NirvanaHook.Mapping mapping = NirvanaHook.recordInvocation(
                bundle,
                (hookTarget, wasParsed, identityResolved, errorCode) -> {
                    calls.incrementAndGet();
                    target.set(hookTarget);
                    parsed[0] = wasParsed;
                    identity[0] = identityResolved;
                    error.set(errorCode);
                });

        assertEquals(1, calls.get());
        assertEquals(HookTargetIds.OSENSE_GROUP_CLEAN, target.get());
        assertTrue(parsed[0]);
        assertFalse(identity[0]);
        assertEquals("", error.get());
        assertEquals(FeatureIds.CAMERA_LOW_FREE, mapping.featureId);
        assertEquals(1028, mapping.code);
    }

    @Test
    public void malformedArgumentsStillRecordAnUnparsedPhysicalHit() {
        AtomicReference<String> error = new AtomicReference<>();
        boolean[] parsed = { true };

        NirvanaHook.Mapping mapping = NirvanaHook.recordInvocation(
                "not-a-bundle",
                (target, wasParsed, identity, errorCode) -> {
                    parsed[0] = wasParsed;
                    error.set(errorCode);
                });

        assertNull(mapping);
        assertFalse(parsed[0]);
        assertEquals("hook_arguments_unparsed", error.get());
    }

    @Test
    public void unknownSceneStillCountsThePhysicalHookWithoutAUserDecision() {
        Bundle bundle = new Bundle();
        bundle.putString("scene", "FutureScene");
        AtomicInteger calls = new AtomicInteger();

        assertNull(NirvanaHook.recordInvocation(
                bundle,
                (target, parsed, identity, error) -> calls.incrementAndGet()));
        assertEquals(1, calls.get());
    }

    @Test
    public void diagnosticsFailureCannotEscapeTheVendorScenePath() {
        Bundle bundle = new Bundle();
        bundle.putString("scene", "NirvanaLowFree");

        NirvanaHook.Mapping mapping = NirvanaHook.recordInvocation(
                bundle,
                (target, parsed, identity, error) -> {
                    throw new IllegalStateException("diagnostics unavailable");
                });

        assertEquals(FeatureIds.NIRVANA, mapping.featureId);
        assertEquals(1026, mapping.code);
    }
}
