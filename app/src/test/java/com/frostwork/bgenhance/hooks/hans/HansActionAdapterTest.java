package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.states.action.Action;
import org.junit.Test;

public final class HansActionAdapterTest {
    @Test
    public void resolvesPreparedActionAndInheritedInstance() {
        assertTrue(HansActionAdapter.prepare(Action.class, OplusHansPackage.class));
        OplusHansPackage hansPackage = new OplusHansPackage(
                10123, "fixture.package", true, false, new Object());

        HansActionAdapter.Resolution resolution =
                HansActionAdapter.resolve(new ConcreteAction(hansPackage));

        assertTrue(resolution.isResolved());
        HansObjectAdapter.Target target = resolution.targetOrNull();
        assertNotNull(target);
        assertEquals(10123, target.uid());
        assertEquals("fixture.package", target.packageName());
        assertTrue(target.frozen());
        assertFalse(target.superFreezeMode());
        assertNull(resolution.failureOrNull());
        assertNull(resolution.packageFailureOrNull());
    }

    @Test
    public void failsClosedAtAdapterBoundaryForNullAndUnpreparedObjects() {
        HansActionAdapter.Resolution nullResolution = HansActionAdapter.resolve(null);
        assertFalse(nullResolution.isResolved());
        assertEquals(
                HansActionAdapter.Failure.NULL_ACTION,
                nullResolution.failureOrNull());

        HansActionAdapter.Resolution unprepared =
                HansActionAdapter.resolve(new UnpreparedAction());
        assertFalse(unprepared.isResolved());
        assertEquals(
                HansActionAdapter.Failure.ACCESSOR_NOT_PREPARED,
                unprepared.failureOrNull());
    }

    @Test
    public void rejectsMissingMutableStaticWrongVisibilityAndWrongTypeFields() {
        assertFalse(HansActionAdapter.prepare(
                MissingFieldAction.class, OplusHansPackage.class));
        assertFalse(HansActionAdapter.prepare(
                MutableFieldAction.class, OplusHansPackage.class));
        assertFalse(HansActionAdapter.prepare(
                StaticFieldAction.class, OplusHansPackage.class));
        assertFalse(HansActionAdapter.prepare(
                PackageFieldAction.class, OplusHansPackage.class));
        assertFalse(HansActionAdapter.prepare(
                WrongTypeAction.class, OplusHansPackage.class));
    }

    @Test
    public void preservesPackageResolutionFailureWithoutThrowing() {
        assertTrue(HansActionAdapter.prepare(Action.class, OplusHansPackage.class));
        HansActionAdapter.Resolution resolution = HansActionAdapter.resolve(
                new ConcreteAction(new OplusHansPackage(
                        -1, "fixture.package", false, false, new Object())));

        assertFalse(resolution.isResolved());
        assertEquals(
                HansActionAdapter.Failure.PACKAGE_RESOLUTION_FAILED,
                resolution.failureOrNull());
        assertEquals(
                HansObjectAdapter.Failure.INVALID_UID,
                resolution.packageFailureOrNull());
    }

    @Test
    public void failedSubclassPreparationDoesNotShadowPreparedParent() {
        assertFalse(HansActionAdapter.prepare(
                InheritedAction.class, OplusHansPackage.class));
        assertTrue(HansActionAdapter.prepare(
                ParentAction.class, OplusHansPackage.class));

        HansActionAdapter.Resolution resolution = HansActionAdapter.resolve(
                new InheritedAction(new OplusHansPackage(
                        10124, "inherited.package", false, false, new Object())));

        assertTrue(resolution.isResolved());
        assertEquals(10124, resolution.targetOrNull().uid());
    }

    @Test
    public void rejectsNullPackageAtCallbackBoundary() {
        assertTrue(HansActionAdapter.prepare(Action.class, OplusHansPackage.class));

        HansActionAdapter.Resolution resolution =
                HansActionAdapter.resolve(new ConcreteAction(null));

        assertFalse(resolution.isResolved());
        assertEquals(
                HansActionAdapter.Failure.NULL_PACKAGE,
                resolution.failureOrNull());
    }

    private static final class ConcreteAction extends Action {
        private ConcreteAction(OplusHansPackage hansPackage) {
            super(hansPackage);
        }
    }

    private static final class UnpreparedAction {}
    private static final class MissingFieldAction {}

    private static final class MutableFieldAction {
        protected OplusHansPackage mHansPackage;
    }

    private static final class StaticFieldAction {
        protected static final OplusHansPackage mHansPackage = null;
    }

    private static final class PackageFieldAction {
        final OplusHansPackage mHansPackage = null;
    }

    private static final class WrongTypeAction {
        protected final Object mHansPackage = null;
    }

    private static class ParentAction {
        protected final OplusHansPackage mHansPackage;

        private ParentAction(OplusHansPackage hansPackage) {
            this.mHansPackage = hansPackage;
        }
    }

    private static final class InheritedAction extends ParentAction {
        private InheritedAction(OplusHansPackage hansPackage) {
            super(hansPackage);
        }
    }
}
