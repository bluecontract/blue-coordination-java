package blue.coordination.internal;

import blue.coordination.api.DocumentInstanceRef;
import java.util.Comparator;

/** Native selectors; semantic identities and processor receipt payloads remain unchanged. */
final class InstanceSourceKeys {
    private InstanceSourceKeys() { }
    static final Comparator<DocumentInstanceRef> ORDER = Comparator.comparing(DocumentInstanceRef::documentId,
            EmbeddingBinding.DOCUMENT_ORDER).thenComparing(DocumentInstanceRef::instanceId, EmbeddingBinding.TEXT_ORDER);
    static final OrderedRecordKey<DocumentInstanceRef> INSTANCE = OrderedRecordKey.pair("document-instance",
            OrderedRecordKey.document(), OrderedRecordKey.text(), DocumentInstanceRef::documentId,
            DocumentInstanceRef::instanceId, DocumentInstanceRef::new);
    record Epoch(DocumentInstanceRef instance, long epoch) { }
    static final Comparator<Epoch> EPOCH_ORDER = Comparator.comparing(Epoch::instance, ORDER).thenComparingLong(Epoch::epoch);
    static final OrderedRecordKey<Epoch> EPOCH = OrderedRecordKey.pair("instance-source-epoch", INSTANCE,
            OrderedRecordKey.signedLong(), Epoch::instance, Epoch::epoch, Epoch::new);
}
