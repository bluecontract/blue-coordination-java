package blue.coordination.internal;

import blue.coordination.api.DocumentId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic in-memory document store. */
final class InMemoryDocumentStore {
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

    public synchronized void remove(DocumentId documentId) {
        sessions.remove(Objects.requireNonNull(documentId, "documentId"));
    }

    public synchronized Collection<DocumentSession> sessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    public synchronized int size() {
        return sessions.size();
    }

}
