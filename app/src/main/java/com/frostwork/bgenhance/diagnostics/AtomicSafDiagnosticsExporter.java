package com.frostwork.bgenhance.diagnostics;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.SyncFailedException;
import java.security.MessageDigest;
import java.util.Locale;

/** Writes, verifies and renames a clearly marked SAF partial document. */
public final class AtomicSafDiagnosticsExporter {
    public static final String MIME_TYPE = "application/json";
    static final int MAX_REPORT_BYTES = 32 * 1_024 * 1_024;
    private static final int BUFFER_BYTES = 32 * 1_024;
    public static final String PARTIAL_SUFFIX = ".partial";
    public static final String PICKER_PARTIAL_SUFFIX = ".partial.json";

    private AtomicSafDiagnosticsExporter() {}

    public static boolean isValidPayload(byte[] payload) {
        return payload != null && payload.length > 0 && payload.length <= MAX_REPORT_BYTES;
    }

    /**
     * Consumes a .partial URI returned by CreateDocument. The final name appears only on success.
     */
    public static Result export(
            ContentResolver resolver,
            Uri destinationUri,
            String expectedPartialDisplayName,
            byte[] payload) {
        if (resolver == null || destinationUri == null) {
            return Result.failed("invalid_destination", false);
        }
        return export(
                new AndroidDocumentStore(resolver), destinationUri,
                expectedPartialDisplayName, payload);
    }

    /** Deletes an empty CreateDocument result when the in-memory payload is no longer available. */
    public static boolean deleteIncomplete(ContentResolver resolver, Uri destinationUri) {
        if (resolver == null || destinationUri == null) return false;
        return deleteQuietly(new AndroidDocumentStore(resolver), destinationUri);
    }

    static Result export(
            DocumentStore store,
            Uri destinationUri,
            String expectedPartialDisplayName,
            byte[] payload) {
        if (store == null || destinationUri == null) {
            return Result.failed("invalid_destination", false);
        }
        String finalDisplayName = finalName(expectedPartialDisplayName);
        if (finalDisplayName == null) {
            return Result.failed(
                    "invalid_filename", deleteQuietly(store, destinationUri));
        }
        if (!isValidPayload(payload)) {
            return Result.failed(
                    "invalid_payload", deleteQuietly(store, destinationUri));
        }

        String failure = "write_failed";
        boolean fsyncApplied = false;
        try {
            checkInterrupted();
            failure = "destination_name_unavailable";
            String actualDisplayName = store.displayName(destinationUri);
            if (!expectedPartialDisplayName.equals(actualDisplayName)) {
                failure = "destination_name_changed";
                throw new IllegalStateException("destination name changed");
            }
            failure = "atomic_commit_unsupported";
            if (!store.supportsRename(destinationUri)) {
                throw new IllegalStateException("atomic rename unavailable");
            }
            failure = "open_failed";
            try (WriteSession session = store.openForRewrite(destinationUri)) {
                if (session == null) {
                    throw new IllegalStateException("document unavailable");
                }
                failure = "write_failed";
                int offset = 0;
                while (offset < payload.length) {
                    checkInterrupted();
                    int count = Math.min(BUFFER_BYTES, payload.length - offset);
                    session.write(payload, offset, count);
                    offset += count;
                }
                failure = "flush_failed";
                session.flush();
                failure = "sync_failed";
                fsyncApplied = session.sync();
                failure = "close_failed";
            }
            checkInterrupted();

            failure = "verification_failed";
            Digest expected = Digest.from(payload);
            Digest actual = store.readDigest(destinationUri);
            if (!expected.matches(actual)) {
                throw new IllegalStateException("written report did not verify");
            }
            checkInterrupted();
            failure = "commit_failed";
            Uri committed = store.rename(destinationUri, finalDisplayName);
            if (committed == null) {
                throw new IllegalStateException("rename returned no document");
            }
            return Result.succeeded(committed, fsyncApplied);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = "export_interrupted";
        } catch (Throwable ignored) {
            // The stage-specific error code is sufficient for the user-facing flow.
        }

        return Result.failed(failure, deleteQuietly(store, destinationUri));
    }

