package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ComponentSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable exact-key index of retained Contracts component proof states.
 *
 * <p>Ordinary publication replaces only component lineages intersecting the
 * staged forward closure. Exhaustive target-before-source materialization is
 * reserved for explicit snapshot APIs.</p>
 */
final class ComponentStateInventory {
    private final PersistentOrderedMap<String, ComponentSnapshot> byLineage;
    private final PersistentOrderedMap<String, String> lineageByState;
    private final PersistentOrderedMap<DocumentId, String> lineageByDocument;

    private ComponentStateInventory(
            PersistentOrderedMap<String, ComponentSnapshot> byLineage,
            PersistentOrderedMap<String, String> lineageByState,
            PersistentOrderedMap<DocumentId, String> lineageByDocument) {
        this.byLineage = Objects.requireNonNull(byLineage, "byLineage");
        this.lineageByState = Objects.requireNonNull(
                lineageByState, "lineageByState");
        this.lineageByDocument = Objects.requireNonNull(
                lineageByDocument, "lineageByDocument");
    }

    static ComponentStateInventory empty() {
        return new ComponentStateInventory(
                PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER));
    }

    static ComponentStateInventory of(
            Collection<ComponentSnapshot> components) {
        ComponentStateInventory result = empty();
        for (ComponentSnapshot component : Objects.requireNonNull(
                components, "components")) {
            result = result.add(Objects.requireNonNull(
                    component, "component"));
        }
        return result;
    }

    ComponentSnapshot byLineage(String componentIdentity) {
        return byLineage.get(Objects.requireNonNull(
                componentIdentity, "componentIdentity"));
    }

    ComponentSnapshot forDocument(DocumentId documentId) {
        String lineage = lineageByDocument.get(Objects.requireNonNull(
                documentId, "documentId"));
        return lineage == null ? null : byLineage.get(lineage);
    }

    ComponentStateInventory replaceAffected(
            Collection<DocumentId> affectedDocuments,
            Collection<ComponentSnapshot> replacements) {
        Set<DocumentId> affected = new LinkedHashSet<>(Objects.requireNonNull(
                affectedDocuments, "affectedDocuments"));
        Set<String> removedLineages = new LinkedHashSet<>();
        for (DocumentId document : affected) {
            String lineage = lineageByDocument.get(Objects.requireNonNull(
                    document, "affectedDocument"));
            if (lineage != null) {
                removedLineages.add(lineage);
            }
        }
        ComponentStateInventory result = this;
        for (String lineage : removedLineages) {
            ComponentSnapshot existing = result.byLineage.get(lineage);
            if (existing == null) {
                throw new IllegalStateException(
                        "Component-state lineage index is inconsistent "
                                + lineage);
            }
            for (blue.language.processor.closure.DocumentId member
                    : existing.orderedMemberDocumentIds()) {
                DocumentId document = DocumentId.of(member.value());
                if (!affected.contains(document)) {
                    throw new IllegalArgumentException(
                            "Affected publication contains only part of "
                                    + "component proof " + lineage);
                }
            }
            result = result.remove(existing);
        }
        for (ComponentSnapshot replacement : Objects.requireNonNull(
                replacements, "replacements")) {
            ComponentSnapshot selected = Objects.requireNonNull(
                    replacement, "replacement");
            for (blue.language.processor.closure.DocumentId member
                    : selected.orderedMemberDocumentIds()) {
                if (!affected.contains(DocumentId.of(member.value()))) {
                    throw new IllegalArgumentException(
                            "Replacement component proof escaped the affected "
                                    + "region " + selected.componentIdentity());
                }
            }
            result = result.add(selected);
        }
        return result;
    }

    List<ComponentSnapshot> statesFor(
            Collection<DocumentId> documents,
            ProcessEmbeddedComponentIndex topology) {
        ArrayList<ComponentSnapshot> result = new ArrayList<>();
        for (ProcessEmbeddedComponentIndex.Component component
                : Objects.requireNonNull(topology, "topology")
                        .orderedComponentsFor(documents)) {
            ComponentSnapshot state = forDocument(component.members().get(0));
            if (state != null) {
                result.add(state);
            }
        }
        return List.copyOf(result);
    }

    List<ComponentSnapshot> states(
            ProcessEmbeddedComponentIndex topology) {
        return statesFor(topology.documents(), topology);
    }

    int sharedLineageNodesForTesting(ComponentStateInventory other) {
        return byLineage.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other").byLineage);
    }

    boolean sameStateEntryIdentityForTesting(
            ComponentStateInventory other,
            DocumentId document) {
        ComponentStateInventory selected = Objects.requireNonNull(
                other, "other");
        DocumentId key = Objects.requireNonNull(document, "document");
        ComponentSnapshot current = forDocument(key);
        return current != null && current == selected.forDocument(key);
    }

    private ComponentStateInventory add(ComponentSnapshot component) {
        if (byLineage.containsKey(component.componentIdentity())) {
            throw new IllegalArgumentException(
                    "Duplicate component lineage "
                            + component.componentIdentity());
        }
        if (lineageByState.containsKey(component.componentStateIdentity())) {
            throw new IllegalArgumentException(
                    "Duplicate component state identity "
                            + component.componentStateIdentity());
        }
        PersistentOrderedMap<DocumentId, String> documents =
                lineageByDocument;
        for (blue.language.processor.closure.DocumentId member
                : component.orderedMemberDocumentIds()) {
            DocumentId document = DocumentId.of(member.value());
            if (documents.containsKey(document)) {
                throw new IllegalArgumentException(
                        "Overlapping component state member " + document);
            }
            documents = documents.put(
                    document, component.componentIdentity()).map();
        }
        return new ComponentStateInventory(
                byLineage.put(
                        component.componentIdentity(), component).map(),
                lineageByState.put(
                        component.componentStateIdentity(),
                        component.componentIdentity()).map(),
                documents);
    }

    private ComponentStateInventory remove(ComponentSnapshot component) {
        PersistentOrderedMap<DocumentId, String> documents =
                lineageByDocument;
        for (blue.language.processor.closure.DocumentId member
                : component.orderedMemberDocumentIds()) {
            documents = documents.remove(DocumentId.of(member.value())).map();
        }
        return new ComponentStateInventory(
                byLineage.remove(component.componentIdentity()).map(),
                lineageByState.remove(
                        component.componentStateIdentity()).map(),
                documents);
    }
}
