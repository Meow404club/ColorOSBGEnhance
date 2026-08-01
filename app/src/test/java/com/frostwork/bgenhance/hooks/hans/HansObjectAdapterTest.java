package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.server.hans.OplusHansPackage;
import org.junit.Test;

public final class HansObjectAdapterTest {
    private static final int UID = 10_611;
    private static final String PACKAGE_NAME = "com.example.hans.target";

    @Test
    public void preparesAndResolvesFirmwarePackageAccessors() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));

        HansObjectAdapter.Resolution resolution = HansObjectAdapter.resolve(
                new OplusHansPackage(UID, PACKAGE_NAME, true, true, new Object()));

        assertTrue(resolution.isResolved());
        assertNull(resolution.failureOrNull());
        assertEquals(UID, resolution.targetOrNull().uid());
        assertEquals(PACKAGE_NAME, resolution.targetOrNull().packageName());
        assertTrue(resolution.targetOrNull().frozen());
        assertTrue(resolution.targetOrNull().superFreezeMode());
    }

    @Test
    public void supportsAccessorsInheritedFromFirmwareBaseClass() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));

        HansObjectAdapter.Resolution resolution = HansObjectAdapter.resolve(
                new InheritedHansPackage(UID, PACKAGE_NAME, false));

        assertTrue(resolution.isResolved());
        assertEquals(UID, resolution.targetOrNull().uid());
        assertEquals(PACKAGE_NAME, resolution.targetOrNull().packageName());
        assertFalse(resolution.targetOrNull().frozen());
        assertFalse(resolution.targetOrNull().superFreezeMode());
    }

    @Test
    public void prefersGetterValuesOverFallbackFields() {
        assertTrue(HansObjectAdapter.prepare(GetterPreferredFixture.class));

        HansObjectAdapter.Resolution resolution = HansObjectAdapter.resolve(
                new GetterPreferredFixture());

        assertTrue(resolution.isResolved());
        assertEquals(UID, resolution.targetOrNull().uid());
        assertEquals(PACKAGE_NAME, resolution.targetOrNull().packageName());
        assertTrue(resolution.targetOrNull().frozen());
        assertTrue(resolution.targetOrNull().superFreezeMode());
    }

    @Test
    public void resolvesFirmwareFieldsWhenGettersAreAbsent() {
        assertTrue(HansObjectAdapter.prepare(FieldFallbackFixture.class));

        HansObjectAdapter.Resolution resolution = HansObjectAdapter.resolve(
                new FieldFallbackFixture(
                        UID, PACKAGE_NAME, true, false, new Object()));

        assertTrue(resolution.isResolved());
        assertEquals(UID, resolution.targetOrNull().uid());
        assertEquals(PACKAGE_NAME, resolution.targetOrNull().packageName());
        assertTrue(resolution.targetOrNull().frozen());
        assertFalse(resolution.targetOrNull().superFreezeMode());
    }

    @Test
    public void failsOpenForNullPackageObject() {
        assertFailure(
                HansObjectAdapter.Failure.NULL_PACKAGE,
                HansObjectAdapter.resolve(null));
    }

    @Test
    public void failsOpenWhenAccessorsWereNotPrepared() {
        assertFailure(
                HansObjectAdapter.Failure.ACCESSOR_NOT_PREPARED,
                HansObjectAdapter.resolve(new UnpreparedAccessorFixture()));
    }

    @Test
    public void failsOpenWhenAnAccessorIsMissing() {
        assertFalse(HansObjectAdapter.prepare(MissingAccessorFixture.class));

        assertFailure(
                HansObjectAdapter.Failure.ACCESSOR_MISSING,
                HansObjectAdapter.resolve(new MissingAccessorFixture()));
    }

    @Test
    public void failsOpenWhenAnAccessorHasAnInvalidSignature() {
        assertFalse(HansObjectAdapter.prepare(InvalidAccessorFixture.class));

        assertFailure(
                HansObjectAdapter.Failure.ACCESSOR_INVALID,
                HansObjectAdapter.resolve(new InvalidAccessorFixture()));
    }

    @Test
    public void rejectsNonPublicGetterInsteadOfTreatingItAsFirmwareAbi() {
        assertFalse(HansObjectAdapter.prepare(NonPublicAccessorFixture.class));

        assertFailure(
                HansObjectAdapter.Failure.ACCESSOR_INVALID,
                HansObjectAdapter.resolve(new NonPublicAccessorFixture()));
    }

    @Test
    public void rejectsInvalidFrozenAndLockAccessorSignaturesDuringPrepare() {
        assertFalse(HansObjectAdapter.prepare(InvalidFrozenAccessorFixture.class));
        assertFailure(
                HansObjectAdapter.Failure.ACCESSOR_INVALID,
                HansObjectAdapter.resolve(new InvalidFrozenAccessorFixture()));

        assertFalse(HansObjectAdapter.prepare(InvalidLockAccessorFixture.class));
        assertFailure(
                HansObjectAdapter.Failure.ACCESSOR_INVALID,
                HansObjectAdapter.resolve(new InvalidLockAccessorFixture()));
    }

    @Test
    public void failsOpenWhenAccessorInvocationThrows() {
        assertTrue(HansObjectAdapter.prepare(ThrowingAccessorFixture.class));

        assertFailure(
                HansObjectAdapter.Failure.REFLECTION_FAILED,
                HansObjectAdapter.resolve(new ThrowingAccessorFixture()));
    }

    @Test
    public void failsOpenForInvalidUid() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));

        assertFailure(
                HansObjectAdapter.Failure.INVALID_UID,
                HansObjectAdapter.resolve(
                        new OplusHansPackage(-1, PACKAGE_NAME, false)));
    }

    @Test
    public void failsOpenForInvalidPackageName() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));

        assertFailure(
                HansObjectAdapter.Failure.INVALID_PACKAGE_NAME,
                HansObjectAdapter.resolve(
                        new OplusHansPackage(UID, " \t\r\n", false)));
    }

    @Test
    public void failsOpenWhenPackageLockIsNull() {
        assertTrue(HansObjectAdapter.prepare(OplusHansPackage.class));

        HansObjectAdapter.Resolution resolution = HansObjectAdapter.resolve(
                new OplusHansPackage(
                        UID, PACKAGE_NAME, false, false, null));

        assertFailure(HansObjectAdapter.Failure.INVALID_LOCK, resolution);
    }

    private static void assertFailure(
            HansObjectAdapter.Failure expected,
            HansObjectAdapter.Resolution resolution) {
        assertFalse(resolution.isResolved());
        assertNull(resolution.targetOrNull());
        assertEquals(expected, resolution.failureOrNull());
    }

    private static final class InheritedHansPackage extends OplusHansPackage {
        private InheritedHansPackage(int uid, String packageName, boolean superFreezeMode) {
            super(uid, packageName, superFreezeMode);
        }
    }

    private static final class MissingAccessorFixture {
        public int getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }
    }

    private static final class InvalidAccessorFixture {
        public long getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public boolean isFrozen() {
            return false;
        }

        public boolean getSuperFRZMode() {
            return false;
        }

        public Object getLock() {
            return this;
        }
    }

    private static final class NonPublicAccessorFixture {
        private int getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public boolean isFrozen() {
            return false;
        }

        public boolean getSuperFRZMode() {
            return false;
        }

        public Object getLock() {
            return this;
        }
    }

    private static final class UnpreparedAccessorFixture {
        public int getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public boolean isFrozen() {
            return false;
        }

        public boolean getSuperFRZMode() {
            return false;
        }

        public Object getLock() {
            return this;
        }
    }

    private static final class ThrowingAccessorFixture {
        public int getUid() {
            throw new IllegalStateException("fixture accessor failure");
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public boolean isFrozen() {
            return false;
        }

        public boolean getSuperFRZMode() {
            return false;
        }

        public Object getLock() {
            return this;
        }
    }

    private static final class InvalidFrozenAccessorFixture {
        public int getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public Boolean isFrozen() {
            return Boolean.FALSE;
        }

        public boolean getSuperFRZMode() {
            return false;
        }

        public Object getLock() {
            return this;
        }
    }

    private static final class InvalidLockAccessorFixture {
        public int getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public boolean isFrozen() {
            return false;
        }

        public boolean getSuperFRZMode() {
            return false;
        }

        public String getLock() {
            return "not-an-object-signature";
        }
    }

    private static final class GetterPreferredFixture {
        @SuppressWarnings("unused") private final int uid = -1;
        @SuppressWarnings("unused") private final String pkgName = " ";
        @SuppressWarnings("unused") private final boolean frozen = false;
        @SuppressWarnings("unused") private final boolean isSuperFRZMode = false;
        @SuppressWarnings("unused") private final Object mLock = null;

        public int getUid() {
            return UID;
        }

        public String getPkgName() {
            return PACKAGE_NAME;
        }

        public boolean isFrozen() {
            return true;
        }

        public boolean getSuperFRZMode() {
            return true;
        }

        public Object getLock() {
            return this;
        }
    }

    private static final class FieldFallbackFixture {
        private final int uid;
        private final String pkgName;
        private final boolean frozen;
        private final boolean isSuperFRZMode;
        private final Object mLock;

        private FieldFallbackFixture(
                int uid,
                String packageName,
                boolean frozen,
                boolean superFreezeMode,
                Object lock) {
            this.uid = uid;
            this.pkgName = packageName;
            this.frozen = frozen;
            this.isSuperFRZMode = superFreezeMode;
            this.mLock = lock;
        }
    }
}
