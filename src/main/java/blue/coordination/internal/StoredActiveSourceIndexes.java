package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Four independently retained source-union indexes; opening never resolves a document or traverses its graph. */
final class StoredActiveSourceIndexes {
    enum Root { PUBLIC_ROOTS, SURFACES, MEMBERSHIPS, TIMELINE_REFERENCES }
    private final StoreIndexCodecs.Binding<DocumentId, Boolean> roots;
    private final StoreIndexCodecs.Binding<DocumentId, ContractsRootSourceSurface.Surface> surfaces;
    private final StoreIndexCodecs.Binding<DocumentId, Set<DocumentId>> memberships;
    private final StoreIndexCodecs.Binding<String, Long> timelines;

    StoredActiveSourceIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var c = new StoreIndexCodecs(objects, limits);
        var documentSets = c.codec("active-source/document-set", (Writer w, Set<DocumentId> value) -> documents(w, List.copyOf(value)),
                r -> {
                    var value = documents(r);
                    require(!value.isEmpty(), "Empty retained source membership");
                    return Collections.unmodifiableSet(new LinkedHashSet<>(value));
                });
        var surfaceRows = c.codec("active-source/surface", (Writer w, ContractsRootSourceSurface.Surface value) -> {
            w.bool(value.lane().publicLane()); documents(w, value.lane().roots());
            documents(w, value.managedDocuments());
            w.integer(value.timelineIds().size()); value.timelineIds().forEach(w::text);
        }, r -> {
            require(r.bool(), "Retained source index lane is not public");
            var root = documents(r);
            require(root.size() == 1, "Source contribution must have one Root");
            var members = documents(r);
            int count = r.count(Integer.MAX_VALUE, 4);
            Set<String> timelineIds = new LinkedHashSet<>(); String previous = null;
            for (int i = 0; i < count; i++) {
                String value = r.text(r.remaining());
                require(!value.isBlank() && (previous == null || EmbeddingBinding.TEXT_ORDER.compare(previous, value) < 0),
                        "Retained source Timelines are not canonical");
                timelineIds.add(value); previous = value;
            }
            return new ContractsRootSourceSurface.Surface(ContractsRootFeederWindow.LaneId.publicRoots(root), members, timelineIds);
        });
        var counts = c.codec("active-source/count", Writer::longValue, r -> {
            long value = r.longValue(); require(value > 0L, "Invalid retained source Timeline reference count"); return value;
        });
        roots = c.binding("active-source/public-roots", EmbeddingBinding.DOCUMENT_ORDER, c.documents, c.membership);
        surfaces = c.binding("active-source/surfaces", EmbeddingBinding.DOCUMENT_ORDER, c.documents, surfaceRows);
        memberships = c.binding("active-source/memberships", EmbeddingBinding.DOCUMENT_ORDER, c.documents, documentSets);
        timelines = c.binding("active-source/timelines", EmbeddingBinding.TEXT_ORDER, c.text, counts);
    }

    ContractsActiveSourceTimelineIndex retainPartition(ContractsActiveSourceTimelineIndex value, EngineMetrics metrics) {
        return physical(() -> {
            var s = value.storedIndexes();
            return ContractsActiveSourceTimelineIndex.restoreIndexes(new ContractsActiveSourceTimelineIndex.StoredIndexes(
                    roots.retain(s.publicRoots()), surfaces.retain(s.surfaces()), memberships.retain(s.memberships()),
                    timelines.retain(s.timelineReferences())), metrics);
        });
    }

    ContractsActiveSourceTimelineIndex open(Function<Root, byte[]> selected, EngineMetrics metrics) {
        return physical(() -> ContractsActiveSourceTimelineIndex.restoreIndexes(new ContractsActiveSourceTimelineIndex.StoredIndexes(
                roots.open(required(selected.apply(Root.PUBLIC_ROOTS))), surfaces.open(required(selected.apply(Root.SURFACES))),
                memberships.open(required(selected.apply(Root.MEMBERSHIPS))), timelines.open(required(selected.apply(Root.TIMELINE_REFERENCES)))), metrics));
    }

    byte[] root(ContractsActiveSourceTimelineIndex value, Root root) {
        return physical(() -> {
            var s = value.storedIndexes();
            return switch (root) {
                case PUBLIC_ROOTS -> s.publicRoots().storedRootDescriptor();
                case SURFACES -> s.surfaces().storedRootDescriptor();
                case MEMBERSHIPS -> s.memberships().storedRootDescriptor();
                case TIMELINE_REFERENCES -> s.timelineReferences().storedRootDescriptor();
            };
        });
    }

    private static void documents(Writer w, List<DocumentId> ids) {
        w.integer(ids.size()); ids.forEach(id -> w.text(id.value()));
    }
    private static List<DocumentId> documents(Reader r) {
        int count = r.count(Integer.MAX_VALUE, 4); List<DocumentId> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var id = DocumentId.of(r.text(r.remaining()));
            require(ids.isEmpty() || EmbeddingBinding.DOCUMENT_ORDER.compare(ids.get(ids.size() - 1), id) < 0,
                    "Retained source documents are not canonical");
            ids.add(id);
        }
        return List.copyOf(ids);
    }
    private static byte[] required(byte[] bytes) { require(bytes != null, "Missing selected source root descriptor"); return bytes; }
}
