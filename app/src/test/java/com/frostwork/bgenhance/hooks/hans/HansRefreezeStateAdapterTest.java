package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.states.HansAppStateMachine;
import org.junit.Test;

public final class HansRefreezeStateAdapterTest {
    private static final int UID = 10_851;
    private static final String PACKAGE = "com.example.refreeze";

    @Test
    public void resolvesMiddleAndFrozenStateFromPreparedAccessors() {
        OplusHansPackage hansPackage = new OplusHansPackage(
                UID, PACKAGE, true, false, new Object());
        HansAppStateMachine stateMachine = new HansAppStateMachine(hansPackage, "Packet");

        assertTrue(prepare(
                HansAppStateMachine.class,
                HansAppStateMachine.MiddleState.class,
                HansAppStateMachine.FrozenState.class));

        assertResolution(
                stateMachine,
                "Packet",
                HansRefreezeStateAdapter.resolve(stateMachine.new MiddleState()));
        stateMachine.setReason("Alarm");
        assertResolution(
                stateMachine,
                "Alarm",
                HansRefreezeStateAdapter.resolve(stateMachine.new FrozenState()));

        assertEquals(Boolean.FALSE,
                HansRefreezeStateAdapter.fastFreezePendingOrNull(stateMachine));
        stateMachine.setMtoFinFastFreeze(true);
        assertEquals(Boolean.TRUE,
                HansRefreezeStateAdapter.fastFreezePendingOrNull(stateMachine));
        assertTrue(HansRefreezeStateAdapter.clearFastFreezePending(stateMachine));
        assertFalse(stateMachine.isMtoFinFastFreeze());
        Object token = new Object();
        assertTrue(HansRefreezeStateAdapter.requestMtoF(stateMachine, token));
        assertEquals(1, stateMachine.sentMessages().size());
        assertEquals(Integer.valueOf(8), stateMachine.sentMessages().get(0));
        assertSame(token, stateMachine.sentMessageObjects().get(0));
    }

    @Test
    public void nullAndUnpreparedStatesFailWithoutLazyReflection() {
        HansRefreezeStateAdapter.Resolution nullState =
                HansRefreezeStateAdapter.resolve(null);
        assertFalse(nullState.isResolved());
        assertEquals(
                HansRefreezeStateAdapter.Failure.NULL_STATE,
                nullState.failureOrNull());

        HansRefreezeStateAdapter.Resolution unprepared =
                HansRefreezeStateAdapter.resolve(new UnpreparedState());
        assertFalse(unprepared.isResolved());
        assertEquals(
                HansRefreezeStateAdapter.Failure.ACCESSOR_NOT_PREPARED,
                unprepared.failureOrNull());
        assertNull(HansRefreezeStateAdapter.fastFreezePendingOrNull(new Object()));
        assertFalse(HansRefreezeStateAdapter.clearFastFreezePending(new Object()));
        assertFalse(HansRefreezeStateAdapter.requestMtoF(new Object(), new Object()));
    }

    @Test
    public void rejectsWrongOuterOwnershipOrFieldShape() {
        assertFalse(prepare(
                WrongOuter.class,
                WrongOuter.MiddleState.class,
                ForeignFrozenState.class));
        assertFalse(prepare(
                WrongFields.class,
                WrongFields.MiddleState.class,
                WrongFields.FrozenState.class));
    }

    private static boolean prepare(
            Class<?> outer, Class<?> middle, Class<?> frozen) {
        return HansRefreezeStateAdapter.prepare(
                outer, middle, frozen, OplusHansPackage.class);
    }

    private static void assertResolution(
            Object stateMachine,
            String reason,
            HansRefreezeStateAdapter.Resolution resolution) {
        assertTrue(resolution.isResolved());
        assertSame(stateMachine, resolution.stateMachineOrNull());
        assertEquals(UID, resolution.targetOrNull().uid());
        assertEquals(PACKAGE, resolution.targetOrNull().packageName());
        assertEquals(reason, resolution.reasonOrNull());
        assertNull(resolution.failureOrNull());
    }

    private static final class UnpreparedState {}

    private static final class WrongOuter {
        private OplusHansPackage mHansPkg;
        private String mUnfreezeReason;
        final class MiddleState {}
    }

    private static final class ForeignFrozenState {}

    private static final class WrongFields {
        private static OplusHansPackage mHansPkg;
        private Object mUnfreezeReason;
        final class MiddleState {}
        final class FrozenState {}
    }
}
