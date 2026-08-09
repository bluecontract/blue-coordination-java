package blue.coordination.basic.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic in-memory document store. */
public final class InMemoryDocumentStore {
    private final Map<DocumentId, DocumentSession> sessions =
            new LinkedHashMap<>();

    public synchronized Optional<DocumentSession> find(DocumentId documentId) {
        return Optional.ofNullable(sessions.get(
                Objects.requireNonNull(documentId, "documentId")));
    }

    public synchronized DocumentSession require(DocumentId documentId) {
        return find(documentId).orElseThrow(() ->
                new IllegalArgumentException("Unknown document " + documentId));
    }

    public synchronized void insert(DocumentSession session) {
        Objects.requireNonNull(session, "session");
        DocumentSession previous = sessions.putIfAbsent(
                session.documentId(), session);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Duplicate document session " + session.documentId());
        }
    }

    public synchronized Collection<DocumentSession> sessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    public synchronized int size() {
        return sessions.size();
    }

    public synchronized Map<DocumentId, DocumentSession> snapshot() {
        Map<DocumentId, DocumentSession> copy = new LinkedHashMap<>();
        sessions.forEach((id, session) -> copy.put(id, session.copy()));
        return Collections.unmodifiableMap(copy);
    }

    public synchronized void restore(
            Map<DocumentId, DocumentSession> snapshot) {
        sessions.clear();
        Objects.requireNonNull(snapshot, "snapshot").forEach(sessions::put);
    }
}
