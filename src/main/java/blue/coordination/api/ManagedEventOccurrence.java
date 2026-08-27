package blue.coordination.api;

import java.util.Map;
import java.util.Objects;

/**
 * One exact Root-boundary event occurrence retained for a managed source epoch.
 *
 * <p>Equal event values remain separate occurrences. The Coordination identity
 * binds the Contracts occurrence identity and every exposed semantic field.</p>
 */
public final class ManagedEventOccurrence {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-event-occurrence/1.0";

    private final String managedEventIdentity;
    private final long ordinal;
    private final long eventOccurrenceOrdinal;
    private final DocumentId sourceDocumentId;
    private final String eventOccurrenceIdentity;
    private final ExactValue exactEvent;
    private final boolean publicAtSource;

    /** Creates and verifies one complete immutable occurrence. */
    public ManagedEventOccurrence(
            String managedEventIdentity,
            long ordinal,
            long eventOccurrenceOrdinal,
            DocumentId sourceDocumentId,
            String eventOccurrenceIdentity,
            ExactValue exactEvent,
            boolean publicAtSource) {
        this.ordinal = ManagedIdentity.requireSafeInteger(ordinal, "ordinal");
        this.eventOccurrenceOrdinal = ManagedIdentity.requireSafeInteger(
                eventOccurrenceOrdinal, "eventOccurrenceOrdinal");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.eventOccurrenceIdentity = ManagedIdentity.requireSha256(
                eventOccurrenceIdentity, "eventOccurrenceIdentity");
        this.exactEvent = Objects.requireNonNull(exactEvent, "exactEvent");
        this.publicAtSource = publicAtSource;
        this.managedEventIdentity = ManagedIdentity.verify(
                managedEventIdentity,
                IDENTITY_DOMAIN,
                identityValue(),
                "managedEventIdentity");
    }

    /** Derives the canonical Coordination identity from exact occurrence data. */
    public static ManagedEventOccurrence identified(
            long ordinal,
            long eventOccurrenceOrdinal,
            DocumentId sourceDocumentId,
            String eventOccurrenceIdentity,
            ExactValue exactEvent,
            boolean publicAtSource) {
        DocumentId source = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        String contractsIdentity = ManagedIdentity.requireSha256(
                eventOccurrenceIdentity, "eventOccurrenceIdentity");
        ExactValue event = Objects.requireNonNull(exactEvent, "exactEvent");
        Map<String, Object> value = identityValue(
                ordinal,
                eventOccurrenceOrdinal,
                source,
                contractsIdentity,
                event,
                publicAtSource);
        return new ManagedEventOccurrence(
                ManagedIdentity.identify(IDENTITY_DOMAIN, value),
                ordinal,
                eventOccurrenceOrdinal,
                source,
                contractsIdentity,
                event,
                publicAtSource);
    }

    /** Canonical Coordination identity of this retained occurrence. */
    public String managedEventIdentity() {
        return managedEventIdentity;
    }

    /** Zero-based ordinal within this managed transition. */
    public long ordinal() {
        return ordinal;
    }

    /** Original invocation-global Contracts event occurrence ordinal. */
    public long eventOccurrenceOrdinal() {
        return eventOccurrenceOrdinal;
    }

    /** Managed source document that emitted the event. */
    public DocumentId sourceDocumentId() {
        return sourceDocumentId;
    }

    /** Authenticated Contracts event occurrence identity. */
    public String eventOccurrenceIdentity() {
        return eventOccurrenceIdentity;
    }

    /** Exact event BlueId. */
    public String eventBlueId() {
        return exactEvent.blueId();
    }

    /** Complete immutable exact event value. */
    public ExactValue exactEvent() {
        return exactEvent;
    }

    /** Whether the source Root was public when it emitted this occurrence. */
    public boolean publicAtSource() {
        return publicAtSource;
    }

    Map<String, Object> identityValue() {
        return identityValue(
                ordinal,
                eventOccurrenceOrdinal,
                sourceDocumentId,
                eventOccurrenceIdentity,
                exactEvent,
                publicAtSource);
    }

    private static Map<String, Object> identityValue(
            long ordinal,
            long eventOccurrenceOrdinal,
            DocumentId sourceDocumentId,
            String eventOccurrenceIdentity,
            ExactValue exactEvent,
            boolean publicAtSource) {
        ManagedIdentity.requireSafeInteger(ordinal, "ordinal");
        ManagedIdentity.requireSafeInteger(
                eventOccurrenceOrdinal, "eventOccurrenceOrdinal");
        return ManagedIdentity.fields(
                "ordinal", ordinal,
                "eventOccurrenceOrdinal", eventOccurrenceOrdinal,
                "sourceDocumentId", sourceDocumentId.value(),
                "eventOccurrenceIdentity", eventOccurrenceIdentity,
                "eventBlueId", exactEvent.blueId(),
                "publicAtSource", publicAtSource);
    }
}
