package blue.coordination.internal;

import blue.coordination.internal.ContractsClosureAdapter.ProjectionUnavailableException;

import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ExternalEventCause;
import java.util.ArrayDeque;
import java.util.Collection;
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
            Collection<DocumentId> targets, InMemoryDocumentStore documents) {
        if (current.rootedEvidence() == null) return Map.of();
        ExternalOrderKey boundary = current.rootedEvidence().historicalOrigin() == null
                ? logicalBoundary(current.input(), documents.catchUpPlansSnapshot())
                : current.rootedEvidence().historicalOrigin().logicalBoundary();
        Map<DocumentId, RootedDocumentView> selected = new LinkedHashMap<>();
        for (DocumentId target : targets) {
            if (current.input().snapshot().contains(ContractsClosureAdapter.closureId(target))) continue;
            RootedDocumentView view = documents.require(target).rootedViewBefore(boundary);
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
}
