package blue.coordination.internal;

import blue.coordination.api.DocumentInstancePosition;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.Objects;
import static blue.coordination.internal.SessionStorageWire.*;

/** Instance-authenticated immutable publication images; never resolves through the active head. */
final class LogicalInstanceHistory {
    private static final String FORMAT = "blue-coordination/instance-history/1";
    private static final Bytes SCOPE = new Bytes(OrderedRecordKey.text().encode(FORMAT));
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final DocumentSessionStorage.OpenScope views;
    private final int maximumRecordBytes;

    LogicalInstanceHistory(LogicalRecordContext context, LogicalDocumentInstances instances,
            DocumentSessionStorage.OpenScope views, int maximumRecordBytes) {
        this.context = Objects.requireNonNull(context); this.instances = Objects.requireNonNull(instances);
        this.views = Objects.requireNonNull(views); this.maximumRecordBytes = maximumRecordBytes;
    }

    DocumentInstancePosition position(DocumentInstanceRef instance, DocumentSession session) {
        require(instance.documentId().equals(session.documentId()), "Instance history has another document");
        var view = Objects.requireNonNull(session.rootedView(), "Instance history requires an actual rooted publication");
        view.requirePublishedHead(session.documentId(), session.epoch(), session.currentRepresentation().blueId());
        return new DocumentInstancePosition(instance, session.epoch(), view.result().invocationIdentity());
    }

    /** Called for each actual complete publication image, with its already prewritten session address. */
    void retain(DocumentInstanceRef instance, DocumentSession session, String address) {
        instances.requireActive(instance);
        var position = position(instance, session);
        var encoded = new Bytes(encode(maximumRecordBytes, writer -> {
            writer.text(FORMAT); writer.text(instance.documentId().value()); writer.text(instance.instanceId());
            writer.longValue(position.epoch()); writer.text(position.invocationIdentity()); writer.text(address);
        }));
        var key = key(position); var old = context.read(key).content();
        require(old == null || old.equals(encoded), "An existing instance publication position cannot be replaced");
        context.select(key, encoded);
    }

    DocumentSession open(DocumentInstancePosition position) {
        return context.protect(() -> {
            instances.requireRetained(position.instance());
            var value = context.read(key(position));
            require(value.present(), "Instance publication position is unavailable");
            String address = decode(value.content().copy(), maximumRecordBytes, reader -> {
                require(FORMAT.equals(reader.text(reader.remaining())), "Unknown instance history format");
                require(position.instance().documentId().value().equals(reader.text(reader.remaining()))
                        && position.instance().instanceId().equals(reader.text(reader.remaining()))
                        && position.epoch() == reader.longValue()
                        && position.invocationIdentity().equals(reader.text(reader.remaining())),
                        "Instance publication association differs from the selected position");
                return reader.text(reader.remaining());
            });
            var session = views.open(position.instance().documentId(), address);
            require(position.equals(position(position.instance(), session)), "Retained session differs from instance publication position");
            return session;
        });
    }

    private static Key key(DocumentInstancePosition position) {
        return new Key(Family.INSTANCE_HISTORY, SCOPE, new Bytes(OrderedRecordKey.tuple(
                OrderedRecordKey.text().encode(position.instance().instanceId()),
                OrderedRecordKey.signedLong().encode(position.epoch()),
                OrderedRecordKey.text().encode(position.invocationIdentity()))));
    }
}
