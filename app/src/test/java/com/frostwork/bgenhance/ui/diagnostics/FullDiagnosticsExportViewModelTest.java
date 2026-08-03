package com.frostwork.bgenhance.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.DiagnosticsReportMetadata;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class FullDiagnosticsExportViewModelTest {
    private static final Uri DESTINATION =
            Uri.parse("content://documents/document/diagnostics.json");
    private static final long EXPORT_TIME = 1_785_724_200_123L;

    @Test
    public void completePayloadIsPreparedBeforeOneCreateDocumentRequest() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.viewModel.prepare(DiagnosticsReportMetadata.empty()));
        assertEquals(FullDiagnosticsExportViewModel.Status.PREPARING,
                fixture.viewModel.state().status);

        fixture.source.complete(completeSnapshot());

        FullDiagnosticsExportViewModel.State ready = fixture.viewModel.state();
        assertEquals(FullDiagnosticsExportViewModel.Status.READY, ready.status);
        assertTrue(ready.payloadByteCount > 0);
        assertNull(fixture.writer.payload);

        FullDiagnosticsExportViewModel.DestinationRequest request =
                fixture.viewModel.takeDestinationRequest();
        assertNotNull(request);
        assertEquals("BgEnhance-diagnostics-20260803-023000-123Z.json",
                request.displayName);
        assertNull(fixture.viewModel.takeDestinationRequest());
        assertEquals(FullDiagnosticsExportViewModel.Status.AWAITING_URI,
                fixture.viewModel.state().status);

        fixture.viewModel.onDestinationSelected(DESTINATION);

        assertEquals(FullDiagnosticsExportViewModel.Status.SUCCESS,
                fixture.viewModel.state().status);
        assertEquals(DESTINATION, fixture.writer.destination);
        assertEquals(request.displayName, fixture.writer.expectedDisplayName);
        String report = new String(fixture.writer.payload, StandardCharsets.UTF_8);
        assertTrue(report.contains("bgenhance-residency-diagnostics"));
        assertTrue(report.endsWith("\n"));
        FullDiagnosticsExportViewModel.Completion completion =
                fixture.viewModel.consumeCompletion(request.operationId);
        assertNotNull(completion);
        assertTrue(completion.successful);
        assertEquals(FullDiagnosticsExportViewModel.Status.IDLE,
                fixture.viewModel.state().status);
    }

    @Test
    public void truncatedSnapshotIsRejectedBeforeDestinationPicker() {
        Fixture fixture = new Fixture();
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());

        fixture.source.complete(snapshotWithStatus(true, false));

        assertIncompleteBeforeDestination(fixture);
    }

    @Test
    public void collectionErrorsAreRejectedBeforeDestinationPicker() {
        Fixture fixture = new Fixture();
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());

        fixture.source.complete(snapshotWithStatus(false, true));

        assertIncompleteBeforeDestination(fixture);
    }

    @Test
    public void incompleteSnapshotCannotSucceedAndAnyUnexpectedDocumentIsDeleted() {
        Fixture fixture = new Fixture();
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        fixture.source.complete(snapshotWithStatus(true, false));
        FullDiagnosticsExportViewModel.State rejected = fixture.viewModel.state();
        FullDiagnosticsExportViewModel.Completion completion =
                fixture.viewModel.consumeCompletion(rejected.operationId);

        assertNotNull(completion);
        assertFalse(completion.successful);
        assertEquals(0, fixture.writer.writeCalls);
        fixture.viewModel.onDestinationSelected(DESTINATION);

        assertEquals(FullDiagnosticsExportViewModel.Status.ERROR,
                fixture.viewModel.state().status);
        assertEquals(FullDiagnosticsExportViewModel.ERROR_MISSING_PAYLOAD,
                fixture.viewModel.state().errorCode);
        assertEquals(1, fixture.writer.deleteCalls);
        assertEquals(0, fixture.writer.writeCalls);
    }

    @Test
    public void snapshotErrorIsRejectedBeforeDestinationPicker() {
        Fixture fixture = new Fixture();
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());

        fixture.source.complete(DiagnosticsQuery.ExportSnapshot.empty("database_unavailable"));

        assertEquals(FullDiagnosticsExportViewModel.Status.ERROR,
                fixture.viewModel.state().status);
        assertEquals("report_unavailable", fixture.viewModel.state().errorCode);
        assertNull(fixture.viewModel.takeDestinationRequest());
        assertNull(fixture.writer.payload);
    }

    @Test
    public void redactedSnapshotCannotBeSavedAsAFullReport() {
        Fixture fixture = new Fixture();
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        DiagnosticsQuery.ExportSnapshot complete = completeSnapshot();
        fixture.source.complete(new DiagnosticsQuery.ExportSnapshot(
                complete.formatVersion,
                complete.databaseSchemaVersion,
                complete.generatedAt,
                complete.retentionMillis,
                false,
                false,
                false,
                complete.recentEvents,
                complete.aggregates,
                complete.rawHooks,
                complete.hookStates,
                complete.homeStats,
                complete.exportStatus,
                ""));

        assertIncompleteBeforeDestination(fixture);
    }

    @Test
    public void detachedListenerDoesNotReceiveLatePreparationResult() {
        Fixture fixture = new Fixture();
        List<FullDiagnosticsExportViewModel.Status> first = new ArrayList<>();
        List<FullDiagnosticsExportViewModel.Status> second = new ArrayList<>();
        FullDiagnosticsExportViewModel.Listener firstListener =
                state -> first.add(state.status);
        FullDiagnosticsExportViewModel.Listener secondListener =
                state -> second.add(state.status);
        fixture.viewModel.setListener(firstListener);
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        fixture.viewModel.removeListener(firstListener);
        fixture.viewModel.setListener(secondListener);

        fixture.source.complete(completeSnapshot());

        assertFalse(first.contains(FullDiagnosticsExportViewModel.Status.READY));
        assertTrue(second.contains(FullDiagnosticsExportViewModel.Status.READY));
        assertNotNull(fixture.viewModel.takeDestinationRequest());
        assertNull(fixture.viewModel.takeDestinationRequest());
    }

    @Test
    public void closedViewModelDropsOldAsyncResult() {
        Fixture fixture = new Fixture();
        List<FullDiagnosticsExportViewModel.Status> observed = new ArrayList<>();
        fixture.viewModel.setListener(state -> observed.add(state.status));
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        int beforeClose = observed.size();

        fixture.viewModel.close();
        fixture.source.complete(completeSnapshot());

        assertEquals(beforeClose, observed.size());
        assertNull(fixture.writer.payload);
    }

    @Test
    public void closeAfterUriKeepsQueuedAtomicWriteButDropsUiResult() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Fixture fixture = new Fixture(tasks::addLast);
        List<FullDiagnosticsExportViewModel.Status> observed = new ArrayList<>();
        fixture.viewModel.setListener(state -> observed.add(state.status));
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        fixture.source.complete(completeSnapshot());
        tasks.removeFirst().run();
        assertNotNull(fixture.viewModel.takeDestinationRequest());
        fixture.viewModel.onDestinationSelected(DESTINATION);
        int beforeClose = observed.size();

        fixture.viewModel.close();
        tasks.removeFirst().run();

        assertEquals(1, fixture.writer.writeCalls);
        assertEquals(beforeClose, observed.size());
        assertEquals(FullDiagnosticsExportViewModel.Status.IDLE,
                fixture.viewModel.state().status);
    }

    @Test
    public void missingPayloadAfterProcessRecreationDeletesNewDocument() {
        Fixture fixture = new Fixture();

        fixture.viewModel.onDestinationSelected(DESTINATION);

        FullDiagnosticsExportViewModel.State error = fixture.viewModel.state();
        assertEquals(FullDiagnosticsExportViewModel.Status.ERROR, error.status);
        assertEquals(FullDiagnosticsExportViewModel.ERROR_MISSING_PAYLOAD, error.errorCode);
        assertTrue(error.destinationCreated);
        assertTrue(error.incompleteDocumentDeleted);
        assertEquals(1, fixture.writer.deleteCalls);
        assertNull(fixture.writer.payload);
    }

    @Test
    public void failedWriteReportsWhetherCleanupAlsoFailed() {
        Fixture fixture = new Fixture();
        fixture.writer.outcome = FullDiagnosticsExportViewModel.WriteOutcome.failed(
                "write_failed", false);
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        fixture.source.complete(completeSnapshot());
        FullDiagnosticsExportViewModel.DestinationRequest request =
                fixture.viewModel.takeDestinationRequest();

        fixture.viewModel.onDestinationSelected(DESTINATION);

        FullDiagnosticsExportViewModel.Completion completion =
                fixture.viewModel.consumeCompletion(request.operationId);
        assertNotNull(completion);
        assertFalse(completion.successful);
        assertTrue(completion.destinationCreated);
        assertFalse(completion.incompleteDocumentDeleted);
    }

    @Test
    public void workerRejectionAfterDestinationCreationDeletesPartialDocument() {
        AtomicInteger submissions = new AtomicInteger();
        Fixture fixture = new Fixture(command -> {
            if (submissions.incrementAndGet() > 1) {
                throw new RejectedExecutionException("worker stopped");
            }
            command.run();
        });
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        fixture.source.complete(completeSnapshot());
        assertNotNull(fixture.viewModel.takeDestinationRequest());

        fixture.viewModel.onDestinationSelected(DESTINATION);

        FullDiagnosticsExportViewModel.State state = fixture.viewModel.state();
        assertEquals(FullDiagnosticsExportViewModel.Status.ERROR, state.status);
        assertEquals("worker_unavailable", state.errorCode);
        assertTrue(state.destinationCreated);
        assertTrue(state.incompleteDocumentDeleted);
        assertEquals(1, fixture.writer.deleteCalls);
        assertEquals(0, fixture.writer.writeCalls);
    }

    @Test
    public void cancelingPickerDiscardsPayloadWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.viewModel.prepare(DiagnosticsReportMetadata.empty());
        fixture.source.complete(completeSnapshot());
        assertNotNull(fixture.viewModel.takeDestinationRequest());

        fixture.viewModel.onDestinationSelected(null);

        assertEquals(FullDiagnosticsExportViewModel.Status.IDLE,
                fixture.viewModel.state().status);
        assertNull(fixture.writer.payload);
    }

    private static void assertIncompleteBeforeDestination(Fixture fixture) {
        FullDiagnosticsExportViewModel.State state = fixture.viewModel.state();
        assertEquals(FullDiagnosticsExportViewModel.Status.ERROR, state.status);
        assertEquals(FullDiagnosticsExportViewModel.ERROR_INCOMPLETE_REPORT,
                state.errorCode);
        assertFalse(state.destinationCreated);
        assertNull(fixture.viewModel.takeDestinationRequest());
        assertNull(fixture.writer.payload);
        assertEquals(0, fixture.writer.writeCalls);
        assertEquals(0, fixture.writer.deleteCalls);
    }

    private static DiagnosticsQuery.ExportSnapshot completeSnapshot() {
        return snapshotWithStatus(false, false);
    }

    private static DiagnosticsQuery.ExportSnapshot snapshotWithStatus(
            boolean truncated, boolean hasErrors) {
        DiagnosticsQuery.ExportCollectionStatus recent =
                new DiagnosticsQuery.ExportCollectionStatus(
                        10, truncated ? 10 : 1, 1, truncated,
                        hasErrors ? "query_failed" : "");
        DiagnosticsQuery.ExportCollectionStatus complete =
                new DiagnosticsQuery.ExportCollectionStatus(10, 1, 1, false, "");
        DiagnosticsQuery.ExportStatus status = new DiagnosticsQuery.ExportStatus(
                recent, complete, complete, complete, 1,
                DiagnosticsQuery.ExportBootStatus.unavailable(),
                new DiagnosticsQuery.BackendHealth(1L, 0L, 1L, 0L, 0, false));
        return new DiagnosticsQuery.ExportSnapshot(
                2, 6, 100L, 200L, true, true, true,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                DiagnosticsQuery.HomeStatsSnapshot.empty(""), status, "");
    }

    private static final class Fixture {
        final CompletableFuture<DiagnosticsQuery.ExportSnapshot> source =
                new CompletableFuture<>();
        final FakeWriter writer = new FakeWriter();
        final FullDiagnosticsExportViewModel viewModel;

        Fixture() {
            this(Runnable::run);
        }

        Fixture(Executor worker) {
            viewModel = new FullDiagnosticsExportViewModel(
                        () -> source,
                        writer,
                        worker,
                        Runnable::run,
                        () -> EXPORT_TIME);
        }
    }

    private static final class FakeWriter
            implements FullDiagnosticsExportViewModel.DestinationWriter {
        FullDiagnosticsExportViewModel.WriteOutcome outcome =
                FullDiagnosticsExportViewModel.WriteOutcome.succeeded();
        Uri destination;
        String expectedDisplayName;
        byte[] payload;
        int deleteCalls;
        int writeCalls;
        boolean deleteSucceeds = true;

        @Override
        public FullDiagnosticsExportViewModel.WriteOutcome write(
                Uri destinationUri, String expectedName, byte[] value) {
            destination = destinationUri;
            expectedDisplayName = expectedName;
            payload = value.clone();
            writeCalls++;
            return outcome;
        }

        @Override
        public boolean deleteIncomplete(Uri destinationUri) {
            destination = destinationUri;
            deleteCalls++;
            return deleteSucceeds;
        }
    }
}
