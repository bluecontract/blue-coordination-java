package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Retains the last retired catalog role, independently of the selected semantic basis. */
final class LogicalInstanceCatalog {
    private static final String FORMAT = "blue-coordination/instance-catalog/1";
    private final LogicalRecordContext context;
    private final int maximumBytes;
    LogicalInstanceCatalog(LogicalRecordContext context, int maximumBytes) {
        this.context = context; this.maximumBytes = maximumBytes;
    }
    void retain(LogicalDocumentInstances.Binding binding, boolean publicRoot) {
        var reference = binding.instance().orElseThrow();
        context.select(key(binding.document()), new Bytes(encode(maximumBytes, writer -> {
            writer.text(FORMAT); writer.text(reference.documentId().value()); writer.text(reference.instanceId());
            writer.longValue(binding.generation()); writer.bool(publicRoot);
        })));
    }
    boolean startingRole(DocumentId document) {
        var ledger = context.instances(maximumBytes); var binding = ledger.select(document);
        require(binding.instance().isEmpty(), "Catalog role requires absent execution authority");
        var value = context.read(key(document));
        require(value.present(), "Starting instance lacks retained catalog authority");
        return decode(value.content().copy(), maximumBytes, reader -> {
            require(FORMAT.equals(reader.text(reader.remaining())), "Unknown instance catalog format");
            var original = new DocumentInstanceRef(DocumentId.of(reader.text(reader.remaining())), reader.text(reader.remaining()));
            long generation = reader.longValue(); boolean publicRoot = reader.bool();
            require(original.documentId().equals(document) && generation > 0 && generation < Long.MAX_VALUE
                    && binding.generation() == generation + 1, "Retained catalog role belongs to another lifecycle transition");
            require(ledger.retired(original), "Catalog role belongs to an unretired instance");
            return publicRoot;
        });
    }
    private static Key key(DocumentId document) {
        return new Key(Family.INSTANCE_IDENTITY, new Bytes(OrderedRecordKey.text().encode(FORMAT)),
                new Bytes(OrderedRecordKey.document().encode(document)));
    }
}
