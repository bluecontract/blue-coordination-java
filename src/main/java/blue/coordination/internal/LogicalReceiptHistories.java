package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import static blue.coordination.internal.ManagedEpochReceiptStore.*;

/** Numbered immutable evidence is independent of the mutable source-head cursor. */
final class LogicalReceiptHistories implements LogicalRecordMap.Source<DocumentId, DocumentHistory> {
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final PersistentOrderedMap<DocumentInstanceRef, PersistentOrderedMap<Long, StoredReceipt>> epochs;
    private final PersistentOrderedMap<DocumentInstanceRef, DocumentHistory.Head> heads;

    LogicalReceiptHistories(LogicalRecordContext context, StoreIndexCodecs.Binding<Long, StoredReceipt> epochCodec,
            CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        this.context = context; instances = context.instances(limits.valueBytes());
        epochs = epochCodec.openLogicalBuckets(context, Family.RECEIPT_DOCUMENT,
                new Bytes(OrderedRecordKey.text().encode("runtime/2/receipt-epochs")), InstanceSourceKeys.ORDER,
                InstanceSourceKeys.INSTANCE, OrderedRecordKey.signedLong());
        var codecs = new StoreIndexCodecs(objects, limits);
        PersistentMapCodec<DocumentHistory.Head> head = codecs.codec("receipt-head", (w, value) -> {
            w.longValue(value.epoch()); w.text(value.representation());
        }, r -> new DocumentHistory.Head(r.longValue(), r.text(r.remaining())));
        PersistentMapCodec<DocumentInstanceRef> refs = codecs.codec("receipt-instance", (w, value) -> {
            w.text(value.documentId().value()); w.text(value.instanceId());
        }, r -> new DocumentInstanceRef(DocumentId.of(r.text(r.remaining())), r.text(r.remaining())));
        heads = codecs.binding("receipt-head", InstanceSourceKeys.ORDER, refs, head)
                .openLogical(context, Family.RECEIPT_DOCUMENT, new Bytes(OrderedRecordKey.text().encode("runtime/2/receipt-heads")), InstanceSourceKeys.INSTANCE);
    }

    PersistentOrderedMap<DocumentId, DocumentHistory> open() {
        return PersistentOrderedMap.logical(EmbeddingBinding.DOCUMENT_ORDER,
                LogicalRecordMap.virtual(EmbeddingBinding.DOCUMENT_ORDER, context, this, this::select, history -> true));
    }

    @Override public DocumentHistory get(DocumentId id) {
        return instances.select(id).instance().map(this::retained).orElse(null);
    }
    DocumentHistory retained(DocumentInstanceRef ref) {
        return context.protect(() -> {
            instances.requireRetained(ref);
            var rows = epochs.get(ref); var initial = rows.get(0L); var id = ref.documentId();
            if (initial == null) {
                if (heads.get(ref) != null) throw new IllegalStateException("Receipt head has no initialization");
                return null;
            }
            if (!initial.publicReceipt().documentId().equals(id) || initial.publicReceipt().epoch() != 0
                    || initial.publicReceipt().kind() != DocumentRevision.Kind.INITIALIZATION)
                throw new IllegalStateException("Receipt initialization is bound to another history");
            return DocumentHistory.logical(id, rows, () -> context.protect(() ->
                    Objects.requireNonNull(heads.get(ref), "Missing receipt head")));
        });
    }
    @Override public boolean contains(DocumentId id) { return get(id) != null; }
    @Override public List<Map.Entry<DocumentId, DocumentHistory>> entries() {
        var ids = new TreeSet<DocumentId>(EmbeddingBinding.DOCUMENT_ORDER);
        epochs.keys().forEach(ref -> ids.add(ref.documentId()));
        var rows = new ArrayList<Map.Entry<DocumentId, DocumentHistory>>();
        for (var id : ids) { var history = get(id); if (history != null) rows.add(Map.entry(id, history)); }
        return List.copyOf(rows);
    }
    @Override public Map.Entry<DocumentId, DocumentHistory> first(DocumentId lower, boolean exclusive, DocumentId upper) {
        return entries().stream().filter(row -> (lower == null || EmbeddingBinding.DOCUMENT_ORDER.compare(row.getKey(), lower) >= (exclusive ? 1 : 0))
                && (upper == null || EmbeddingBinding.DOCUMENT_ORDER.compare(row.getKey(), upper) < 0)).findFirst().orElse(null);
    }
    private void select(DocumentId owner, DocumentHistory selected) {
        var ref = instances.requireOrCreateInitial(owner);
        if (selected == null) {
            epochs.remove(ref).map().selectLogicalRecords(); heads.remove(ref).map().selectLogicalRecords(); return;
        }
        if (!owner.equals(selected.documentId())) throw new IllegalArgumentException("Receipt history is bound to another owner");
        var state = selected.storedState();
        epochs.put(ref, state.receipts()).map().selectLogicalRecords();
        heads.put(ref, new DocumentHistory.Head(state.latestEpoch(), state.currentRepresentation())).map().selectLogicalRecords();
    }
}
