package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusHansManager;
import com.android.server.hans.OplusHansDBConfig;
import com.android.server.hans.OplusHansPackage;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryAdapter.Outcome;
import org.junit.Before;
import org.junit.Test;

public final class HansActiveRecoveryAdapterTest {
    private static final int UID = 10_731;
    private HansActiveRecoveryAdapter adapter;

    @Before
    public void resetFixture() {
        OplusHansManager.resetActiveRecoveryFixture();
        OplusHansDBConfig.clearPackagesForTest();
        adapter = HansActiveRecoveryAdapter.create(
                HansActiveRecoveryRuntime.inspect(
                        OplusHansPackage.class.getClassLoader()));
        assertNotNull(adapter);
    }

    @Test
    public void invokesOnlyTheVerifiedForceRecoveryContract() {
        OplusHansDBConfig.putPackageForTest(
                UID,
                new OplusHansPackage(
                        UID, "fixture.recovery", true, false, new Object()));

        assertEquals(Outcome.RECOVERED, adapter.recover(UID));
        assertEquals(1, OplusHansManager.unfreezeCallsForTest());
        assertEquals(UID, OplusHansManager.lastUnfreezeUidForTest());
        assertEquals(
                HansActiveRecoveryAdapter.RECOVERY_REASON,
                OplusHansManager.lastUnfreezeReasonForTest());
        assertEquals(
                HansActiveRecoveryAdapter.RECOVERY_COMPONENT,
                OplusHansManager.lastUnfreezeComponentForTest());
    }

    @Test
    public void distinguishesNotReadyMissingThawedAndVendorFailure() {
        OplusHansManager.setReadyForTest(false);
        assertEquals(Outcome.NOT_READY, adapter.recover(UID));
        assertTrue(Outcome.NOT_READY.retryable());
        assertEquals(0, OplusHansManager.unfreezeCallsForTest());

        OplusHansManager.setReadyForTest(true);
        assertEquals(Outcome.TARGET_UNAVAILABLE, adapter.recover(UID));
        assertFalse(Outcome.TARGET_UNAVAILABLE.retryable());

        OplusHansDBConfig.putPackageForTest(
                UID,
                new OplusHansPackage(
                        UID, "fixture.recovery", false, false, new Object()));
        assertEquals(Outcome.NOT_FROZEN, adapter.recover(UID));
        assertTrue(Outcome.NOT_FROZEN.completed());
        assertEquals(0, OplusHansManager.unfreezeCallsForTest());

        OplusHansDBConfig.putPackageForTest(
                UID,
                new OplusHansPackage(
                        UID, "fixture.recovery", true, false, new Object()));
        OplusHansManager.setUnfreezeResultForTest(false);
        assertEquals(Outcome.UNFREEZE_FAILED, adapter.recover(UID));
        assertTrue(Outcome.UNFREEZE_FAILED.retryable());
        assertEquals(1, OplusHansManager.unfreezeCallsForTest());
    }

    @Test
    public void rejectsInvalidOrMismatchedPackageIdentityBeforeCallingVendorCode() {
        assertEquals(Outcome.INVALID_TARGET, adapter.recover(-1));

        OplusHansDBConfig.putPackageForTest(
                UID,
                new OplusHansPackage(
                        UID + 1, "fixture.recovery", true, false, new Object()));
        assertEquals(Outcome.ACCESS_FAILED, adapter.recover(UID));

        OplusHansDBConfig.putPackageForTest(
                UID,
                new OplusHansPackage(
                        -1, "fixture.recovery", true, false, new Object()));
        assertEquals(Outcome.ACCESS_FAILED, adapter.recover(UID));
        assertEquals(0, OplusHansManager.unfreezeCallsForTest());
    }

    @Test
    public void refusesIncompleteCapabilityMatrices() {
        ClassLoader blocked = new ClassLoader(
                OplusHansPackage.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.equals("com.android.server.am.OplusHansManager")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        assertNull(HansActiveRecoveryAdapter.create(
                HansActiveRecoveryRuntime.inspect(blocked)));
    }
}
