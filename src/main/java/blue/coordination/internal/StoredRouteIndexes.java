package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.processor.ExternalOrderKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Exact operation-route rows and retained AVL shape; selected roots are caller-pinned, not CAS authority. */
final class StoredRouteIndexes {
    enum Root { ROWS, DOCUMENT_KEYS }
    private final StoreIndexCodecs.Binding<OperationRouteIndex.RouteKey, List<OperationRouteIndex.RouteRow>> rows;
    private final StoreIndexCodecs.Binding<DocumentId, Set<OperationRouteIndex.RouteKey>> documents;
    private final StoreIndexCodecs.Binding<DocumentId, List<OperationRouteIndex.RouteRow>> documentRows;

    StoredRouteIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var c = new StoreIndexCodecs(objects, limits);
        var keys = c.codec("route/key", StoredRouteIndexes::key, StoredRouteIndexes::key);
        var values = c.codec("route/rows", (Writer w, List<OperationRouteIndex.RouteRow> value) -> {
            w.integer(value.size());
            for (var row : value) {
                w.text(row.documentId().value()); w.text(row.scopePath()); w.integer(row.channelOrder());
                w.bool(row.startAfter() != null);
                if (row.startAfter() != null) order(w, row.startAfter());
                w.integer(row.sources().size());
                for (var source : row.sources()) { w.text(source.timelineId()); w.text(source.actorId()); }
            }
        }, r -> {
            int count = r.count(Integer.MAX_VALUE, 17);
            List<OperationRouteIndex.RouteRow> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                DocumentId document = DocumentId.of(text(r)); String scope = text(r); int channelOrder = r.integer();
                ExternalOrderKey startAfter = r.bool() ? order(r) : null;
                int sourceCount = r.count(Integer.MAX_VALUE, 8);
                List<RoutingSurface.SourceAddress> sources = new ArrayList<>();
                for (int j = 0; j < sourceCount; j++) sources.add(new RoutingSurface.SourceAddress(text(r), text(r)));
                var canonical = new RoutingSurface.Definition(scope, "physical-validation", "physical-validation", sources).sources();
                require(canonical.equals(sources), "Stored route sources are not canonical");
                var row = new OperationRouteIndex.RouteRow(document, scope, channelOrder, startAfter, sources);
                require(result.isEmpty() || OperationRouteIndex.RouteRow.ORDER.compare(result.get(result.size() - 1), row) <= 0,
                        "Stored route rows are not ordered");
                result.add(row);
            }
            require(!result.isEmpty(), "Empty retained route bucket");
            return List.copyOf(result);
        });
        var sets = c.codec("route/document-keys", (Writer w, Set<OperationRouteIndex.RouteKey> value) -> {
            w.integer(value.size()); value.forEach(item -> key(w, item));
        }, r -> {
            int count = r.count(Integer.MAX_VALUE, 12);
            Set<OperationRouteIndex.RouteKey> result = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) require(result.add(key(r)), "Duplicate retained route key");
            require(!result.isEmpty(), "Empty retained document-route bucket");
            // Insertion order affects the exact mutation/comparison sequence.
            return Collections.unmodifiableSet(result);
        });
        documentRows = c.binding("route/document-rows", EmbeddingBinding.DOCUMENT_ORDER, c.documents, values);
        rows = c.binding("route/rows", OperationRouteIndex.RouteKey.ORDER, keys, values);
        documents = c.binding("route/document-keys", EmbeddingBinding.DOCUMENT_ORDER, c.documents, sets);
    }

    OperationRouteIndex openLogical(LogicalRecordContext context, EngineMetrics metrics,
            Function<DocumentId, DocumentSession> sessions, Function<DocumentId, String> selectedHeads) {
        var members = documentRows.openLogicalBuckets(context, Family.ROUTE, LogicalRecordContext.runtimeScope(),
                OperationRouteIndex.RouteKey.ORDER, orderedRouteKey(), OrderedRecordKey.document());
        var logical = new LogicalRouteRows(context, members);
        return OperationRouteIndex.restoreIndexes(new OperationRouteIndex.StoredIndexes(logical.all(),
                documents.openLogical(context, Family.ROUTE_DOCUMENT, LogicalRecordContext.runtimeScope(), OrderedRecordKey.document()),
                0, logical), metrics, sessions, selectedHeads);
    }
    static void selectLogical(OperationRouteIndex index) {
        var state = index.storedIndexes();
        java.util.Objects.requireNonNull(state.logicalRows(), "Not a logical route index").select();
        state.keysByDocument().selectLogicalRecords();
    }
    private static OrderedRecordKey<OperationRouteIndex.RouteKey> orderedRouteKey() {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/route/1"; }
            public byte[] encode(OperationRouteIndex.RouteKey key) {
                var text = OrderedRecordKey.text(); return OrderedRecordKey.tuple(text.encode(key.operation()), text.encode(key.channel()), text.encode(key.subscriptionKey()));
            }
            public OperationRouteIndex.RouteKey decode(byte[] bytes) {
                var parts = OrderedRecordKey.split(bytes, 3); var text = OrderedRecordKey.text();
                return new OperationRouteIndex.RouteKey(text.decode(parts[0]), text.decode(parts[1]), text.decode(parts[2]));
            }
        };
    }

    OperationRouteIndex retainPartition(OperationRouteIndex value, EngineMetrics metrics,
            Function<DocumentId, DocumentSession> sessions, Function<DocumentId, String> selectedHeads) {
        return physical(() -> {
            var s = value.storedIndexes();
            return OperationRouteIndex.restoreIndexes(new OperationRouteIndex.StoredIndexes(
                    rows.retain(s.rows()), documents.retain(s.keysByDocument()), s.generation()), metrics, sessions, selectedHeads);
        });
    }

    OperationRouteIndex open(Function<Root, byte[]> selected, long generation, EngineMetrics metrics,
            Function<DocumentId, DocumentSession> sessions, Function<DocumentId, String> selectedHeads) {
        return physical(() -> OperationRouteIndex.restoreIndexes(new OperationRouteIndex.StoredIndexes(
                rows.open(required(selected.apply(Root.ROWS))), documents.open(required(selected.apply(Root.DOCUMENT_KEYS))),
                generation), metrics, sessions, selectedHeads));
    }

    byte[] root(OperationRouteIndex value, Root root) {
        return physical(() -> {
            var s = value.storedIndexes();
            return switch (root) {
                case ROWS -> s.rows().storedRootDescriptor();
                case DOCUMENT_KEYS -> s.keysByDocument().storedRootDescriptor();
            };
        });
    }

    private static void key(Writer w, OperationRouteIndex.RouteKey value) {
        w.text(value.operation()); w.text(value.channel()); w.text(value.subscriptionKey());
    }
    private static OperationRouteIndex.RouteKey key(Reader r) {
        return new OperationRouteIndex.RouteKey(text(r), text(r), text(r));
    }
    private static String text(Reader r) { return r.text(r.remaining()); }
    private static byte[] required(byte[] bytes) { require(bytes != null, "Missing selected route root descriptor"); return bytes; }
}
