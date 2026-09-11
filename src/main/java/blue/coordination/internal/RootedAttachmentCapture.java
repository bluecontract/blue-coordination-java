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

    /** A new current-at-activation occurrence does not acquire an interval from a later physical head. */
    static ManagedOccurrenceResolver.Resolution classifyAtBoundary(ContractsClosureAdapter.CohortInvocation current,
            ManagedOccurrenceResolver.Resolution resolution, Map<DocumentId, RootedDocumentView> selected,
            InMemoryDocumentStore documents) {
        if (current.rootedEvidence() == null) return resolution;
        ExternalOrderKey boundary = current.rootedEvidence().historicalOrigin() == null
                ? logicalBoundary(current.input(), documents.catchUpPlansSnapshot())
                : current.rootedEvidence().historicalOrigin().logicalBoundary();
        var occurrences = new java.util.ArrayList<ManagedOccurrenceResolver.ResolvedOccurrence>();
        boolean changed = false;
        for (var occurrence : resolution.resolvedOccurrences()) {
            // Existing reservations, historical cursors and retarget generations keep their exact contract.
            boolean existing = current.input().snapshot().occurrences().stream().anyMatch(row ->
                    row.sourceDocumentId().equals(occurrence.demand().sourceDocumentId())
                            && row.sourcePath().equals(occurrence.demand().sourcePath()));
            RootedDocumentView view = selected.get(occurrence.targetDocumentId());
            if (!existing && occurrence.historicalExisting() && occurrence.admittedSourceEpoch() >= 0L
                    && view == null && current.input().snapshot().contains(
                            ContractsClosureAdapter.closureId(occurrence.targetDocumentId()))) {
                // A second new path can name an already frozen source. Do not replace that
                // primary, or mistake its locally advanced representation for the source anchor.
                RootedDocumentView anchor = documents.require(occurrence.targetDocumentId()).rootedViewBefore(boundary);
                if (matchesFrozenSource(current.input().snapshot(), anchor, occurrence.targetDocumentId())) view = anchor;
            }
            if (!existing && occurrence.historicalExisting() && occurrence.admittedSourceEpoch() >= 0L
                    && view != null && isNumberedActivationPosition(occurrence, view, boundary, documents)) {
                occurrences.add(new ManagedOccurrenceResolver.ResolvedOccurrence(occurrence.demand(), occurrence.targetDocumentId(),
                        occurrence.expectedTargetBlueId(), ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING,
                        occurrence.admittedSourceEpoch(), null));
                changed = true;
            } else occurrences.add(occurrence);
        }
        return changed ? new ManagedOccurrenceResolver.Resolution(resolution.demands(), occurrences,
                resolution.resolvedExactNodes(), resolution.unresolvedDemands(), resolution.resolvedSelectorPaths()) : resolution;
    }

    private static boolean matchesFrozenSource(blue.language.processor.closure.AffectedClosureSnapshot input,
            RootedDocumentView anchor, DocumentId target) {
        var id = ContractsClosureAdapter.closureId(target);
        var selected = input.managedDocument(id);
        var source = anchor.retainedSnapshot().managedDocument(id);
        if (source == null || selected.epoch() != source.epoch() || !selected.blueId().equals(source.blueId())
                || selected.initialized() != source.initialized() || selected.terminated() != source.terminated()
                || selected.componentGeneration() != source.componentGeneration()
                || !blue.language.model.NodeWireForm.get(selected.document())
                        .equals(blue.language.model.NodeWireForm.get(source.document()))) return false;
        // Public-root presentation is intentionally not source position: the same source is
        // independently owned in its publication and borrowed in the receiving root's view.
        var localComponent = input.components().stream().filter(row -> row.orderedMemberDocumentIds().contains(id))
                .findFirst().orElseThrow();
        var sourceComponent = anchor.retainedSnapshot().components().stream()
                .filter(row -> row.orderedMemberDocumentIds().contains(id)).findFirst().orElseThrow();
        return localComponent.componentIdentity().equals(sourceComponent.componentIdentity())
                && localComponent.componentStateIdentity().equals(sourceComponent.componentStateIdentity())
                && ManagedOccurrenceInventory.sameRows(input.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(id)).toList(), anchor.snapshot().occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(id)).toList());
    }

    private static boolean isNumberedActivationPosition(ManagedOccurrenceResolver.ResolvedOccurrence occurrence,
            RootedDocumentView view, ExternalOrderKey boundary, InMemoryDocumentStore documents) {
        DocumentId id = occurrence.targetDocumentId();
        DocumentSession session = documents.require(id);
        // Compare the complete authenticated publication/position, not Java identity: a retained
        // view may be reconstructed. Equal source endpoint hashes alone do not identify a position.
        RootedDocumentView anchor = session.rootedViewBefore(boundary);
        if (!anchor.result().invocationIdentity().equals(view.result().invocationIdentity())
                || !anchor.result().inputClosureIdentity().equals(view.result().inputClosureIdentity())
                || !anchor.result().commitCompanion().companionIdentity()
                        .equals(view.result().commitCompanion().companionIdentity())
                || !anchor.snapshot().closureIdentity().equals(view.snapshot().closureIdentity())
                || !anchor.retainedSnapshot().closureIdentity().equals(view.retainedSnapshot().closureIdentity())
                || !java.util.Objects.equals(anchor.logicalBoundary(), view.logicalBoundary())) return false;
        var source = view.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(id));
        if (source == null || !source.initialized() || source.epoch() != occurrence.admittedSourceEpoch()
                || !source.blueId().equals(occurrence.expectedTargetBlueId())) return false;
        var evidence = documents.managedEpochEvidence(id, source.epoch());
        if (evidence.receipt() == null || evidence.transitionReceipt() == null)
            throw new ProjectionUnavailableException("Current-at-activation attachment lacks its exact source receipt");
        var receipt = evidence.receipt();
        // A same-epoch representation tail is not the numbered position, even if its epoch is equal.
        if (!receipt.afterBlueId().equals(source.blueId())
                || !evidence.transitionReceipt().sourceInvocationIdentity().equals(view.result().invocationIdentity())) return false;
        if (!receipt.documentId().equals(id) || receipt.epoch() != source.epoch()
                || !receipt.contractsTransitionReceiptIdentity().equals(evidence.transitionReceipt().transitionReceiptIdentity()))
            throw new ProjectionUnavailableException("Current-at-activation attachment changed its original source publication");
        view.requirePublishedHead(id, source.epoch(), source.blueId());
        anchor.requirePublishedHead(id, source.epoch(), source.blueId());
        session.rootedPublicationPrefix(anchor);
        return true;
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
