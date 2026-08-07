package blue.coordination.engine.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable authoritative outcome of one session-store CAS transaction. */
public final class CommitOutcome {

    private final CommitStatus status;
    private final ManagedDocumentSnapshot session;
    private final String transitionIdentity;

    public CommitOutcome(
            CommitStatus status,
            ManagedDocumentSnapshot session,
            String transitionIdentity) {
        this.status = Objects.requireNonNull(status, "status");
        this.session = session;
        this.transitionIdentity = Objects.requireNonNull(
                transitionIdentity, "transitionIdentity");
    }

    public CommitStatus status() { return status; }
    public Optional<ManagedDocumentSnapshot> session() {
        return Optional.ofNullable(session);
    }
    public String transitionIdentity() { return transitionIdentity; }
    public boolean committed() {
        return status == CommitStatus.COMMITTED
                || status == CommitStatus.ALREADY_COMMITTED;
    }
}
