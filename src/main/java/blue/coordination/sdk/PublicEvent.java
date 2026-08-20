package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;
import java.util.Optional;

/** Immutable exact event publicly emitted by committed Coordination work. */
public final class PublicEvent {
    private final ExactBlueValue exact;
    private final DocumentId sourceDocument;
    private final String occurrencePath;

    /** Creates an event when no managed-document source applies. */
    public PublicEvent(ExactBlueValue exact) {
        this(exact, null, null);
    }

    /** Creates an event with optional exact managed-occurrence evidence. */
    public PublicEvent(
            ExactBlueValue exact,
            DocumentId sourceDocument,
            String occurrencePath) {
        this.exact = Objects.requireNonNull(exact, "exact");
        this.sourceDocument = sourceDocument;
        this.occurrencePath = occurrencePath == null
                ? null
                : SdkPreconditions.requireOccurrencePath(occurrencePath);
        if (sourceDocument == null && occurrencePath != null) {
            throw new IllegalArgumentException(
                    "Occurrence evidence requires a source document");
        }
    }

    /** Exact event value. */
    public ExactBlueValue exact() {
        return exact;
    }

    /** Exact event BlueId. */
    public String blueId() {
        return exact.blueId();
    }

    /** Managed source document when this event came from a document step. */
    public Optional<DocumentId> sourceDocument() {
        return Optional.ofNullable(sourceDocument);
    }

    /** Managed occurrence path when available. */
    public Optional<String> occurrencePath() {
        return Optional.ofNullable(occurrencePath);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PublicEvent event
                && exact.equals(event.exact)
                && Objects.equals(sourceDocument, event.sourceDocument)
                && Objects.equals(occurrencePath, event.occurrencePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exact, sourceDocument, occurrencePath);
    }
}
