package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authenticates ordered same-epoch steps from the original atomic publications. */
final class ManagedRepresentationHistory {
    private final InMemoryDocumentStore documents;
    ManagedRepresentationHistory(InMemoryDocumentStore documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    Chain at(DocumentId documentId, long epoch) {
        DocumentSession session = documents.find(documentId).orElseThrow(() ->
                new IllegalArgumentException("Historical representation source is absent"));
        ManagedEpochReceipt anchor = documents.managedEpochEvidence(documentId, epoch).receipt();
        if (anchor == null) throw new IllegalArgumentException("Representation epoch anchor is unavailable");
        List<ManagedRepresentationTransition> transitions = new ArrayList<>();
        String predecessorPosition = anchor.receiptIdentity();
        String beforeBlueId = anchor.afterBlueId();
        for (DocumentSession.ComponentRepresentationTransition row : session.representationTransitions()) {
            if (row.epoch() != epoch) continue;
            if (!row.beforeBlueId().equals(beforeBlueId) || row.originalPublicationIdentity() == null) {
                throw new IllegalArgumentException("Unproved ordered representation predecessor");
            }
            ContractsClosurePublicationReceipt publication = documents
                    .closurePublicationReceipt(row.originalPublicationIdentity())
                    .filter(ContractsClosurePublicationReceipt::commits)
                    .orElseThrow(() -> new IllegalArgumentException("Original representation commit is unavailable"));
            if (!publication.documentIds().contains(documentId)
                    || publication.managedSurfaceEvidence().originalInvocation() == null) {
                throw new IllegalArgumentException("Original representation classification input is unavailable");
            }
            ManagedRepresentationTransition proved = new ManagedRepresentationTransition(
                    ContractsClosureAdapter.closureId(documentId), epoch,
                    anchor.receiptIdentity(), predecessorPosition,
                    publication.managedSurfaceEvidence().originalInvocation(),
                    publication.attempt().processResult(), row.transitionReceiptIdentity());
            if (!proved.transitionReceipt().beforeBlueId().equals(row.beforeBlueId())
                    || !proved.transitionReceipt().afterBlueId().equals(row.afterBlueId())) {
                throw new IllegalArgumentException("Representation commit differs from its durable source history");
            }
            transitions.add(proved);
            predecessorPosition = proved.positionIdentity();
            beforeBlueId = row.afterBlueId();
        }
        ManagedEpochReceipt next = documents.managedEpochEvidence(documentId, epoch + 1L).receipt();
        if (next != null && !next.beforeBlueId().orElseThrow().equals(beforeBlueId)) {
            throw new IllegalArgumentException("Unproved gap before the next immutable source receipt");
        }
        if (next == null && (epoch != session.epoch()
                || !session.currentRepresentation().blueId().equals(beforeBlueId))) {
            throw new IllegalArgumentException("Unproved terminal representation head");
        }
        return new Chain(documentId, epoch, anchor, List.copyOf(transitions),
                predecessorPosition, next == null ? null : next.contractsTransitionReceiptIdentity());
    }

    /** Captured tails never grow to chase representation changes made by their own reconciliation. */
    Chain atCaptured(DocumentId documentId, long epoch, ManagedRepresentationCursor cursor) {
        Chain full = at(documentId, epoch);
        if (cursor == null) return full;
        if (!full.anchor().receiptIdentity().equals(cursor.anchorReceiptIdentity())
                || !Objects.equals(full.nextRevisionReceiptIdentity(), cursor.nextRevisionReceiptIdentity())) {
            throw new IllegalArgumentException("Captured representation anchor or immutable next revision changed");
        }
        int target = -1;
        for (int i = 0; i < full.transitions().size(); i++) {
            if (full.transitions().get(i).positionIdentity().equals(cursor.targetPositionIdentity())) target = i;
        }
        if (target < 0) throw new IllegalArgumentException("Captured representation target was not committed");
        if (cursor.nextRevisionReceiptIdentity() != null && target != full.transitions().size() - 1) {
            throw new IllegalArgumentException("Intermediate target is not the exact next revision predecessor");
        }
        return new Chain(documentId, epoch, full.anchor(), List.copyOf(full.transitions().subList(0, target + 1)),
                cursor.targetPositionIdentity(), cursor.nextRevisionReceiptIdentity());
    }

    /** Host admission proves both original publication membership and the captured next position. */
    void verifyCause(ManagedRepresentationCause cause, ManagedOccurrenceBinding occurrence) {
        if (occurrence.active() || occurrence.pendingHistoricalEpoch() == null
                || !occurrence.targetDocumentId().equals(cause.childDocumentId())
                || occurrence.pendingHistoricalEpoch().longValue() != cause.fromEpoch()
                || !occurrence.occurrenceIdentity().equals(cause.targetOccurrenceIdentity())) {
            throw new IllegalArgumentException("Representation cause does not own the pending occurrence");
        }
        Chain chain = atCaptured(DocumentId.of(cause.childDocumentId().value()), cause.fromEpoch(),
                occurrence.pendingRepresentationCursor());
        var next = chain.next(occurrence.pendingRepresentationCursor(), occurrence.expectedTargetBlueId())
                .orElseThrow(() -> new IllegalArgumentException("Captured representation chain is already complete"));
        if (!next.positionIdentity().equals(cause.transition().positionIdentity())
                || !chain.targetPositionIdentity().equals(cause.targetPositionIdentity())
                || !Objects.equals(chain.nextRevisionReceiptIdentity(), cause.nextRevisionReceiptIdentity())) {
            throw new IllegalArgumentException("Representation cause skips or changes its authenticated history target");
        }
        verifySupplied(cause.transition());
    }

    /** Supplied recomputable evidence has no authority without exact durable membership. */
    void verifySupplied(ManagedRepresentationTransition supplied) {
        Chain chain = at(DocumentId.of(supplied.documentId().value()), supplied.epoch());
        ManagedRepresentationTransition actual = chain.transitions().stream()
                .filter(row -> row.positionIdentity().equals(supplied.positionIdentity()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Supplied representation position was not committed"));
        if (!actual.originalInput().invocationIdentity().equals(supplied.originalInput().invocationIdentity())
                || !actual.originalResult().outputClosureIdentity().equals(supplied.originalResult().outputClosureIdentity())
                || !actual.originalResult().platformCommitCompanion().companionIdentity().equals(
                        supplied.originalResult().platformCommitCompanion().companionIdentity())) {
            throw new IllegalArgumentException("Supplied representation evidence differs from the durable commit");
        }
    }

    record Chain(DocumentId documentId, long epoch, ManagedEpochReceipt anchor,
            List<ManagedRepresentationTransition> transitions, String targetPositionIdentity,
            String nextRevisionReceiptIdentity) {
        Optional<ManagedRepresentationTransition> next(ManagedRepresentationCursor cursor, String currentBlueId) {
            String position = cursor == null ? anchor.receiptIdentity() : cursor.positionIdentity();
            if (cursor != null && (!cursor.anchorReceiptIdentity().equals(anchor.receiptIdentity())
                    || !cursor.targetPositionIdentity().equals(targetPositionIdentity)
                    || !Objects.equals(cursor.nextRevisionReceiptIdentity(), nextRevisionReceiptIdentity))) {
                throw new IllegalArgumentException("Historical representation target changed");
            }
            String expected = anchor.afterBlueId();
            for (ManagedRepresentationTransition transition : transitions) {
                if (position.equals(transition.predecessorPositionIdentity())) {
                    if (!currentBlueId.equals(expected)) throw new IllegalArgumentException("Historical representation exact predecessor changed");
                    return Optional.of(transition);
                }
                expected = transition.transitionReceipt().afterBlueId();
            }
            if (!position.equals(targetPositionIdentity) || !currentBlueId.equals(expected)) {
                throw new IllegalArgumentException("Historical representation position is unknown or incomplete");
            }
            return Optional.empty();
        }
    }
}
