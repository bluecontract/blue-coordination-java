package blue.coordination.internal;

import blue.coordination.internal.ContractsClosureAdapter.ProjectionUnavailableException;

import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ExternalEventCause;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Selects complete retained source views at an attachment's logical input boundary. */
final class RootedAttachmentCapture {
    private RootedAttachmentCapture() { }

    static ExternalOrderKey logicalBoundary(blue.language.processor.closure.ClosureInvocationInput input,
            CatchUpPlanStore plans) {
        if (input.cause() instanceof ExternalEventCause external) return external.sourceOrder();
        String occurrence;
        if (input.cause() instanceof blue.language.processor.closure.ManagedRevisionCause revision)
            occurrence = revision.targetOccurrenceIdentity();
        else if (input.cause() instanceof blue.language.processor.closure.ManagedRepresentationCause representation)
            occurrence = representation.targetOccurrenceIdentity();
        else throw new IllegalArgumentException("Rooted processing lacks a supported logical cause");
        var row = input.snapshot().occurrences().stream().filter(value -> value.occurrenceIdentity().equals(occurrence))
                .findFirst().orElseThrow();
        var matches = plans.plansForOccurrence(occurrence).plans().stream()
                .filter(plan -> plan.activationGeneration() == row.activationGeneration()
                        && plan.consumerDocumentId().value().equals(row.sourceDocumentId().value())).toList();
        if (matches.size() != 1) throw new ProjectionUnavailableException("Historical attachment lacks its exact owning barrier");
        return plans.barrier(matches.get(0).barrierIdentity()).barrier().causeOrder();
    }

    static Map<DocumentId, RootedDocumentView> select(ContractsClosureAdapter.CohortInvocation current,
            ManagedOccurrenceResolver.Resolution resolution, InMemoryDocumentStore documents) {
        if (current.rootedEvidence() == null) return Map.of();
        ExternalOrderKey boundary = current.rootedEvidence().historicalOrigin() == null
                ? logicalBoundary(current.input(), documents.catchUpPlansSnapshot())
                : current.rootedEvidence().historicalOrigin().logicalBoundary();
        Map<DocumentId, RootedDocumentView> selected = new LinkedHashMap<>();
        for (DocumentId target : resolution.existingTargets()) {
            if (current.input().snapshot().contains(ContractsClosureAdapter.closureId(target))) continue;
            RootedDocumentView view = documents.require(target).rootedViewBefore(boundary);
            view = rejectionWitness(current, resolution, target, view, documents);
            Set<DocumentId> visited = new LinkedHashSet<>();
            var pending = new ArrayDeque<DocumentId>();
            pending.add(target);
            while (!pending.isEmpty()) {
                DocumentId id = pending.removeFirst();
                if (!visited.add(id)) continue;
                var exact = view.snapshot().managedDocument(ContractsClosureAdapter.closureId(id));
                if (exact == null) throw new ProjectionUnavailableException("Retained attachment view has incomplete forward evidence");
                var existing = current.input().snapshot().managedDocument(exact.documentId());
                RootedDocumentView prior = selected.get(id);
                if (existing != null && !existing.blueId().equals(exact.blueId())) {
                    // Keep the calculating primary. Its older exact role remains
                    // inside the complete retained source proof; the Contracts
                    // read-expansion factory independently authenticates that role.
                    continue;
                }
                if (prior != null && !prior.snapshot().managedDocument(exact.documentId()).blueId().equals(exact.blueId())) {
                    throw new ProjectionUnavailableException("Historical witness needs a separate exact view for " + id
                            + " at=" + boundary + " target=" + target + " proof=" + exact.blueId()
                            + " current=" + (existing == null ? "absent" : existing.blueId())
                            + " prior=" + prior.snapshot().managedDocument(exact.documentId()).blueId());
                }
                if (existing == null) selected.putIfAbsent(id, view);
                view.snapshot().occurrences().stream().filter(row -> row.sourceDocumentId().equals(exact.documentId()))
                        .forEach(row -> pending.addLast(ContractsClosureAdapter.coordinationId(row.targetDocumentId())));
            }
        }
        return Map.copyOf(selected);
    }

    /** An invalid new foreign target needs its selected exact proof, not a calculating source head. */
    private static RootedDocumentView rejectionWitness(ContractsClosureAdapter.CohortInvocation current,
            ManagedOccurrenceResolver.Resolution resolution, DocumentId target, RootedDocumentView boundaryView,
            InMemoryDocumentStore documents) {
        RootedDocumentView selected = null;
        for (var occurrence : resolution.resolvedOccurrences()) {
            if (!target.equals(occurrence.targetDocumentId()) || occurrence.admittedSourceEpoch() < 0L) continue;
            var reservation = current.input().snapshot().occurrences().stream().filter(row ->
                    row.sourceDocumentId().equals(occurrence.demand().sourceDocumentId())
                            && row.sourcePath().equals(occurrence.demand().sourcePath())).findFirst().orElse(null);
            if (reservation == null || reservation.active() || reservation.pendingHistoricalEpoch() != null
                    || reservation.targetDocumentId().equals(ContractsClosureAdapter.closureId(target))) continue;
            var reservedSource = current.input().snapshot().managedDocument(reservation.targetDocumentId());
            if (reservedSource == null || !reservedSource.initialized()) continue;
            var evidence = documents.managedEpochEvidence(target, occurrence.admittedSourceEpoch());
            if (evidence.receipt() == null || evidence.transitionReceipt() == null
                    || !evidence.receipt().documentId().equals(target)
                    || evidence.receipt().epoch() != occurrence.admittedSourceEpoch()
                    || !evidence.receipt().afterBlueId().equals(occurrence.expectedTargetBlueId())
                    || !evidence.receipt().contractsTransitionReceiptIdentity()
                            .equals(evidence.transitionReceipt().transitionReceiptIdentity()))
                throw new ProjectionUnavailableException("Inactive retarget lacks its exact retained source publication");
            var session = documents.require(target);
            var witness = session.rootedViewForInvocation(evidence.transitionReceipt().sourceInvocationIdentity());
            witness.requirePublishedHead(target, occurrence.admittedSourceEpoch(), occurrence.expectedTargetBlueId());
            if (!session.rootedPublicationPrefix(boundaryView).contains(witness.result().invocationIdentity()))
                throw new ProjectionUnavailableException("Inactive retarget source proof is after its logical boundary");
            if (selected != null && selected != witness)
                throw new ProjectionUnavailableException("Inactive retarget requires incompatible exact source witnesses");
            selected = witness;
        }
        return selected == null ? boundaryView : selected;
    }
}
