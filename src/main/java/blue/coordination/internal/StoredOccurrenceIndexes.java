package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Complete current occurrence rows and independently pinned physical index roots, not active-graph reconstruction. */
final class StoredOccurrenceIndexes {
    enum Root { PATH, ORDERED, ACTIVE, OCCURRENCE_ID, BINDING_ID, DOCUMENT, SOURCE, ACTIVE_SOURCE }
    private final StoreIndexCodecs.Binding<ManagedOccurrenceInventory.OccurrenceKey, ManagedOccurrenceBinding> paths;
    private final StoreIndexCodecs.Binding<ManagedOccurrenceInventory.RowOrderKey, ManagedOccurrenceBinding> ordered, active;
    private final StoreIndexCodecs.Binding<String, ManagedOccurrenceInventory.OccurrenceKey> occurrenceIds, bindingIds;
    private final StoreIndexCodecs.Binding<DocumentId, PersistentOrderedMap<ManagedOccurrenceInventory.RowOrderKey, ManagedOccurrenceBinding>> documents, sources, activeSources;
    private final PersistentMapCodec<ManagedOccurrenceBinding> rows;

    StoredOccurrenceIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var codecs = new StoreIndexCodecs(objects, limits); rows = codecs.occurrences;
        paths = codecs.binding("occurrence/path", ManagedOccurrenceInventory.KEY_ORDER, codecs.occurrenceKeys, rows);
        ordered = codecs.binding("occurrence/ordered", ManagedOccurrenceInventory.ROW_ORDER, codecs.occurrenceOrder, rows);
        active = codecs.binding("occurrence/active", ManagedOccurrenceInventory.ROW_ORDER, codecs.occurrenceOrder, rows);
        occurrenceIds = codecs.binding("occurrence/identity", EmbeddingBinding.TEXT_ORDER, codecs.text, codecs.occurrenceKeys);
        bindingIds = codecs.binding("occurrence/binding", EmbeddingBinding.TEXT_ORDER, codecs.text, codecs.occurrenceKeys);
        var bucket = codecs.binding("occurrence/bucket", ManagedOccurrenceInventory.ROW_ORDER, codecs.occurrenceOrder, rows);
        documents = codecs.binding("occurrence/document", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, bucket.nested());
        sources = codecs.binding("occurrence/source", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, bucket.nested());
        activeSources = codecs.binding("occurrence/active-source", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, bucket.nested());
    }

    ManagedOccurrenceInventory retainPartition(ManagedOccurrenceInventory value) {
        return physical(() -> {
            var s = value.storedIndexes();
            return ManagedOccurrenceInventory.restoreIndexes(new ManagedOccurrenceInventory.StoredIndexes(paths.retain(s.paths()),
                    ordered.retain(s.ordered()), active.retain(s.active()), occurrenceIds.retain(s.occurrenceKeys()), bindingIds.retain(s.bindingKeys()),
                    documents.retain(s.documents()), sources.retain(s.sources()), activeSources.retain(s.activeSources())));
        });
    }

    ManagedOccurrenceInventory open(Function<Root, byte[]> selected) {
        return physical(() -> ManagedOccurrenceInventory.restoreIndexes(new ManagedOccurrenceInventory.StoredIndexes(
                paths.open(selected.apply(Root.PATH)), ordered.open(selected.apply(Root.ORDERED)), active.open(selected.apply(Root.ACTIVE)),
                occurrenceIds.open(selected.apply(Root.OCCURRENCE_ID)), bindingIds.open(selected.apply(Root.BINDING_ID)),
                documents.open(selected.apply(Root.DOCUMENT)), sources.open(selected.apply(Root.SOURCE)), activeSources.open(selected.apply(Root.ACTIVE_SOURCE)))));
    }

    byte[] root(ManagedOccurrenceInventory value, Root root) {
        var s = value.storedIndexes();
        return switch (root) {
            case PATH -> s.paths().storedRootDescriptor(); case ORDERED -> s.ordered().storedRootDescriptor();
            case ACTIVE -> s.active().storedRootDescriptor(); case OCCURRENCE_ID -> s.occurrenceKeys().storedRootDescriptor();
            case BINDING_ID -> s.bindingKeys().storedRootDescriptor(); case DOCUMENT -> s.documents().storedRootDescriptor();
            case SOURCE -> s.sources().storedRootDescriptor(); case ACTIVE_SOURCE -> s.activeSources().storedRootDescriptor();
        };
    }

    Optional<ManagedOccurrenceBinding> find(ManagedOccurrenceInventory value, DocumentId source, String path) {
        return physical(() -> {
            var key = ManagedOccurrenceInventory.OccurrenceKey.of(source, path); var row = value.storedIndexes().paths().get(key);
            if (row != null) { require(key.equals(key(row)), "Selected occurrence path differs from its row"); verify(value, row); }
            return Optional.ofNullable(row);
        });
    }

    /** Validates all memberships for one selected source, not the unrelated occurrence catalog. */
    List<ManagedOccurrenceBinding> sourceRows(ManagedOccurrenceInventory value, DocumentId source, boolean activeOnly) {
        return physical(() -> {
            var state = value.storedIndexes(); var all = state.sources().get(source); var active = state.activeSources().get(source);
            var result = new ArrayList<ManagedOccurrenceBinding>(); int activeCount = 0;
            if (all != null) for (var entry : all.entries()) {
                var row = entry.getValue();
                require(source.value().equals(row.sourceDocumentId().value())
                        && entry.getKey().equals(ManagedOccurrenceInventory.RowOrderKey.from(row)), "Selected source bucket contains foreign occurrence");
                verify(value, row); if (row.active()) activeCount++;
                if (!activeOnly || row.active()) result.add(row);
            }
            require((active == null ? 0 : active.size()) == activeCount, "Selected active-source coverage differs");
            return List.copyOf(result);
        });
    }

    List<ManagedOccurrenceBinding> touching(ManagedOccurrenceInventory value, DocumentId document) {
        return physical(() -> {
            var bucket = value.storedIndexes().documents().get(document); if (bucket == null) return List.of();
            var result = new ArrayList<ManagedOccurrenceBinding>();
            for (var entry : bucket.entries()) {
                var row = entry.getValue(); require(document.value().equals(row.sourceDocumentId().value())
                        || document.value().equals(row.targetDocumentId().value()), "Selected occurrence document bucket has foreign owner");
                require(entry.getKey().equals(ManagedOccurrenceInventory.RowOrderKey.from(row)), "Selected occurrence ordering differs");
                verify(value, row); result.add(row);
            }
            return List.copyOf(result);
        });
    }

    void verify(ManagedOccurrenceInventory value, ManagedOccurrenceBinding row) {
        var s = value.storedIndexes(); var key = key(row); var order = ManagedOccurrenceInventory.RowOrderKey.from(row);
        require(same(row, s.paths().get(key)) && same(row, s.ordered().get(order))
                && key.equals(s.occurrenceKeys().get(row.occurrenceIdentity())) && key.equals(s.bindingKeys().get(row.bindingIdentity())),
                "Selected occurrence primary/identity indexes differ");
        require(row.active() ? same(row, s.active().get(order)) : s.active().get(order) == null, "Selected occurrence active index differs");
        var source = DocumentId.of(row.sourceDocumentId().value()); var target = DocumentId.of(row.targetDocumentId().value());
        for (var id : new LinkedHashSet<>(List.of(source, target))) require(same(row, bucketRow(s.documents(), id, order)), "Selected occurrence touching membership differs");
        require(same(row, bucketRow(s.sources(), source, order)), "Selected occurrence source membership differs");
        var active = bucketRow(s.activeSources(), source, order);
        require(row.active() ? same(row, active) : active == null, "Selected occurrence active-source membership differs");
    }

    private static ManagedOccurrenceBinding bucketRow(PersistentOrderedMap<DocumentId, PersistentOrderedMap<ManagedOccurrenceInventory.RowOrderKey, ManagedOccurrenceBinding>> outer,
            DocumentId document, ManagedOccurrenceInventory.RowOrderKey key) {
        var bucket = outer.get(document); return bucket == null ? null : bucket.get(key);
    }
    private static ManagedOccurrenceInventory.OccurrenceKey key(ManagedOccurrenceBinding row) {
        return ManagedOccurrenceInventory.OccurrenceKey.of(DocumentId.of(row.sourceDocumentId().value()), row.sourceAddress().path());
    }
    private boolean same(ManagedOccurrenceBinding a, ManagedOccurrenceBinding b) { return b != null && Arrays.equals(rows.encode(a), rows.encode(b)); }
}
