package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstancePosition;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Original publication owners resolve through authenticated instance history, never today's session head. */
final class LogicalPublicationInstances {
    private static final String FORMAT = "blue-coordination/publication-instances/1";
    private static final Bytes SCOPE = new Bytes(OrderedRecordKey.text().encode(FORMAT));
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final LogicalInstanceHistory history;
    private final DocumentSessionStorage.OpenScope views;
    private final int maximumBytes;

    LogicalPublicationInstances(LogicalRecordContext context, LogicalDocumentInstances instances,
            LogicalInstanceHistory history, DocumentSessionStorage.OpenScope views, int maximumBytes) {
        this.context = context; this.instances = instances; this.history = history;
        this.views = views; this.maximumBytes = maximumBytes;
    }

    void retain(Family kind, String identity, List<DocumentId> documents,
            PersistentOrderedMap<DocumentId, DocumentSession> sessions) {
        retain(key(kind, identity), kind, identity, documents, sessions);
    }

    void retainExecution(DocumentInstanceRef observer, String identity, List<DocumentId> documents,
            PersistentOrderedMap<DocumentId, DocumentSession> sessions) {
        retain(executionKey(observer, identity), Family.CLOSURE, identity, documents, sessions);
    }

    boolean hasExecution(DocumentInstanceRef observer, String identity) {
        return context.protect(() -> context.read(executionKey(observer, identity)).present());
    }

    private void retain(Key key, Family kind, String identity, List<DocumentId> documents,
            PersistentOrderedMap<DocumentId, DocumentSession> sessions) {
        context.protect(() -> {
            // An unchanged original result keeps its original instance association.
            if (context.read(key).present()) return null;
            var positions = new LinkedHashMap<DocumentId, DocumentInstancePosition>();
            try (var retention = views.openRetentionStage()) {
                for (var id : documents) {
                    var session = sessions.get(id);
                    if (session == null) continue; // Canonical rejected virtual drafts authenticate their original absence.
                    var ref = instances.requireOrCreateInitial(id);
                    history.retain(ref, session, retention.retain(session));
                    positions.put(id, history.position(ref, session));
                }
            }
            require(!positions.isEmpty(), "Publication has no retained instance owner");
            var encoded = new Bytes(encode(maximumBytes, writer -> {
                writer.text(FORMAT); writer.text(kind.name()); writer.text(identity); writer.integer(documents.size());
                for (var id : documents) {
                    writer.text(id.value()); var position = positions.get(id); writer.bool(position != null);
                    if (position != null) {
                        writer.text(position.instance().instanceId()); writer.longValue(position.epoch());
                        writer.text(position.invocationIdentity());
                    }
                }
            }));
            context.select(key, encoded); return null;
        });
    }

    Map<DocumentId, DocumentSession> open(Family kind, String identity, List<DocumentId> documents) {
        return open(key(kind, identity), kind, identity, documents, null);
    }

    Map<DocumentId, DocumentSession> openExecution(DocumentInstanceRef observer, String identity, List<DocumentId> documents) {
        return open(executionKey(observer, identity), Family.CLOSURE, identity, documents, observer);
    }

    private Map<DocumentId, DocumentSession> open(Key key, Family kind, String identity, List<DocumentId> documents,
            DocumentInstanceRef observer) {
        return context.protect(() -> {
            var result = new LinkedHashMap<DocumentId, DocumentSession>();
            positions(key, kind, identity, documents, observer).forEach((id, position) -> result.put(id, history.open(position)));
            return Collections.unmodifiableMap(result);
        });
    }

    Optional<DocumentSession> retiredOriginalAdmission(String identity, List<DocumentId> documents, DocumentId document) {
        return context.protect(() -> {
            var position = positions(key(Family.ADMISSION, identity), Family.ADMISSION, identity, documents, null).get(document);
            require(position != null, "Admission has no original retained document");
            return instances.retired(position.instance()) ? Optional.of(history.open(position)) : Optional.empty();
        });
    }

    private Map<DocumentId, DocumentInstancePosition> positions(Key key, Family kind, String identity,
            List<DocumentId> documents, DocumentInstanceRef observer) {
        var value = context.read(key);
        require(value.present(), "Publication has no original instance association");
        return decode(value.content().copy(), maximumBytes, reader -> {
            require(FORMAT.equals(reader.text(reader.remaining())) && kind.name().equals(reader.text(reader.remaining()))
                    && identity.equals(reader.text(reader.remaining())), "Publication instance association differs");
            require(reader.count(Integer.MAX_VALUE, 5) == documents.size(), "Publication owner count differs");
            var result = new LinkedHashMap<DocumentId, DocumentInstancePosition>();
            for (var id : documents) {
                require(id.value().equals(reader.text(reader.remaining())), "Publication owner order differs");
                if (reader.bool()) {
                    var ref = new DocumentInstanceRef(id, reader.text(reader.remaining()));
                    var position = new DocumentInstancePosition(ref, reader.longValue(), reader.text(reader.remaining()));
                    if (observer != null && id.equals(observer.documentId())) require(ref.equals(observer), "Execution archive belongs to another observer instance");
                    require(result.put(id, position) == null, "Repeated publication owner");
                }
            }
            require(!result.isEmpty(), "Publication has no retained instance owner");
            if (observer != null) require(result.containsKey(observer.documentId()), "Execution archive lacks its observer");
            return Collections.unmodifiableMap(result);
        });
    }

    private static Key executionKey(DocumentInstanceRef observer, String identity) {
        return new Key(Family.INSTANCE_PUBLICATION,
                new Bytes(OrderedRecordKey.text().encode("blue-coordination/publication-execution/1")),
                new Bytes(OrderedRecordKey.tuple(InstanceSourceKeys.INSTANCE.encode(observer), OrderedRecordKey.text().encode(identity))));
    }

    private static Key key(Family kind, String identity) {
        require(kind == Family.ADMISSION || kind == Family.CLOSURE, "Not a publication receipt family");
        return new Key(Family.INSTANCE_PUBLICATION, SCOPE, new Bytes(OrderedRecordKey.tuple(
                OrderedRecordKey.text().encode(kind.name()), OrderedRecordKey.text().encode(identity))));
    }
}
