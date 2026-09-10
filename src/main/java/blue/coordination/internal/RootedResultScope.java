package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.RootedPublicationProjection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Closed projection of a processor-produced result, never a host-supplied allowlist. */
final class RootedResultScope {
    private RootedResultScope() { }

    static List<DocumentId> members(ClosureProcessResult result) {
        return documents(result).stream().map(d -> DocumentId.of(d.documentId().value()))
                .sorted(EmbeddingBinding.DOCUMENT_ORDER).toList();
    }

    static List<ResultingDocument> documents(ClosureProcessResult result) {
        return result.rootedProjection() == null ? result.resultingDocuments()
                : result.rootedProjection().ownedDocuments();
    }

    static Map<DocumentId, ResultingDocument> requireDocuments(ClosureProcessResult result,
            Set<DocumentId> expectedMembers) {
        Map<DocumentId, ResultingDocument> selected = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (ResultingDocument document : documents(result)) {
            if (selected.putIfAbsent(DocumentId.of(document.documentId().value()), document) != null) {
                throw new IllegalArgumentException("Publication repeats a document");
            }
        }
        if (!selected.keySet().equals(expectedMembers)) {
            throw new IllegalArgumentException("Publication must contain exactly the processor-derived owners");
        }
        return selected;
    }

    static List<ComponentSnapshot> components(ClosureProcessResult result) {
        return result.rootedProjection() == null ? result.resultingComponents()
                : result.rootedProjection().ownedComponents();
    }

    static List<CheckpointWrite> checkpoints(ClosureProcessResult result) {
        return result.rootedProjection() == null ? result.checkpointWrites()
                : result.rootedProjection().ownedCheckpointWrites();
    }

    static List<PublicEventOccurrence> events(ClosureProcessResult result) {
        return result.rootedProjection() == null ? result.publicEvents()
                : result.rootedProjection().ownedPublicEvents();
    }

    static long processTransitionCount(ClosureProcessResult result) {
        RootedPublicationProjection scope = result.rootedProjection();
        return result.managedTransitionReceipts().stream()
                .filter(receipt -> scope == null || scope.owns(receipt.documentId()))
                .count();
    }

    static RootedPublicationProjection require(ClosureProcessResult result, RootedInvocationEvidence expected) {
        RootedPublicationProjection scope = result.rootedProjection();
        if (scope == null || expected == null || !result.commits()
                || !scope.context().identity().equals(expected.context().identity())
                || !scope.deliveryBasisIdentity().equals(expected.deliveryBasisIdentity())
                || !scope.invocationIdentity().equals(expected.invocationIdentity())
                || !scope.companionIdentity().equals(expected.context().commitCompanionIdentity(
                        result.commitCompanion().companionIdentity(), expected.invocationIdentity()))) {
            throw new IllegalArgumentException("Rooted publication lacks its exact processor-derived authority");
        }
        return scope;
    }
}
