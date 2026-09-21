package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.storage.CoordinationRecords.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Original numbered-history membership for shared canonical receipt content. */
final class LogicalReceiptOrigins {
    private static final String FORMAT = "blue-coordination/receipt-instance-origin/1";
    private static final Bytes SCOPE = new Bytes(OrderedRecordKey.text().encode(FORMAT));
    private LogicalReceiptOrigins() { }
    static void retain(LogicalRecordContext context, ManagedEpochReceipt receipt, int maximumBytes) {
        var key = key(receipt.receiptIdentity()); var prior = context.read(key);
        if (prior.present()) { requireOrigin(context, receipt, maximumBytes); return; }
        var ref = context.instances(maximumBytes).requireOrCreateInitial(receipt.documentId());
        context.select(key, new Bytes(encode(maximumBytes, w -> {
            w.text(FORMAT); w.text(receipt.receiptIdentity()); w.text(ref.documentId().value()); w.text(ref.instanceId());
        })));
    }
    static DocumentInstanceRef requireOrigin(LogicalRecordContext context, ManagedEpochReceipt receipt, int maximumBytes) {
        return context.protect(() -> {
            var value = context.read(key(receipt.receiptIdentity())); require(value.present(), "Canonical receipt lacks its original instance membership");
            var ref = decode(value.content().copy(), maximumBytes, r -> {
                require(FORMAT.equals(r.text(r.remaining())) && receipt.receiptIdentity().equals(r.text(r.remaining())), "Receipt origin identity differs");
                return new DocumentInstanceRef(DocumentId.of(r.text(r.remaining())), r.text(r.remaining()));
            });
            require(ref.documentId().equals(receipt.documentId()), "Receipt origin has another semantic owner");
            context.instances(maximumBytes).requireRetained(ref); return ref;
        });
    }
    private static Key key(String identity) { return new Key(Family.RECEIPT_IDENTITY, SCOPE, new Bytes(OrderedRecordKey.text().encode(identity))); }
}
