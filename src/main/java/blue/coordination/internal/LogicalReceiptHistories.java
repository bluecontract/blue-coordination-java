package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import static blue.coordination.internal.ManagedEpochReceiptStore.*;

/** Numbered immutable evidence is independent of the mutable source-head cursor. */
final class LogicalReceiptHistories implements LogicalRecordMap.Source<DocumentId, DocumentHistory> {
    private final LogicalRecordContext context;
    private final PersistentOrderedMap<DocumentId, PersistentOrderedMap<Long, StoredReceipt>> epochs;
    private final PersistentOrderedMap<DocumentId, DocumentHistory.Head> heads;

    LogicalReceiptHistories(LogicalRecordContext context, StoreIndexCodecs.Binding<Long, StoredReceipt> epochCodec,
            CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        this.context = context;
        epochs = epochCodec.openLogicalBuckets(context, Family.RECEIPT_DOCUMENT,
                new Bytes(OrderedRecordKey.text().encode("runtime/1/receipt-epochs")), EmbeddingBinding.DOCUMENT_ORDER,
                OrderedRecordKey.document(), OrderedRecordKey.signedLong());
        var codecs = new StoreIndexCodecs(objects, limits);
        PersistentMapCodec<DocumentHistory.Head> head = codecs.codec("receipt-head", (w, value) -> {
            w.longValue(value.epoch()); w.text(value.representation());
        }, r -> new DocumentHistory.Head(r.longValue(), r.text(r.remaining())));
        heads = codecs.binding("receipt-head", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, head)
                .openLogical(context, Family.RECEIPT_DOCUMENT, new Bytes(OrderedRecordKey.text().encode("runtime/1/receipt-heads")), OrderedRecordKey.document());
    }

    PersistentOrderedMap<DocumentId, DocumentHistory> open() {
        return PersistentOrderedMap.logical(EmbeddingBinding.DOCUMENT_ORDER,
                LogicalRecordMap.virtual(EmbeddingBinding.DOCUMENT_ORDER, context, this, this::select, history -> true));
    }

    @Override public DocumentHistory get(DocumentId id) {
        var rows = epochs.get(id); var initial = rows.get(0L);
        if (initial == null) {
            if (heads.get(id) != null) throw new IllegalStateException("Receipt head has no initialization");
            return null;
        }
        if (!initial.publicReceipt().documentId().equals(id) || initial.publicReceipt().epoch() != 0
                || initial.publicReceipt().kind() != DocumentRevision.Kind.INITIALIZATION)
            throw new IllegalStateException("Receipt initialization is bound to another history");
        return DocumentHistory.logical(id, rows, () -> Objects.requireNonNull(heads.get(id), "Missing receipt head"));
    }
    @Override public boolean contains(DocumentId id) { return get(id) != null; }
    @Override public List<Map.Entry<DocumentId, DocumentHistory>> entries() {
        return epochs.keys().stream().map(id -> Map.entry(id, Objects.requireNonNull(get(id)))).toList();
    }
    @Override public Map.Entry<DocumentId, DocumentHistory> first(DocumentId lower, boolean exclusive, DocumentId upper) {
        var iterator = epochs.range(lower, upper);
        while (iterator.hasNext()) {
            var row = iterator.next();
            if (exclusive && lower != null && row.getKey().equals(lower)) continue;
            return Map.entry(row.getKey(), Objects.requireNonNull(get(row.getKey())));
        }
        return null;
    }
    private void select(DocumentId owner, DocumentHistory selected) {
        if (selected == null) {
            epochs.remove(owner).map().selectLogicalRecords(); heads.remove(owner).map().selectLogicalRecords(); return;
        }
        if (!owner.equals(selected.documentId())) throw new IllegalArgumentException("Receipt history is bound to another owner");
        var state = selected.storedState();
        epochs.put(owner, state.receipts()).map().selectLogicalRecords();
        heads.put(owner, new DocumentHistory.Head(state.latestEpoch(), state.currentRepresentation())).map().selectLogicalRecords();
    }
}
