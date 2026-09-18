package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.processor.closure.ComponentSnapshot;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Lossless component proof-state rows; hash-selected storage is not a new component-admission authority. */
final class StoredComponentStateIndexes {
    enum Root { LINEAGE, STATE, DOCUMENT }
    private final StoreIndexCodecs.Binding<String, ComponentSnapshot> lineages;
    private final StoreIndexCodecs.Binding<String, String> states;
    private final StoreIndexCodecs.Binding<DocumentId, String> documents;
    private final PersistentMapCodec<ComponentSnapshot> rows;

    StoredComponentStateIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits, int maximumDepth) {
        var c = new StoreIndexCodecs(objects, limits); var exact = new SessionRecordCodec(limits.valueBytes(), maximumDepth);
        rows = c.codec("component-state", exact::component, exact::component);
        lineages = c.binding("component-state/lineage", EmbeddingBinding.TEXT_ORDER, c.text, rows);
        states = c.binding("component-state/state", EmbeddingBinding.TEXT_ORDER, c.text, c.text);
        documents = c.binding("component-state/document", EmbeddingBinding.DOCUMENT_ORDER, c.documents, c.text);
    }

    ComponentStateInventory openLogical(LogicalRecordContext context) {
        var scope = LogicalRecordContext.runtimeScope();
        return ComponentStateInventory.restoreIndexes(new ComponentStateInventory.StoredIndexes(
                lineages.openLogical(context, Family.COMPONENT_LINEAGE, scope, OrderedRecordKey.text()),
                states.openLogical(context, Family.COMPONENT_STATE, scope, OrderedRecordKey.text()),
                documents.openLogical(context, Family.COMPONENT_DOCUMENT, scope, OrderedRecordKey.document())));
    }

    void selectLogical(ComponentStateInventory value) {
        var s = value.storedIndexes(); s.lineages().selectLogicalRecords(); s.states().selectLogicalRecords(); s.documents().selectLogicalRecords();
    }

    ComponentStateInventory retainPartition(ComponentStateInventory value) {
        return physical(() -> {
            var s = value.storedIndexes(); return ComponentStateInventory.restoreIndexes(new ComponentStateInventory.StoredIndexes(
                    lineages.retain(s.lineages()), states.retain(s.states()), documents.retain(s.documents())));
        });
    }
    ComponentStateInventory open(Function<Root, byte[]> selected) {
        return physical(() -> ComponentStateInventory.restoreIndexes(new ComponentStateInventory.StoredIndexes(
                lineages.open(selected.apply(Root.LINEAGE)), states.open(selected.apply(Root.STATE)), documents.open(selected.apply(Root.DOCUMENT)))));
    }
    byte[] root(ComponentStateInventory value, Root root) {
        var s = value.storedIndexes(); return switch (root) {
            case LINEAGE -> s.lineages().storedRootDescriptor(); case STATE -> s.states().storedRootDescriptor(); case DOCUMENT -> s.documents().storedRootDescriptor();
        };
    }

    /** The already authenticated original publication components, not a final-SCC reconstruction, qualify selected proof authority. */
    Optional<ComponentSnapshot> forDocument(ComponentStateInventory value, DocumentId document, List<ComponentSnapshot> publicationComponents) {
        return physical(() -> {
            var s = value.storedIndexes(); var lineage = s.documents().get(document);
            var original = publicationComponents.stream().filter(row -> row.orderedMemberDocumentIds().stream()
                    .anyMatch(id -> id.value().equals(document.value()))).toList();
            require(original.size() <= 1, "Selected original publication repeats component membership");
            if (lineage == null) { require(original.isEmpty(), "Selected original publication component is missing"); return Optional.empty(); }
            var row = s.lineages().get(lineage);
            require(row != null && lineage.equals(row.componentIdentity()) && lineage.equals(s.states().get(row.componentStateIdentity())),
                    "Selected component lineage/state indexes differ");
            require(original.size() == 1 && Arrays.equals(rows.encode(row), rows.encode(original.get(0))),
                    "Selected component proof differs from its original authenticated publication");
            for (var member : row.orderedMemberDocumentIds()) require(lineage.equals(s.documents().get(DocumentId.of(member.value()))),
                    "Selected component member index differs");
            return Optional.of(row);
        });
    }

    byte[] exactRow(ComponentSnapshot row) { return rows.encode(row); }
}
