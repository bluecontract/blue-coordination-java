package blue.coordination.external;

import blue.language.processor.*;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceFrontierSelectionTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void exactCompleteBoundarySelectsItsViewNotALaterHeadAndColdRetainsTypedFrontier() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = f.source(); var e15 = CanonicalSourceHistoryTest.input("first", 15, "account");
            var f20 = CanonicalSourceHistoryTest.input("frontier", 20, "other").order();
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, f20, List.of(e15), 21);
            var creator = f.authored("name: creator", Map.of()); var selection = selection(f, creator, source, f20);
            var admitted = SourceFrontierSelection.fromBoundary(selection, prepared.boundary(), prepared.steps().get(1).receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(BigInteger.valueOf(5), admitted.selectedView().selectedView().document().getProperties().get("counter").getValue());
            assertEquals(1, admitted.selectedView().successfulEpoch());
            assertEquals(prepared.boundary().cursor().semanticPredecessor().orElseThrow(), admitted.selectedView().terminalOperationIdentity());
            var encoder = new FrozenNodeEvidenceCodec.Encoder(f.blobs::put, LIMITS);
            var hash = SourceFrontierViewCodec.encode(admitted.selectedView(), encoder);
            var cold = SourceFrontierViewCodec.decode(hash, new FrozenNodeEvidenceCodec.Decoder(f.blobs::get, LIMITS));
            assertEquals(admitted.selectedView().identity(), cold.identity()); assertEquals(Optional.of(f20), cold.selection().frontier());
            var later = selection(f, creator, source, CanonicalSourceHistoryTest.input("later", 25, "other").order());
            assertThrows(InvalidExecutionEvidenceException.class, () -> SourceFrontierSelection.fromBoundary(later, prepared.boundary(),
                    prepared.steps().get(1).receiptIdentity(), f.blobs::get, LIMITS));
            assertThrows(InvalidExecutionEvidenceException.class, () -> SourceFrontierSelection.fromBoundary(selection, prepared.boundary(),
                    prepared.steps().get(0).receiptIdentity(), f.blobs::get, LIMITS));
        }
    }

    @Test void terminalFailureKeepsEarlierSuccessfulViewAndCannotBecomeAValidProgram() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = f.authored("""
                    name: Failing source at the frontier
                    counter: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      update:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: remove, path: /counter/scalar-child}]
                    """, Map.of());
            var f20 = CanonicalSourceHistoryTest.input("frontier", 20, "other").order();
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, f20,
                    List.of(CanonicalSourceHistoryTest.input("failed", 15, "account")), 21);
            var creator = f.authored("name: creator", Map.of()); var selection = selection(f, creator, source, f20);
            var admitted = SourceFrontierSelection.fromBoundary(selection, prepared.boundary(), prepared.steps().get(1).receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(0, admitted.selectedView().successfulEpoch());
            assertNotEquals(admitted.selectedView().successfulOperationIdentity(), admitted.selectedView().terminalOperationIdentity());
            assertEquals(BigInteger.ZERO, admitted.selectedView().selectedView().document().getProperties().get("counter").getValue());
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSourceProgram(
                    prepared.steps().get(1).receiptIdentity(), f.blobs::get, LIMITS));
        }
    }

    @Test void explicitFrontierIsMandatoryOnlyForItsModeAndCannotBeAfterActualCreation() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = f.source(); var creator = f.authored("name: creator", Map.of());
            var f20 = CanonicalSourceHistoryTest.input("frontier", 20, "other").order();
            var choice = selection(f, creator, source, f20);
            assertThrows(IllegalArgumentException.class, () -> new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_FRONTIER,
                    creator.documentId(), choice.occurrenceIdentity(), source.documentId(), source.blueId()));
            assertThrows(IllegalArgumentException.class, () -> new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW,
                    creator.documentId(), choice.occurrenceIdentity(), source.documentId(), source.blueId(), f20));
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, f20, List.of(), 21);
            var view = SourceFrontierSelection.fromBoundary(choice, prepared.boundary(), prepared.steps().get(0).receiptIdentity(), f.blobs::get, LIMITS).selectedView();
            var entry = CanonicalSourceHistoryTest.input("earlier-creator", 10, "other");
            var cut = ClosureEvidenceFactory.affectedClosure(0, List.of(creator), List.of(), List.of(ClosureEvidenceFactory.acyclicComponent(creator)), List.of(creator.documentId()));
            var cause = ClosureEvidenceFactory.externalCause(entry.entry().copyNode(), entry.entry().blueId(), entry.order(), f.core.environment().externalOrderPolicyIdentity());
            var invocation = ClosureEvidenceFactory.processClosure(cut, cause, List.of(), f.core.executionPolicy(), f.core.environment());
            assertThrows(IllegalArgumentException.class, () -> view.verifyInvocation(invocation));
        }
    }

    static SameOriginAttachmentPolicy.Selection selection(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot creator,
            ManagedDocumentSnapshot source, ExternalOrderKey frontier) {
        var row = f.binding(creator, source);
        return new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_FRONTIER, creator.documentId(), row.occurrenceIdentity(),
                source.documentId(), source.blueId(), frontier);
    }
}
