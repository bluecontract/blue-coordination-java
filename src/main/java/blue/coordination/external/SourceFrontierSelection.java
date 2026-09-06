package blue.coordination.external;

import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import java.util.*;

/**
 * Complete frontier admission owned by Coordination. Unlike Language's selected
 * exact view, this closed value is created only from a canonical complete boundary
 * and its authenticated terminal/successful receipts. No host scalar/body factory.
 */
public final class SourceFrontierSelection {
    private final CanonicalSourceHistory.Boundary boundary;
    private final SourceFrontierView selectedView;
    private SourceFrontierSelection(CanonicalSourceHistory.Boundary boundary, SourceFrontierView selectedView) {
        this.boundary = boundary; this.selectedView = selectedView;
    }

    public static SourceFrontierSelection fromBoundary(SameOriginAttachmentPolicy.Selection selection,
            CanonicalSourceHistory.Boundary boundary, String authenticatedTerminalReceipt,
            FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        Objects.requireNonNull(selection); Objects.requireNonNull(boundary);
        if (selection.mode() != SameOriginAttachmentPolicy.Mode.FROM_FRONTIER
                || !selection.frontier().orElseThrow().equals(boundary.cut())
                || !selection.targetLineage().equals(boundary.cursor().source()))
            throw invalid("Complete source boundary differs from the frozen frontier selection");
        var successful = boundary.cursor().successfulView().orElseThrow(() -> invalid("Frontier lacks a usable canonical initialization"));
        if (boundary.cursor().initialView().isEmpty()) throw invalid("Frontier lacks its canonical birth basis");
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        var terminal = OperationReceiptCodec.decode(authenticatedTerminalReceipt, decoder);
        var valid = OperationReceiptCodec.decode(successful.receiptIdentity(), decoder);
        if (!terminal.operationId().equals(boundary.cursor().semanticPredecessor().orElseThrow())
                || terminal.disposition() != CoordinationCore.Disposition.CONSUMED
                || valid.status() != ProcessorStatus.SUCCESS || valid.disposition() != CoordinationCore.Disposition.CONSUMED)
            throw invalid("Receipt does not establish the boundary's exact terminal and successful operations");
        for (var receipt : List.of(terminal, valid)) {
            var state = receipt.states().stream().filter(value -> value.lineage().equals(successful.source())).findFirst()
                    .orElseThrow(() -> invalid("Frontier receipt does not own the source"));
            if (!state.initialized() || !state.afterBlueId().equals(successful.blueId()) || state.afterEpoch() != successful.epoch())
                throw invalid("Frontier receipt differs from its exact successful view");
        }
        var program = OperationReceiptCodec.restoreSourceProgram(valid, decoder);
        var state = program.sourceResults().stream().filter(value -> value.documentId().equals(successful.source())).findFirst().orElseThrow();
        var component = program.sourceAfterComponents().stream().filter(value -> value.orderedMemberDocumentIds().contains(successful.source())).findFirst()
                .orElseThrow(() -> invalid("Selected frontier lacks original source component evidence"));
        var pin = ManagedReadPin.fromExactEvidence(successful.source(), successful.blueId(), state.document(), component.completeCyclicProof());
        Optional<SourceOperationFailure> failure = terminal.status() == ProcessorStatus.SUCCESS ? Optional.empty()
                : Optional.of(OperationReceiptCodec.restoreSourceFailure(terminal, decoder));
        if (failure.isEmpty() && !valid.operationId().equals(terminal.operationId()))
            throw invalid("Successful terminal is not the selected latest successful source operation");
        var view = SourceFrontierView.fromRetainedEvidence(selection, program, failure, pin);
        return new SourceFrontierSelection(boundary, view);
    }

    public CanonicalSourceHistory.Boundary boundary() { return boundary; }
    public SourceFrontierView selectedView() { return selectedView; }
    void verifySelection(SameOriginAttachmentPolicy.Selection selection) {
        var actual = selectedView.selection();
        if (actual.mode() != selection.mode() || !actual.creatorLineage().equals(selection.creatorLineage())
                || !actual.occurrenceIdentity().equals(selection.occurrenceIdentity()) || !actual.targetLineage().equals(selection.targetLineage())
                || !actual.suppliedExactRefBlueId().equals(selection.suppliedExactRefBlueId()) || !actual.frontier().equals(selection.frontier()))
            throw invalid("Frontier admission belongs to another original creator selection");
    }
    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
