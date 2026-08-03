package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class AtomicSafDiagnosticsExporterTest {
    private static final Uri DESTINATION =
            Uri.parse("content://documents/document/diagnostics.partial");
    private static final Uri COMMITTED =
            Uri.parse("content://documents/document/diagnostics.json");
    private static final String PARTIAL_NAME = "BgEnhance-diagnostics.partial.json";
    private static final String FINAL_NAME = "BgEnhance-diagnostics.json";
    private static final byte[] REPORT = "{\"message\":\"\u5b8c\u6574\u8bca\u65ad\u62a5\u544a\"}\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void writesFlushesSyncsClosesAndVerifiesBeforeSuccess() {
        FakeStore store = new FakeStore();

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertTrue(result.successful());
        assertEquals(COMMITTED, result.documentUri);
        assertTrue(result.fsyncApplied);
        assertArrayEquals(REPORT, store.payload);
        assertEquals("name,open:rwt,write,flush,sync,close,verify,rename",
                store.operations.toString());
        assertEquals(FINAL_NAME, store.renamedTo);
        assertFalse(store.deleted);
    }

    @Test
    public void legacyTrailingPartialNameRemainsSupported() {
        FakeStore store = new FakeStore();
        String legacyName = "BgEnhance-diagnostics.json.partial";
        store.displayName = legacyName;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, legacyName, REPORT);

        assertTrue(result.successful());
        assertEquals(FINAL_NAME, store.renamedTo);
    }

    @Test
    public void finalNameWritesAndVerifiesWithoutRename() {
        FakeStore store = new FakeStore();
        store.displayName = FINAL_NAME;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, FINAL_NAME, REPORT);

        assertTrue(result.successful());
        assertEquals(DESTINATION, result.documentUri);
        assertNull(store.renamedTo);
        assertEquals("name,open:rwt,write,flush,sync,close,verify",
                store.operations.toString());
    }

    @Test
    public void unavailableFsyncUsesVerifiedFallback() {
        FakeStore store = new FakeStore();
        store.syncAvailable = false;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertTrue(result.successful());
        assertFalse(result.fsyncApplied);
        assertEquals("name,open:rwt,write,flush,sync,close,verify,rename",
                store.operations.toString());
    }

    @Test
    public void payloadLargerThanWriteBufferIsWrittenCompletely() {
        FakeStore store = new FakeStore();
        byte[] largeReport = new byte[70 * 1_024];
        for (int index = 0; index < largeReport.length; index++) {
            largeReport[index] = (byte) (index % 251);
        }

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, largeReport);

        assertTrue(result.successful());
        assertArrayEquals(largeReport, store.payload);
    }

    @Test
    public void writeFailureClosesAndDeletesDestination() {
        FakeStore store = new FakeStore();
        store.writeFails = true;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertFalse(result.successful());
        assertEquals("write_failed", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("name,open:rwt,write,close,delete", store.operations.toString());
    }

    @Test
    public void flushSyncAndCloseFailuresNeverReportSuccess() {
        assertStageFailure("flush_failed", FailureStage.FLUSH);
        assertStageFailure("sync_failed", FailureStage.SYNC);
        assertStageFailure("close_failed", FailureStage.CLOSE);
    }

    @Test
    public void verificationMismatchDeletesDestination() {
        FakeStore store = new FakeStore();
        store.mismatchDigest = true;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals("verification_failed", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("name,open:rwt,write,flush,sync,close,verify,delete",
                store.operations.toString());
    }

    @Test
    public void failedCleanupIsReportedSeparately() {
        FakeStore store = new FakeStore();
        store.writeFails = true;
        store.deleteSucceeds = false;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals("write_failed", result.errorCode);
        assertFalse(result.incompleteDocumentDeleted);
    }

    @Test
    public void missingPayloadDeletesNewlyCreatedEmptyDocument() {
        FakeStore store = new FakeStore();

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, null);

        assertEquals("invalid_payload", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("delete", store.operations.toString());
    }

    @Test
    public void invalidDestinationCannotClaimCleanup() {
        FakeStore store = new FakeStore();

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, null, PARTIAL_NAME, REPORT);

        assertEquals("invalid_destination", result.errorCode);
        assertFalse(result.incompleteDocumentDeleted);
        assertEquals("", store.operations.toString());
    }

    @Test
    public void unsafeExpectedFilenameIsDeletedWithoutQueryOrWrite() {
        FakeStore store = new FakeStore();

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, "../diagnostics.json.partial", REPORT);

        assertEquals("invalid_filename", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("delete", store.operations.toString());
    }

    @Test
    public void interruptionDeletesDestinationAndPreservesInterrupt() {
        FakeStore store = new FakeStore();
        Thread.currentThread().interrupt();
        AtomicSafDiagnosticsExporter.Result result;
        try {
            result = AtomicSafDiagnosticsExporter.export(
                    store, DESTINATION, PARTIAL_NAME, REPORT);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals("export_interrupted", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("delete", store.operations.toString());
    }

    @Test
    public void changedSuggestedNameIsDeletedBeforeWrite() {
        FakeStore store = new FakeStore();
        store.displayName = "Renamed-diagnostics.json.partial";

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals("destination_name_changed", result.errorCode);
        assertEquals("name,delete", store.operations.toString());
        assertFalse(store.opened);
    }

    @Test
    public void providerSuffixIsDeletedBeforeWrite() {
        FakeStore store = new FakeStore();
        store.displayName = PARTIAL_NAME + " (1)";

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals("destination_name_changed", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("name,delete", store.operations.toString());
        assertFalse(store.opened);
        assertNull(store.payload);
    }

    @Test
    public void unsupportedRenameIsDeletedAfterVerifiedWrite() {
        FakeStore store = new FakeStore();
        store.renameFails = true;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals("commit_failed", result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertEquals("name,open:rwt,write,flush,sync,close,verify,rename,delete",
                store.operations.toString());
        assertTrue(store.opened);
    }

    @Test
    public void renameFailureCannotLeaveACompleteLookingName() {
        FakeStore store = new FakeStore();
        store.renameFails = true;
        store.deleteSucceeds = false;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals("commit_failed", result.errorCode);
        assertFalse(result.incompleteDocumentDeleted);
        assertTrue(store.displayName.endsWith(
                AtomicSafDiagnosticsExporter.PICKER_PARTIAL_SUFFIX));
        assertEquals("name,open:rwt,write,flush,sync,close,verify,rename,delete",
                store.operations.toString());
        assertNull(store.renamedTo);
    }

    @Test
    public void successfulRenameIsTheFinalCommitPoint() {
        FakeStore store = new FakeStore();
        store.committedDisplayName = FINAL_NAME + ".json";

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertTrue(result.successful());
        assertEquals(COMMITTED, result.documentUri);
        assertFalse(store.deleted);
        assertEquals("name,open:rwt,write,flush,sync,close,verify,rename",
                store.operations.toString());
    }

    private static void assertStageFailure(String expectedCode, FailureStage stage) {
        FakeStore store = new FakeStore();
        store.failureStage = stage;

        AtomicSafDiagnosticsExporter.Result result =
                AtomicSafDiagnosticsExporter.export(
                        store, DESTINATION, PARTIAL_NAME, REPORT);

        assertEquals(expectedCode, result.errorCode);
        assertTrue(result.incompleteDocumentDeleted);
        assertTrue(store.deleted);
    }

    private enum FailureStage { NONE, FLUSH, SYNC, CLOSE }

    private static final class FakeStore
            implements AtomicSafDiagnosticsExporter.DocumentStore {
        final StringBuilder operations = new StringBuilder();
        boolean syncAvailable = true;
        boolean writeFails;
        boolean mismatchDigest;
        boolean deleteSucceeds = true;
        boolean deleted;
        boolean opened;
        boolean renameFails;
        FailureStage failureStage = FailureStage.NONE;
        String displayName = PARTIAL_NAME;
        String committedDisplayName = FINAL_NAME;
        String renamedTo;
        byte[] payload;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        @Override
        public String displayName(Uri document) {
            append("name");
            return displayName;
        }

        @Override
        public AtomicSafDiagnosticsExporter.WriteSession openForRewrite(Uri document) {
            append("open:rwt");
            opened = true;
            return new AtomicSafDiagnosticsExporter.WriteSession() {
                @Override
                public void write(byte[] value, int offset, int count) {
                    append("write");
                    if (writeFails) throw new IllegalStateException("write failed");
                    written.write(value, offset, count);
                    payload = written.toByteArray();
                }

                @Override
                public void flush() {
                    append("flush");
                    if (failureStage == FailureStage.FLUSH) {
                        throw new IllegalStateException("flush failed");
                    }
                }

                @Override
                public boolean sync() {
                    append("sync");
                    if (failureStage == FailureStage.SYNC) {
                        throw new IllegalStateException("sync failed");
                    }
                    return syncAvailable;
                }

                @Override
                public void close() {
                    append("close");
                    if (failureStage == FailureStage.CLOSE) {
                        throw new IllegalStateException("close failed");
                    }
                }
            };
        }

        @Override
        public AtomicSafDiagnosticsExporter.Digest readDigest(Uri document) throws Exception {
            append("verify");
            byte[] value = mismatchDigest
                    ? "different".getBytes(StandardCharsets.UTF_8) : payload;
            return AtomicSafDiagnosticsExporter.Digest.from(value);
        }

        @Override
        public Uri rename(Uri document, String value) {
            append("rename");
            if (renameFails) throw new IllegalStateException("rename failed");
            renamedTo = value;
            displayName = committedDisplayName;
            return COMMITTED;
        }

        @Override
        public boolean delete(Uri document) {
            append("delete");
            deleted = true;
            return deleteSucceeds;
        }

        private void append(String value) {
            if (operations.length() > 0) operations.append(',');
            operations.append(value);
        }
    }
}
