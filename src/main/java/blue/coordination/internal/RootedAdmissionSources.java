package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationEngine;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureInvocationInput;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Exact source publication positions selected by static admission, not a later head lookup. */
final class RootedAdmissionSources {
    static final RootedAdmissionSources NONE = new RootedAdmissionSources(null, Map.of());
    private final ExternalOrderKey boundary;
    private final Map<DocumentId, RootedDocumentView> views;

    private RootedAdmissionSources(ExternalOrderKey boundary, Map<DocumentId, RootedDocumentView> views) {
        this.boundary = boundary;
        this.views = Map.copyOf(views);
    }

    static RootedAdmissionSources capture(ClosureInvocationInput input, ExternalOrderKey boundary,
            Set<DocumentId> existing, InMemoryDocumentStore documents, CoordinationEngine.AdmissionPolicy policy) {
        if (input.operation() != ClosureInvocationInput.Operation.ADMIT_CLOSURE) {
            throw new IllegalArgumentException("Source admission positions require an actual admission input");
        }
        Map<DocumentId, RootedDocumentView> selected = new LinkedHashMap<>();
        for (DocumentId id : existing) {
            DocumentSession source = documents.require(id);
            RootedDocumentView view = source.rootedView();
            var exact = input.snapshot().managedDocument(ContractsClosureAdapter.closureId(id));
            // FULL_HISTORY uses a beginning sentinel for replay, not a source endpoint.
            // The complete admission input still authenticates the exact selected head.
            if (view == null || exact == null || (policy != CoordinationEngine.AdmissionPolicy.FULL_HISTORY
                    && view.logicalBoundary() != null
                    && view.logicalBoundary().compareTo(boundary) > 0)) {
                throw new IllegalArgumentException("Admission source is outside its authenticated frontier");
            }
            view.requirePublishedHead(id, exact.epoch(), exact.blueId());
            source.rootedPublicationPrefix(view);
            selected.put(id, view);
        }
        return new RootedAdmissionSources(boundary, selected);
    }

    RootedDocumentView selected(DocumentId source, ExternalOrderKey requestedBoundary,
            InMemoryDocumentStore documents) {
        if (!requestedBoundary.equals(boundary)) return null;
        RootedDocumentView view = views.get(source);
        if (view != null) documents.require(source).rootedPublicationPrefix(view);
        return view;
    }
}
