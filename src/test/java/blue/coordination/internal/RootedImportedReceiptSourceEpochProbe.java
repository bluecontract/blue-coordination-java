package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.WorkKind;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only reads of the actual committed carrier; no invocation or authority is reconstructed. */
public final class RootedImportedReceiptSourceEpochProbe {
    private RootedImportedReceiptSourceEpochProbe() { }

    public static String verify(CoordinationEngine actualEngine, ManagedEpochApplicationWork work) {
        var engine = (DefaultCoordinationEngine) actualEngine;
        var documents = engine.documents();
        var application = documents.catchUpApplicationByWork(work.workIdentity()).orElseThrow();
        var publication = documents.closureReceiptForApplication(application).orElseThrow();
        assertTrue(publication.rootedTerminalEvidence().identifiesHistoricalWork(work));
        var input = publication.rootedTerminalEvidence().input();
        var result = publication.attempt().processResult();
        assertTrue(result.commits());
        assertEquals(application.contractsInvocationIdentity(), result.invocationIdentity());
        var cause = (ManagedRevisionCause) input.cause();
        var successor = cause.successorRepresentationCause().orElseThrow();
        assertEquals(7L, cause.fromEpoch());
        assertEquals(8L, cause.toEpoch());
        var source = cause.childDocumentId();
        var consumer = ContractsClosureAdapter.closureId(work.consumerDocumentId());
        var beforeSource = input.snapshot().managedDocument(source);
        var afterSource = result.resultingDocuments().stream().filter(value -> value.documentId().equals(source)).findFirst().orElseThrow();
        assertEquals(8L, beforeSource.epoch());
        assertEquals(beforeSource.epoch(), afterSource.epoch(),
                "A deferred terminal tail cannot turn exact imported-event reference reencoding into a numbered source revision");
        assertNotEquals(beforeSource.blueId(), afterSource.afterBlueId(), "The fixture must really reencode the cyclic source");
        assertEquals(afterSource.epoch(), documents.require(work.sourceDocumentId()).epoch());
        assertTrue(documents.managedEpochReceipt(work.sourceDocumentId(), 9L).isEmpty(),
                "No C9 exists until genuine later source work; historical receipt evidence is not a new source emission");
        var anchor = documents.managedEpochReceipt(work.sourceDocumentId(), 8L).orElseThrow();
        assertEquals(work.sourceReceiptIdentity(), anchor.receiptIdentity());
        assertEquals(anchor.contractsTransitionReceiptIdentity(), cause.sourceRevisionReceiptIdentity());
        var pending = result.occurrenceBindings().stream()
                .filter(row -> row.occurrenceIdentity().equals(work.targetOccurrenceIdentity())).findFirst().orElseThrow();
        assertFalse(pending.active(), "The numbered carrier must not consume or activate its future representation tail");
        assertEquals(Long.valueOf(8L), pending.pendingHistoricalEpoch());
        assertEquals(new ManagedRepresentationCursor(cause.sourceRevisionReceiptIdentity(), cause.sourceRevisionReceiptIdentity(),
                successor.targetPositionIdentity(), null), pending.pendingRepresentationCursor());

        var execution = engine.contractsClosureAdapter().lastExecutionEvidence().orElseThrow();
        assertEquals(input.invocationIdentity(), execution.invocationIdentity());
        assertEquals(2, execution.workTrace().size());
        var rewrite = execution.workTrace().get(0);
        var imported = execution.workTrace().get(1);
        assertEquals(WorkKind.CONTAINING_REFERENCE_UPDATE, rewrite.kind());
        assertEquals(cause.causeIdentity(), rewrite.sourceOccurrenceIdentity());
        assertEquals(consumer, rewrite.targetDocumentId());
        assertEquals(WorkKind.EMBEDDED_EVENT, imported.kind());
        assertEquals(consumer, imported.targetDocumentId());
        assertTrue(cause.sourceTransitionReceipt().orElseThrow().emittedRootEvents().stream().anyMatch(event ->
                event.sourceDocumentId().equals(source) && event.occurrenceIdentity().equals(imported.sourceOccurrenceIdentity())
                        && Long.valueOf(event.occurrenceOrdinal()).equals(imported.occurrenceOrdinal())
                        && event.eventBlueId().equals(imported.eventBlueId())), "The imported event must be the original exact C8 receipt event");
        assertTrue(execution.workTrace().stream().noneMatch(step -> step.targetDocumentId().equals(source)),
                "There is no C-local work to hide as representation-only reencoding");
        assertTrue(result.managedTransitionReceipts().stream().filter(receipt -> receipt.documentId().equals(source))
                .allMatch(receipt -> receipt.emittedRootEvents().isEmpty()), "C must not republish its imported source event");
        var beforeConsumer = input.snapshot().managedDocument(consumer);
        var afterConsumer = result.resultingDocuments().stream().filter(value -> value.documentId().equals(consumer)).findFirst().orElseThrow();
        assertEquals(beforeConsumer.epoch() + 1L, afterConsumer.epoch(), "The actual A-local reaction still advances A");
        assertEquals(((Number) beforeConsumer.document().get("/observed")).longValue() + 1L,
                ((Number) afterConsumer.document().get("/observed")).longValue());

        var previous = result.rootedProjection().inputSnapshot();
        int changedBoundaries = 0;
        for (var boundary : result.rootedProjection().topologyBoundaries()) {
            var before = previous.managedDocument(source);
            var after = boundary.managedDocument(source);
            if (!before.blueId().equals(after.blueId())) {
                var explained = before.document();
                for (var row : previous.occurrences()) {
                    if (row.active() && row.sourceDocumentId().equals(source)) {
                        var replacement = NodePathEditor.getOrNull(after.document(), row.sourcePath());
                        assertNotNull(replacement);
                        NodePathEditor.put(explained, row.sourcePath(), replacement.clone());
                    }
                }
                assertEquals(NodeWireForm.get(explained), NodeWireForm.get(after.document()),
                        "Every exact C-body change is explained solely by its active reference paths");
                changedBoundaries++;
            }
            previous = boundary;
        }
        assertEquals(2, changedBoundaries, "Both the receipt rewrite and its exact imported event really reencode C");
        System.out.println("IMPORTED_C8_SOURCE_EPOCH_PRESERVED work=" + work.workIdentity()
                + " invocation=" + result.invocationIdentity() + " sourceReceipt=" + anchor.receiptIdentity()
                + " sourceEpoch=" + afterSource.epoch() + " gas=" + result.totalGas() + " gasTrace=" + result.gasTraceIdentity());
        return anchor.receiptIdentity();
    }
}
