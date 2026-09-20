package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** General-channel rows use a distinct scope and independent per-document bucket members. */
final class StoredGeneralRouteIndexes {
    private final StoreIndexCodecs.Binding<DocumentId, List<GeneralRouteIndex.Row>> members;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<DocumentId, List<GeneralRouteIndex.Row>>> buckets;
    private final StoreIndexCodecs.Binding<DocumentId, Set<String>> documents;
    StoredGeneralRouteIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var c = new StoreIndexCodecs(objects, limits);
        var values = c.codec("general-route/rows", (Writer w, List<GeneralRouteIndex.Row> rows) -> list(w, rows, (out, row) -> {
            out.text(row.documentId().value()); out.text(row.scopePath()); out.text(row.channelKey()); out.integer(row.order());
            optional(out, row.startAfter(), SessionStorageWire::order);
            list(out, row.sources(), (a, source) -> { a.text(source.timelineId()); a.text(source.actorId()); });
        }), r -> {
            var rows = list(r, in -> new GeneralRouteIndex.Row(DocumentId.of(text(in)), text(in), text(in), in.integer(),
                    optional(in, SessionStorageWire::order), list(in, a -> new RoutingSurface.SourceAddress(text(a), text(a)))));
            require(!rows.isEmpty() && rows.equals(rows.stream().distinct().sorted(GeneralRouteIndex.Row.ORDER).toList()),
                    "Noncanonical general route rows");
            return rows;
        });
        members = c.binding("general-route/members", EmbeddingBinding.DOCUMENT_ORDER, c.documents, values);
        buckets = c.binding("general-route/buckets", EmbeddingBinding.TEXT_ORDER, c.text, members.nested());
        documents = c.binding("general-route/document-keys", EmbeddingBinding.DOCUMENT_ORDER, c.documents,
                c.codec("general-route/document-keys", SessionRecordCodec::stringSet, SessionRecordCodec::stringSet));

    }
    GeneralRouteIndex openLogical(LogicalRecordContext context) {
        var scope = new Bytes(OrderedRecordKey.text().encode("general-route/1"));
        return new GeneralRouteIndex(members.openLogicalBuckets(context, Family.ROUTE, scope,
                EmbeddingBinding.TEXT_ORDER, OrderedRecordKey.text(), OrderedRecordKey.document()),
                documents.openLogical(context, Family.ROUTE_DOCUMENT, scope, OrderedRecordKey.document()));
    }
    GeneralRouteIndex retain(GeneralRouteIndex value) {
        return new GeneralRouteIndex(buckets.retain(value.buckets()), documents.retain(value.documents()));
    }
    GeneralRouteIndex read(Reader in, int maximumBytes) {
        return new GeneralRouteIndex(buckets.open(in.bytes(maximumBytes)), documents.open(in.bytes(maximumBytes)));
    }
    void write(Writer out, GeneralRouteIndex value) {
        out.bytes(value.buckets().storedRootDescriptor()); out.bytes(value.documents().storedRootDescriptor());
    }
}
