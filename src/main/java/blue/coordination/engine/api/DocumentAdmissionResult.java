package blue.coordination.engine.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable result of an admission or attachment attempt. */
public final class DocumentAdmissionResult {

    private final DocumentAdmissionStatus status;
    private final ManagedDocumentSnapshot session;
    private final String diagnostic;

    public DocumentAdmissionResult(
            DocumentAdmissionStatus status,
            ManagedDocumentSnapshot session,
            String diagnostic) {
        this.status = Objects.requireNonNull(status, "status");
        this.session = session;
        this.diagnostic = diagnostic;
        boolean success = status == DocumentAdmissionStatus.CREATED
                || status == DocumentAdmissionStatus.ATTACHED_CURRENT
                || status == DocumentAdmissionStatus.ATTACHED_TO_CURRENT;
        if (success != (session != null)) {
            throw new IllegalArgumentException(
                    "Successful admission results require a session and "
                            + "non-success results cannot expose one");
        }
    }

    public DocumentAdmissionStatus status() { return status; }
    public Optional<ManagedDocumentSnapshot> session() {
        return Optional.ofNullable(session);
    }
    public Optional<String> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }
    public boolean succeeded() {
        return session != null;
    }
}
