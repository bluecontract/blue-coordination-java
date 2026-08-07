package blue.coordination.engine.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable result of a revision-bound managed-session removal. */
public final class DocumentRemovalResult {

    private final DocumentRemovalStatus status;
    private final ManagedDocumentSnapshot session;

    public DocumentRemovalResult(
            DocumentRemovalStatus status,
            ManagedDocumentSnapshot session) {
        this.status = Objects.requireNonNull(status, "status");
        this.session = session;
    }

    public DocumentRemovalStatus status() { return status; }
    public Optional<ManagedDocumentSnapshot> session() {
        return Optional.ofNullable(session);
    }
}
