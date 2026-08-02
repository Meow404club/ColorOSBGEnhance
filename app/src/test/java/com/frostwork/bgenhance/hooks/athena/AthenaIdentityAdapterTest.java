package com.frostwork.bgenhance.hooks.athena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oplus.app.athena.ProcDetailInfo;
import org.junit.Test;

public final class AthenaIdentityAdapterTest {
    private static final String PACKAGE = "example.protected";
    private static final int UID = 10_321;
    private static final int PID = 4321;

    @Test
    public void preheatsProcessCandidateGroupAndActionAccessors() {
        AthenaIdentityAdapter.Preparation preparation = preparation();

        assertTrue(preparation.status().name(), preparation.isReady());
        assertEquals(AthenaIdentityAdapter.PreparationStatus.READY, preparation.status());
        assertNotNull(preparation.preparedOrNull());
    }

    @Test
    public void resolvesAndCrossChecksEveryIdentityShape() {
        AthenaIdentityAdapter.Prepared adapter = prepared();
        ProcDetailInfo process = process(PACKAGE, UID, PID);
        e4.a0 candidate = new e4.a0(process);
        e4.z group = new e4.z(PACKAGE, 0).add(candidate)
                .add(new e4.a0(process(PACKAGE, UID, PID + 1)));

        assertIdentity(adapter.resolveProcess(process), PACKAGE, UID, PID);
        assertIdentity(adapter.resolveCandidate(candidate), PACKAGE, UID, PID);
        assertIdentity(
                adapter.resolveGroup(group),
                PACKAGE,
                UID,
                com.frostwork.bgenhance.residency.runtime.ResidencyTarget.UNKNOWN_PID);

        AthenaIdentityAdapter.ActionCodeResolution action =
                adapter.resolveActionCode(new e4.t(4012));
        assertTrue(action.isResolved());
        assertEquals(4012, action.code());
        assertNull(action.failureOrNull());
    }

    @Test
    public void invalidUidPackageUserAndRuntimeTypesFailOpen() {
        AthenaIdentityAdapter.Prepared adapter = prepared();

        assertFailure(
                adapter.resolveProcess(process(PACKAGE, -1, PID)),
                AthenaIdentityAdapter.Failure.INVALID_UID);
        assertFailure(
                adapter.resolveProcess(process("   ", UID, PID)),
                AthenaIdentityAdapter.Failure.INVALID_PACKAGE);
        ProcDetailInfo wrongUser = process(PACKAGE, 110_321, PID);
        wrongUser.userId = 0;
        assertFailure(
                adapter.resolveProcess(wrongUser),
                AthenaIdentityAdapter.Failure.USER_ID_CONFLICT);
        assertFailure(
                adapter.resolveProcess(new Object()),
                AthenaIdentityAdapter.Failure.WRONG_RUNTIME_TYPE);
        assertFailure(
                adapter.resolveCandidate(null),
                AthenaIdentityAdapter.Failure.NULL_VALUE);
        assertFalse(adapter.resolveActionCode(new Object()).isResolved());
    }

    @Test
    public void candidateGetterConflictIsRejected() {
        AthenaIdentityAdapter.Prepared adapter = prepared();
        e4.a0 conflicting = new e4.a0(process(PACKAGE, UID, PID)) {
            @Override public int p() { return UID + 1; }
        };

        assertFailure(
                adapter.resolveCandidate(conflicting),
                AthenaIdentityAdapter.Failure.OBJECT_CONFLICT);
    }

    @Test
    public void groupRequiresOneConsistentFullUid() {
        AthenaIdentityAdapter.Prepared adapter = prepared();

        e4.z multipleUids = new e4.z(PACKAGE, 0)
                .add(new e4.a0(process(PACKAGE, UID, PID)))
                .add(new e4.a0(process(PACKAGE, UID + 1, PID + 1)));
        assertFailure(
                adapter.resolveGroup(multipleUids),
                AthenaIdentityAdapter.Failure.MULTIPLE_UIDS);

        e4.z packageConflict = new e4.z(PACKAGE, 0)
                .add(new e4.a0(process("example.other", UID, PID)));
        assertFailure(
                adapter.resolveGroup(packageConflict),
                AthenaIdentityAdapter.Failure.OBJECT_CONFLICT);

        assertFailure(
                adapter.resolveGroup(new e4.z(PACKAGE, 0)),
                AthenaIdentityAdapter.Failure.EMPTY_GROUP);
    }

    @Test
    public void unavailableCapabilityMatrixCannotPublishAccessors() {
        ClassLoader loader = new FilteringClassLoader(
                getClass().getClassLoader(), "b4.x");
        AthenaIdentityAdapter.Preparation preparation = AthenaIdentityAdapter.prepare(
                AthenaTerminalRuntime.inspect(loader));

        assertFalse(preparation.isReady());
        assertEquals(
                AthenaIdentityAdapter.PreparationStatus.MATRIX_UNAVAILABLE,
                preparation.status());
        assertNull(preparation.preparedOrNull());
    }

    private AthenaIdentityAdapter.Preparation preparation() {
        return AthenaIdentityAdapter.prepare(AthenaTerminalRuntime.inspect(
                getClass().getClassLoader()));
    }

    private AthenaIdentityAdapter.Prepared prepared() {
        AthenaIdentityAdapter.Preparation preparation = preparation();
        assertTrue(preparation.status().name(), preparation.isReady());
        return preparation.preparedOrNull();
    }

    private static ProcDetailInfo process(String packageName, int uid, int pid) {
        int userId = uid < 0 ? -1 : uid / 100_000;
        return new ProcDetailInfo(packageName, packageName + ":worker", uid, pid, userId);
    }

    private static void assertIdentity(
            AthenaIdentityAdapter.IdentityResolution resolution,
            String packageName,
            int uid,
            int pid) {
        assertTrue(
                String.valueOf(resolution.failureOrNull()),
                resolution.isResolved());
        AthenaIdentityAdapter.Identity identity = resolution.identityOrNull();
        assertEquals(packageName, identity.packageName());
        assertEquals(uid, identity.uid());
        assertEquals(pid, identity.pid());
    }

    private static void assertFailure(
            AthenaIdentityAdapter.IdentityResolution resolution,
            AthenaIdentityAdapter.Failure expected) {
        assertFalse(resolution.isResolved());
        assertNull(resolution.identityOrNull());
        assertEquals(expected, resolution.failureOrNull());
    }

    private static final class FilteringClassLoader extends ClassLoader {
        private final String blockedName;

        private FilteringClassLoader(ClassLoader parent, String blockedName) {
            super(parent);
            this.blockedName = blockedName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (blockedName.equals(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }
}
