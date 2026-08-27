package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/** Immutable SDK projection of one complete managed Root event occurrence. */
public final class ManagedEventOccurrence {
    private final blue.coordination.api.ManagedEventOccurrence occurrence;

    private ManagedEventOccurrence(
            blue.coordination.api.ManagedEventOccurrence occurrence) {
        this.occurrence = Objects.requireNonNull(occurrence, "occurrence");
    }

    static ManagedEventOccurrence wrap(
            blue.coordination.api.ManagedEventOccurrence occurrence) {
        return new ManagedEventOccurrence(occurrence);
    }

    /** Canonical Coordination identity for this retained event occurrence. */
    public String managedEventIdentity() {
        return occurrence.managedEventIdentity();
    }

    /** Zero-based occurrence ordinal within its source transition. */
    public long ordinal() {
        return occurrence.ordinal();
    }

    /** Original invocation-global Contracts event occurrence ordinal. */
    public long eventOccurrenceOrdinal() {
        return occurrence.eventOccurrenceOrdinal();
    }

    /** Managed source document that emitted this exact event. */
    public DocumentId sourceDocumentId() {
        return occurrence.sourceDocumentId();
    }

    /** Authenticated Contracts occurrence identity. */
    public String eventOccurrenceIdentity() {
        return occurrence.eventOccurrenceIdentity();
    }

    /** Exact event content identity. */
    public String eventBlueId() {
        return occurrence.eventBlueId();
    }

    /** Complete exact event value. */
    public ExactBlueValue exactEvent() {
        return ExactBlueValue.wrap(occurrence.exactEvent());
    }

    /** Whether the emitting source Root was public at this transition. */
    public boolean publicAtSource() {
        return occurrence.publicAtSource();
    }
}
