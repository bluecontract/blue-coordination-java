package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import java.util.Objects;
import java.util.stream.Collectors;

/** A retained body alone is not proof that an exclusive source requirement is complete. */
record RootedSourceHistoryAssessment(boolean satisfied, String requiredEvidence) {
    static RootedSourceHistoryAssessment assess(DocumentId source, DocumentSession retained,
            ExternalOrderKey cutoff, InMemoryDocumentStore documents, ContractsClosureAdapter adapter,
            TimelineJournal journal, java.util.Set<DocumentId> observers) {
        var view = retained.rootedViewBefore(cutoff);
        Objects.requireNonNull(view, "Source sufficiency requires an authenticated retained view");
        String pending = RootedJoinPrerequisites.pendingBefore(source, view, cutoff, documents);
        if (pending != null) return new RootedSourceHistoryAssessment(false, pending);
        var id = ContractsClosureAdapter.closureId(source);
        var owners = view.snapshot().components().stream()
                .filter(component -> component.orderedMemberDocumentIds().contains(id)).findFirst().orElseThrow()
                .orderedMemberDocumentIds().stream().map(ContractsClosureAdapter::coordinationId).collect(Collectors.toSet());
        // The complete pending-join query is semantic protection, not generic incoming-edge validation.
        for (var fence : RootedJoinEligibility.captureForView(documents, source, view))
            if (fence.boundary().compareTo(cutoff) < 0 && !java.util.Collections.disjoint(fence.owners(), owners))
                return new RootedSourceHistoryAssessment(false, "Earlier pending join requires live source assessment");
        if (adapter.sourceHasEligibleInputBefore(source, view, cutoff, journal,
                target -> documents.retainedSourceBefore(target, retained, cutoff, observers).orElseThrow(() ->
                        new ContractsClosureAdapter.ProjectionUnavailableException("Missing retained operation-target lineage " + target))))
            return new RootedSourceHistoryAssessment(false, "Earlier accepted source input requires processing");
        return new RootedSourceHistoryAssessment(true, null);
    }
}
