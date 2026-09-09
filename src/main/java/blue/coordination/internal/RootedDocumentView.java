package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.RootedProcessingContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable selected dependency views and local channel progress owned by a committed root. */
final class RootedDocumentView {
    private final ClosureProcessResult result;
    private final blue.language.processor.ExternalOrderKey logicalBoundary;
    private final AffectedClosureSnapshot snapshot;
    private final ClosureSubscriptionInventory subscriptions;
    private final Map<DocumentId, List<SubscriptionDelta.Entry>> routes;

    RootedDocumentView(ClosureProcessResult result, ClosureSubscriptionInventory subscriptions,
            Map<DocumentId, List<SubscriptionDelta.Entry>> routes,
            blue.language.processor.ExternalOrderKey logicalBoundary) {
        this.result = Objects.requireNonNull(result, "result");
        this.logicalBoundary = logicalBoundary;
        if (!result.commits() || result.commitCompanion() == null
                || !RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY.equals(
                        result.commitCompanion().contractsSpecificationIdentity())) {
            throw new IllegalArgumentException("A rooted view requires a successful result under the rooted profile");
        }
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        Map<DocumentId, List<SubscriptionDelta.Entry>> selected = new LinkedHashMap<>();
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        List<blue.language.processor.closure.DocumentId> publicRoots = new ArrayList<>();
        for (var document : result.resultingDocuments()) {
            DocumentId id = DocumentId.of(document.documentId().value());
            selected.put(id, List.copyOf(Objects.requireNonNull(routes.get(id), "selected routes for " + id)));
            documents.add(new ManagedDocumentSnapshot(document.documentId(), document.afterBlueId(), document.document(),
                    document.initialized(), document.terminated(), document.publicRoot(), document.epoch(),
                    document.componentGeneration()));
            if (document.publicRoot()) publicRoots.add(document.documentId());
        }
        if (!selected.keySet().equals(routes.keySet())) {
            throw new IllegalArgumentException("A rooted view cannot retain unrelated route surfaces");
        }
        this.routes = Map.copyOf(selected);
        this.snapshot = ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), documents,
                result.occurrenceBindings(), result.resultingComponents(), publicRoots);
        if (!snapshot.closureIdentity().equals(result.outputClosureIdentity())) {
            throw new IllegalArgumentException("Rooted view differs from the complete computed result");
        }
    }

    void requireProcessingBoundary(blue.language.processor.closure.ClosureInvocationInput input,
            CatchUpPlanStore plans) {
        if (!RootedAttachmentCapture.logicalBoundary(input, plans).equals(logicalBoundary)) {
            throw new IllegalStateException("Rooted view changed the frozen input's logical boundary");
        }
    }

    blue.language.processor.ExternalOrderKey logicalBoundary() { return logicalBoundary; }
    ClosureProcessResult result() { return result; }
    AffectedClosureSnapshot snapshot() { return snapshot; }
    ClosureSubscriptionInventory subscriptions() { return subscriptions; }
    List<SubscriptionDelta.Entry> routes(DocumentId documentId) {
        return Objects.requireNonNull(routes.get(documentId), "No selected route view for " + documentId);
    }

    void requireOwnerHead(DocumentId owner, String exactHead) {
        ManagedDocumentSnapshot member = snapshot.managedDocument(ContractsClosureAdapter.closureId(owner));
        if (member == null || !member.blueId().equals(exactHead)) {
            throw new IllegalArgumentException("A root cannot install another exact state's selected view");
        }
        if (result.rootedProjection() != null
                && !result.rootedProjection().owns(ContractsClosureAdapter.closureId(owner))) {
            throw new IllegalArgumentException("A local dependency cannot acquire an authoritative selected view");
        }
    }
}
