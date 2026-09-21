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

    RootedAdmissionSources(ExternalOrderKey boundary, Map<DocumentId, RootedDocumentView> views) {
        this(boundary, views, CoordinationEngine.AdmissionPolicy.FROM_FRONTIER);
    }

    private RootedAdmissionSources(ExternalOrderKey boundary, Map<DocumentId, RootedDocumentView> views,
            CoordinationEngine.AdmissionPolicy policy) {
        this.boundary = boundary;
        this.views = Map.copyOf(views);
        if (!this.views.isEmpty() && boundary == null) {
            throw new IllegalArgumentException("Stored admission sources require their exact frontier");
        }
        this.views.forEach((id, view) -> {
            var member = view.snapshot().managedDocument(ContractsClosureAdapter.closureId(id));
            if (member == null || policy != CoordinationEngine.AdmissionPolicy.FULL_HISTORY
                    && view.logicalBoundary() != null && view.logicalBoundary().compareTo(boundary) > 0) {
                throw new IllegalArgumentException("Stored admission source is outside its authenticated frontier");
            }
            view.requirePublishedHead(id, view.retainedEpoch(id), member.blueId());
        });
    }

    ExternalOrderKey storedBoundary() { return boundary; }
    Map<DocumentId, RootedDocumentView> storedViews() { return views; }

    static RootedAdmissionSources restoreStored(ExternalOrderKey boundary, Map<DocumentId, RootedDocumentView> views,
            Map<String, Object> historyDescriptor) {
        if (!(historyDescriptor.get("admission") instanceof Map<?, ?> admission)
                || !(admission.get("mode") instanceof String mode)) {
            throw new IllegalArgumentException("Stored admission sources require their exact admission mode");
        }
        return switch (mode) {
            case "FULL_HISTORY" -> new RootedAdmissionSources(boundary, views, CoordinationEngine.AdmissionPolicy.FULL_HISTORY);
            case "FROM_NOW", "FROM_FRONTIER" -> new RootedAdmissionSources(boundary, views);
            case "CREATED_IN_OPERATION" -> {
                if (boundary != null || !views.isEmpty()) {
                    throw new IllegalArgumentException("Created history cannot substitute static admission sources");
                }
                yield NONE;
            }
            default -> throw new IllegalArgumentException("Stored admission mode is unsupported: " + mode);
        };
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
            source.requireRetainedRootedView(view);
            selected.put(id, view);
        }
        return new RootedAdmissionSources(boundary, selected, policy);
    }

    RootedDocumentView selected(DocumentId source, ExternalOrderKey requestedBoundary,
            InMemoryDocumentStore documents) {
        return selected(source, requestedBoundary, documents::require);
    }

    RootedDocumentView selected(DocumentId source, ExternalOrderKey requestedBoundary,
            java.util.function.Function<DocumentId, DocumentSession> sessions) {
        if (!requestedBoundary.equals(boundary)) return null;
        RootedDocumentView view = views.get(source);
        if (view != null) sessions.apply(source).requireRetainedRootedView(view);
        return view;
    }
}
