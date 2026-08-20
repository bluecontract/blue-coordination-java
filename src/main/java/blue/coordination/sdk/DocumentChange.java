package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable exact before/after evidence for one committed document epoch. */
public final class DocumentChange {
    private final DocumentId documentId;
    private final long epoch;
    private final ExactBlueValue before;
    private final ExactBlueValue after;
    private final List<PublicEvent> publicEvents;

    /** Creates one application-safe committed change. */
    public DocumentChange(
            DocumentId documentId,
            long epoch,
            ExactBlueValue before,
            ExactBlueValue after,
            List<PublicEvent> publicEvents) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.epoch = SdkPreconditions.requireNonNegative(epoch, "epoch");
        this.before = before;
        this.after = Objects.requireNonNull(after, "after");
        this.publicEvents = List.copyOf(Objects.requireNonNull(
                publicEvents, "publicEvents"));
    }

    /** Stable managed lineage changed by this step. */
    public DocumentId documentId() {
        return documentId;
    }

    /** Committed document-local epoch. */
    public long epoch() {
        return epoch;
    }

    /** Prior exact state, absent for initial admission. */
    public Optional<ExactBlueValue> before() {
        return Optional.ofNullable(before);
    }

    /** Exact state committed by this step. */
    public ExactBlueValue after() {
        return after;
    }

    /** Exact public events emitted by this step. */
    public List<PublicEvent> publicEvents() {
        return publicEvents;
    }
}