    private static boolean deleteQuietly(DocumentStore store, Uri destinationUri) {
        try {
            return store.delete(destinationUri);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static String finalName(String partialName) {
        if (partialName == null || partialName.isEmpty() || partialName.length() > 200) {
            return null;
        }
        String lower = partialName.toLowerCase(Locale.ROOT);
        String value;
        if (lower.endsWith(PICKER_PARTIAL_SUFFIX)) {
            int marker = partialName.length() - PICKER_PARTIAL_SUFFIX.length();
            if (marker <= 0) return null;
            value = partialName.substring(0, marker) + ".json";
        } else {
            int marker = lower.lastIndexOf(PARTIAL_SUFFIX);
            if (marker <= 0 || marker + PARTIAL_SUFFIX.length() != partialName.length()) {
                return null;
            }
            value = partialName.substring(0, marker);
        }
        if (!value.toLowerCase(Locale.ROOT).endsWith(".json")) return null;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean allowed = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '.' || current == '-' || current == '_';
            if (!allowed) return null;
        }
        return value;
    }

    interface DocumentStore {
        String displayName(Uri document) throws Exception;
        boolean supportsRename(Uri document) throws Exception;
        WriteSession openForRewrite(Uri document) throws Exception;
        Digest readDigest(Uri document) throws Exception;
        Uri rename(Uri document, String displayName) throws Exception;
        boolean delete(Uri document) throws Exception;
    }

    interface WriteSession extends AutoCloseable {
        void write(byte[] value, int offset, int count) throws Exception;
        void flush() throws Exception;

        /** Returns false only when the document provider does not expose fsync semantics. */
        boolean sync() throws Exception;

        @Override
        void close() throws Exception;
    }

    static final class Digest {
        final long byteCount;
        final byte[] sha256;

        Digest(long byteCount, byte[] sha256) {
            this.byteCount = Math.max(0L, byteCount);
            this.sha256 = sha256 == null ? new byte[0] : sha256.clone();
        }

        static Digest from(byte[] payload) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new Digest(payload.length, digest.digest(payload));
        }

        boolean matches(Digest other) {
            return other != null
                    && byteCount == other.byteCount
                    && MessageDigest.isEqual(sha256, other.sha256);
        }
    }

    private static final class AndroidDocumentStore implements DocumentStore {
        private final ContentResolver resolver;

        AndroidDocumentStore(ContentResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public String displayName(Uri document) throws Exception {
            try (Cursor cursor = resolver.query(
                    document,
                    new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                    null,
                    null,
                    null)) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                return cursor.getString(0);
            }
        }

        @Override
        public boolean supportsRename(Uri document) throws Exception {
            try (Cursor cursor = resolver.query(
                    document,
                    new String[] { DocumentsContract.Document.COLUMN_FLAGS },
                    null,
                    null,
                    null)) {
                if (cursor == null || !cursor.moveToFirst()) return false;
                return (cursor.getLong(0)
                        & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0L;
            }
        }

        @Override
        public WriteSession openForRewrite(Uri document) throws Exception {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(document, "rwt");
            if (descriptor == null) throw new IllegalStateException("document unavailable");
            return new AndroidWriteSession(descriptor);
        }

        @Override
        public Digest readDigest(Uri document) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0L;
            try (InputStream input = resolver.openInputStream(document)) {
                if (input == null) throw new IllegalStateException("document unavailable");
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    count += read;
                    if (count > MAX_REPORT_BYTES) {
                        throw new IllegalStateException("report exceeds limit");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            return new Digest(count, digest.digest());
        }

        @Override
        public Uri rename(Uri document, String displayName) throws Exception {
            return DocumentsContract.renameDocument(resolver, document, displayName);
        }

        @Override
        public boolean delete(Uri document) throws Exception {
            return DocumentsContract.deleteDocument(resolver, document);
        }
    }

    private static final class AndroidWriteSession implements WriteSession {
        private final FileOutputStream output;

        AndroidWriteSession(ParcelFileDescriptor descriptor) {
            output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
        }

        @Override
        public void write(byte[] value, int offset, int count) throws Exception {
            output.write(value, offset, count);
        }

        @Override
        public void flush() throws Exception {
            output.flush();
        }

        @Override
        public boolean sync() throws Exception {
            try {
                output.getFD().sync();
                return true;
            } catch (SyncFailedException unsupported) {
                return false;
            }
        }

        @Override
        public void close() throws Exception {
            output.close();
        }
    }

    public static final class Result {
        public final Uri documentUri;
        public final String errorCode;
        public final boolean incompleteDocumentDeleted;
        public final boolean fsyncApplied;

        private Result(
                Uri documentUri,
                String errorCode,
                boolean incompleteDocumentDeleted,
                boolean fsyncApplied) {
            this.documentUri = documentUri;
            this.errorCode = DiagnosticsValues.id(errorCode);
            this.incompleteDocumentDeleted = incompleteDocumentDeleted;
            this.fsyncApplied = fsyncApplied;
        }

        static Result succeeded(Uri documentUri, boolean fsyncApplied) {
            return new Result(documentUri, "", true, fsyncApplied);
        }

        static Result failed(String errorCode, boolean incompleteDocumentDeleted) {
            return new Result(null, errorCode, incompleteDocumentDeleted, false);
        }

        public boolean successful() {
            return documentUri != null && errorCode.isEmpty();
        }
    }
}
