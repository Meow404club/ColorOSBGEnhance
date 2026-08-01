package com.frostwork.bgenhance.hooks.osense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OsenseProcessIdentityAdapterTest {
    private static final String PACKAGE_NAME = "com.example.identity";
    private static final String PROCESS_NAME = "com.example.identity:worker";
    private static final int UID = 10_321;
    private static final int PID = 4_321;

    @Test
    public void resolvesIdentityWhenConfirmedPackageNamesMatch() {
        ProcessInfoFixture fixture = new ProcessInfoFixture(
                UID, PID, PROCESS_NAME, PACKAGE_NAME, PACKAGE_NAME);

        OsenseProcessIdentityResolution resolution =
                OsenseProcessIdentityAdapter.resolve(fixture);

        assertTrue(resolution.isResolved());
        assertFalse(resolution.shouldFailOpen());
        assertEquals(
                new OsenseProcessIdentity(UID, PID, PROCESS_NAME, PACKAGE_NAME),
                resolution.identity().orElseThrow());
        assertTrue(resolution.failure().isEmpty());
    }

    @Test
    public void prewarmsAllIdentityAccessors() {
        assertTrue(OsenseProcessIdentityAdapter.prepare(BaseProcessInfoFixture.class));
        assertTrue(OsenseProcessIdentityAdapter.resolve(new InheritedProcessInfoFixture(
                UID, PID, PROCESS_NAME, PACKAGE_NAME, PACKAGE_NAME)).isResolved());
        assertFalse(OsenseProcessIdentityAdapter.prepare(MissingFieldProcessInfoFixture.class));
    }

    @Test
    public void resolvesFieldsAndGetterDeclaredBySuperclass() {
        InheritedProcessInfoFixture fixture = new InheritedProcessInfoFixture(
                UID, PID, PROCESS_NAME, PACKAGE_NAME, PACKAGE_NAME);

        OsenseProcessIdentityResolution resolution =
                OsenseProcessIdentityAdapter.resolve(fixture);

        assertEquals(
                new OsenseProcessIdentity(UID, PID, PROCESS_NAME, PACKAGE_NAME),
                resolution.identity().orElseThrow());
    }

    @Test
    public void failsOpenWhenPackageSourcesConflict() {
        ProcessInfoFixture fixture = new ProcessInfoFixture(
                UID,
                PID,
                PROCESS_NAME,
                "com.example.group",
                "com.example.record");

        assertUnresolved(
                OsenseProcessIdentityFailure.PACKAGE_NAME_CONFLICT,
                OsenseProcessIdentityAdapter.resolve(fixture));
    }

    @Test
    public void failsOpenWhenRequiredFieldIsMissing() {
        MissingFieldProcessInfoFixture fixture = new MissingFieldProcessInfoFixture(
                UID, PID, PROCESS_NAME, PACKAGE_NAME);

        assertUnresolved(
                OsenseProcessIdentityFailure.ACCESSOR_MISSING,
                OsenseProcessIdentityAdapter.resolve(fixture));
    }

    @Test
    public void failsOpenWhenProcessNameGetterThrows() {
        ThrowingGetterProcessInfoFixture fixture = new ThrowingGetterProcessInfoFixture(
                UID, PID, PACKAGE_NAME);

        assertUnresolved(
                OsenseProcessIdentityFailure.REFLECTION_FAILED,
                OsenseProcessIdentityAdapter.resolve(fixture));
    }

    @Test
    public void failsOpenForInvalidUid() {
        ProcessInfoFixture fixture = new ProcessInfoFixture(
                -1, PID, PROCESS_NAME, PACKAGE_NAME, PACKAGE_NAME);

        assertUnresolved(
                OsenseProcessIdentityFailure.INVALID_UID,
                OsenseProcessIdentityAdapter.resolve(fixture));
    }

    @Test
    public void failsOpenForInvalidPid() {
        ProcessInfoFixture fixture = new ProcessInfoFixture(
                UID, 0, PROCESS_NAME, PACKAGE_NAME, PACKAGE_NAME);

        assertUnresolved(
                OsenseProcessIdentityFailure.INVALID_PID,
                OsenseProcessIdentityAdapter.resolve(fixture));
    }

    @Test
    public void failsOpenWhenPackageIdentityIsIncomplete() {
        ProcessInfoFixture fixture = new ProcessInfoFixture(
                UID, PID, PROCESS_NAME, PACKAGE_NAME, null);

        assertUnresolved(
                OsenseProcessIdentityFailure.INCOMPLETE_PACKAGE_IDENTITY,
                OsenseProcessIdentityAdapter.resolve(fixture));
    }

    @Test
    public void failsOpenForNullInput() {
        assertUnresolved(
                OsenseProcessIdentityFailure.NULL_PROCESS_INFO,
                OsenseProcessIdentityAdapter.resolve(null));
    }

    private static void assertUnresolved(
            OsenseProcessIdentityFailure expectedFailure,
            OsenseProcessIdentityResolution resolution) {
        assertFalse(resolution.isResolved());
        assertTrue(resolution.shouldFailOpen());
        assertTrue(resolution.identity().isEmpty());
        assertEquals(expectedFailure, resolution.failure().orElseThrow());
    }

    private static class BaseProcessInfoFixture {
        private final int mUid;
        private final int mPid;
        private final String processName;
        private final ProcessGroupFixture mGroup;
        private final ProcessRecordFixture mToBeKill;

        BaseProcessInfoFixture(
                int uid,
                int pid,
                String processName,
                String groupPackageName,
                String recordPackageName) {
            this.mUid = uid;
            this.mPid = pid;
            this.processName = processName;
            this.mGroup = new ProcessGroupFixture(groupPackageName);
            this.mToBeKill = new ProcessRecordFixture(recordPackageName);
        }

        private String getProcessName() {
            return processName;
        }
    }

    private static final class ProcessInfoFixture extends BaseProcessInfoFixture {
        ProcessInfoFixture(
                int uid,
                int pid,
                String processName,
                String groupPackageName,
                String recordPackageName) {
            super(uid, pid, processName, groupPackageName, recordPackageName);
        }
    }

    private static final class InheritedProcessInfoFixture extends BaseProcessInfoFixture {
        InheritedProcessInfoFixture(
                int uid,
                int pid,
                String processName,
                String groupPackageName,
                String recordPackageName) {
            super(uid, pid, processName, groupPackageName, recordPackageName);
        }
    }

    private static final class MissingFieldProcessInfoFixture {
        private final int mUid;
        private final int mPid;
        private final String processName;
        private final ProcessGroupFixture mGroup;

        MissingFieldProcessInfoFixture(
                int uid, int pid, String processName, String packageName) {
            this.mUid = uid;
            this.mPid = pid;
            this.processName = processName;
            this.mGroup = new ProcessGroupFixture(packageName);
        }

        private String getProcessName() {
            return processName;
        }
    }

    private static final class ThrowingGetterProcessInfoFixture {
        private final int mUid;
        private final int mPid;
        private final ProcessGroupFixture mGroup;
        private final ProcessRecordFixture mToBeKill;

        ThrowingGetterProcessInfoFixture(int uid, int pid, String packageName) {
            this.mUid = uid;
            this.mPid = pid;
            this.mGroup = new ProcessGroupFixture(packageName);
            this.mToBeKill = new ProcessRecordFixture(packageName);
        }

        private String getProcessName() {
            throw new IllegalStateException("fixture getter failure");
        }
    }

    private static final class ProcessGroupFixture {
        private final String mPackageName;

        ProcessGroupFixture(String packageName) {
            this.mPackageName = packageName;
        }
    }

    private static final class ProcessRecordFixture {
        private final ApplicationInfoFixture info;

        ProcessRecordFixture(String packageName) {
            this.info = new ApplicationInfoFixture(packageName);
        }
    }

    private static final class ApplicationInfoFixture {
        private final String packageName;

        ApplicationInfoFixture(String packageName) {
            this.packageName = packageName;
        }
    }
}
