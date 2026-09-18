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
    private final Map<DocumentId, InMemoryDocumentStore.DocumentHead> publishedHeads;
    private final AffectedClosureSnapshot retainedSnapshot;

    RootedDocumentView(ClosureProcessResult result, ClosureSubscriptionInventory subscriptions,
            Map<DocumentId, List<SubscriptionDelta.Entry>> routes,
            blue.language.processor.ExternalOrderKey logicalBoundary) {
        this.result = Objects.requireNonNull(result, "result");
        this.publishedHeads = Map.of();
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
        // Retain the processor-verified roles as well as the exact values. Rebuilding a
        // rooted result as a flat set of current heads erases immutable historical
        // witness provenance (including an authenticated return reference to an older owner).
        this.snapshot = result.rootedProjection() == null
                ? ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), documents,
                    result.occurrenceBindings(), result.resultingComponents(), publicRoots)
                : result.rootedProjection().resultingSnapshot();
        if (!snapshot.closureIdentity().equals(result.outputClosureIdentity())) {
            throw new IllegalArgumentException("Rooted view differs from the complete computed result");
        }
        this.retainedSnapshot = snapshot;
    }

    private RootedDocumentView(RootedDocumentView calculated,
            Map<DocumentId, InMemoryDocumentStore.DocumentHead> publishedHeads) {
        this.result = calculated.result;
        this.logicalBoundary = calculated.logicalBoundary;
        this.snapshot = calculated.snapshot;
        this.subscriptions = calculated.subscriptions;
        this.routes = calculated.routes;
        this.publishedHeads = Map.copyOf(publishedHeads);
        this.publishedHeads.forEach((owner, head) -> requireOwnerHead(owner, head.blueId()));
        Map<blue.language.processor.closure.DocumentId, Long> epochs = new LinkedHashMap<>();
        this.publishedHeads.forEach((id, head) -> epochs.put(ContractsClosureAdapter.closureId(id), head.epoch()));
        this.retainedSnapshot = result.rootedProjection() == null ? snapshot
                : ClosureEvidenceFactory.rootedRetainedSnapshot(result, epochs);
    }

    /** Bound only to the fully validated replacement sessions, inside atomic publication. */
    RootedDocumentView withPublishedHeads(Map<DocumentId, InMemoryDocumentStore.DocumentHead> heads) {
        return new RootedDocumentView(this, heads);
    }

    record StoredState(ClosureProcessResult result, blue.language.processor.ExternalOrderKey logicalBoundary,
            AffectedClosureSnapshot snapshot, ClosureSubscriptionInventory subscriptions,
            Map<DocumentId, List<SubscriptionDelta.Entry>> routes,
            Map<DocumentId, InMemoryDocumentStore.DocumentHead> publishedHeads,
            AffectedClosureSnapshot retainedSnapshot) { }

    StoredState storedState() {
        return new StoredState(result, logicalBoundary, snapshot, subscriptions, routes, publishedHeads, retainedSnapshot);
    }

    /** The Language codecs have restored the original roles and binding; never reconstruct them from heads. */
    static RootedDocumentView restoreStored(StoredState state) { return new RootedDocumentView(state); }

    private RootedDocumentView(StoredState state) {
        this.result = Objects.requireNonNull(state.result());
        this.logicalBoundary = state.logicalBoundary();
        this.snapshot = Objects.requireNonNull(state.snapshot());
        this.retainedSnapshot = Objects.requireNonNull(state.retainedSnapshot());
        this.subscriptions = Objects.requireNonNull(state.subscriptions());
        var copiedRoutes = new LinkedHashMap<DocumentId, List<SubscriptionDelta.Entry>>();
        state.routes().forEach((id, rows) -> copiedRoutes.put(id, List.copyOf(rows)));
        this.routes = Map.copyOf(copiedRoutes);
        this.publishedHeads = Map.copyOf(state.publishedHeads());
        if (!result.commits() || result.commitCompanion() == null
                || !RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY.equals(
                        result.commitCompanion().contractsSpecificationIdentity())
                || !snapshot.closureIdentity().equals(result.outputClosureIdentity())
                || !routes.keySet().equals(result.resultingDocuments().stream()
                    .map(document -> DocumentId.of(document.documentId().value())).collect(java.util.stream.Collectors.toSet()))
                || snapshot.graphGeneration() != retainedSnapshot.graphGeneration()
                || !ManagedOccurrenceInventory.sameRows(snapshot.occurrences(), retainedSnapshot.occurrences())
                || !sameComponents(snapshot.components(), retainedSnapshot.components())
                || !snapshot.publicRootDocumentIds().equals(retainedSnapshot.publicRootDocumentIds())
                || snapshot.managedDocuments().size() != retainedSnapshot.managedDocuments().size()) {
            throw new IllegalArgumentException("Stored rooted view differs from its authenticated result");
        }
        publishedHeads.forEach((owner, head) -> requireOwnerHead(owner, head.blueId()));
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            var retained = retainedSnapshot.managedDocument(document.documentId());
            var published = publishedHeads.get(DocumentId.of(document.documentId().value()));
            if (retained == null || retained.epoch() != (published == null ? document.epoch() : published.epoch())
                    || !document.blueId().equals(retained.blueId())
                    || !blue.language.model.NodeWireForm.get(document.document())
                            .equals(blue.language.model.NodeWireForm.get(retained.document()))
                    || document.initialized() != retained.initialized() || document.terminated() != retained.terminated()
                    || document.publicRoot() != retained.publicRoot()
                    || document.componentGeneration() != retained.componentGeneration()) {
                throw new IllegalArgumentException("Stored retained snapshot changed exact body or publication position");
            }
        }
    }

    /** Host receipt position may advance for an event-only step without changing the processor epoch. */
    long retainedEpoch(DocumentId documentId) {
        var published = publishedHeads.get(documentId);
        return published == null ? snapshot.managedDocument(ContractsClosureAdapter.closureId(documentId)).epoch()
                : published.epoch();
    }

    void requirePublishedHead(DocumentId owner, long epoch, String blueId) {
        requireOwnerHead(owner, blueId);
        if (!new InMemoryDocumentStore.DocumentHead(epoch, blueId).equals(publishedHeads.get(owner))) {
            throw new IllegalArgumentException("Rooted view does not bind its exact retained publication position");
        }
    }

    void requireProcessingBoundary(blue.language.processor.closure.ClosureInvocationInput input,
            CatchUpPlanStore plans) {
        if (!RootedAttachmentCapture.logicalBoundary(input, plans).equals(logicalBoundary)) {
            throw new IllegalStateException("Rooted view changed the frozen input's logical boundary");
        }
    }

    void requireProcessingBoundary(RootedTerminalEvidence evidence, CatchUpPlanStore plans) {
        if (!evidence.logicalBoundary(plans).equals(logicalBoundary)) {
            throw new IllegalStateException("Rooted view changed the frozen input's logical boundary");
        }
    }

    blue.language.processor.ExternalOrderKey logicalBoundary() { return logicalBoundary; }
    ClosureProcessResult result() { return result; }
    AffectedClosureSnapshot snapshot() { return snapshot; }
    AffectedClosureSnapshot retainedSnapshot() { return retainedSnapshot; }

    /** Retains processor provenance only when capture selected this complete, unchanged exact view. */
    boolean matchesCapture(long graphGeneration, List<ManagedDocumentSnapshot> documents,
            List<blue.language.processor.closure.ManagedOccurrenceBinding> occurrences,
            List<blue.language.processor.closure.ComponentSnapshot> components,
            List<blue.language.processor.closure.DocumentId> publicRoots) {
        AffectedClosureSnapshot snapshot = retainedSnapshot;
        if (snapshot.graphGeneration() != graphGeneration || documents.size() != snapshot.managedDocuments().size()
                || !ManagedOccurrenceInventory.sameRows(snapshot.occurrences(), occurrences) || !sameComponents(snapshot.components(), components)
                || !snapshot.publicRootDocumentIds().equals(publicRoots)) return false;
        var seen = new java.util.HashSet<blue.language.processor.closure.DocumentId>();
        for (var document : documents) {
            var original = snapshot.managedDocument(document.documentId());
            if (!seen.add(document.documentId()) || original == null
                    || !original.blueId().equals(document.blueId()) || original.epoch() != document.epoch()
                    || !blue.language.model.NodeWireForm.get(original.document())
                            .equals(blue.language.model.NodeWireForm.get(document.document()))
                    || original.initialized() != document.initialized() || original.terminated() != document.terminated()
                    || original.publicRoot() != document.publicRoot()
                    || original.componentGeneration() != document.componentGeneration()) return false;
        }
        return true;
    }
    ClosureSubscriptionInventory subscriptions() { return subscriptions; }
    private static boolean sameComponents(List<blue.language.processor.closure.ComponentSnapshot> left,
            List<blue.language.processor.closure.ComponentSnapshot> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            var a = left.get(i); var b = right.get(i);
            if (!a.componentIdentity().equals(b.componentIdentity()) || !a.componentStateIdentity().equals(b.componentStateIdentity())
                    || a.componentGeneration() != b.componentGeneration() || a.kind() != b.kind()
                    || !a.orderedMemberDocumentIds().equals(b.orderedMemberDocumentIds())
                    || !a.orderedMemberBlueIds().equals(b.orderedMemberBlueIds())
                    || !Objects.equals(a.masterBlueId(), b.masterBlueId()) || !Objects.equals(a.cyclicProofIdentity(), b.cyclicProofIdentity())
                    || !Objects.equals(proofWire(a.completeCyclicProof()), proofWire(b.completeCyclicProof()))) return false;
        }
        return true;
    }
    private static Object proofWire(blue.language.provider.CyclicSetProof proof) {
        return proof == null ? null : proof.declaredPlaceholderSet().stream().map(blue.language.model.NodeWireForm::get).toList();
    }
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
