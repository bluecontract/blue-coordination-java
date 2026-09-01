package blue.coordination.api;

import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete self-verifying evidence for one committed managed source epoch.
 * Receipt epochs are non-negative; {@code -1} is only a selection sentinel.
 */
public final class ManagedEpochReceipt {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-epoch-receipt/1.0";

    private final String receiptIdentity;
    private final DocumentId documentId;
    private final long epoch;
    private final DocumentRevision.Kind kind;
    private final String beforeBlueId;
    private final ExactValue afterDocument;
    private final String originalCauseIdentity;
    private final TimelineEntry sourceEntry;
    private final ExternalOrderKey sourceOrder;
    private final String contractsTransitionReceiptIdentity;
    private final String commitCompanionIdentity;
    private final List<ManagedEventOccurrence> emittedEvents;
    private final long processingGas;

    /** Creates and verifies one immutable managed epoch receipt. */
    public ManagedEpochReceipt(
            String receiptIdentity,
            DocumentId documentId,
            long epoch,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            ExactValue afterDocument,
            String originalCauseIdentity,
            TimelineEntry sourceEntry,
            ExternalOrderKey sourceOrder,
            String contractsTransitionReceiptIdentity,
            String commitCompanionIdentity,
            List<ManagedEventOccurrence> emittedEvents,
            long processingGas) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.epoch = ManagedIdentity.requireSafeInteger(epoch, "epoch");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.beforeBlueId = beforeBlueId == null ? null
                : BlueIds.requireBlueIdOrCyclicMember(
                        beforeBlueId, "/beforeBlueId");
        this.afterDocument = Objects.requireNonNull(
                afterDocument, "afterDocument");
        this.originalCauseIdentity = ManagedIdentity.requireSha256(
                originalCauseIdentity, "originalCauseIdentity");
        this.sourceEntry = sourceEntry;
        this.sourceOrder = sourceEntry == null ? sourceOrder
                : requireMatchingSourceOrder(sourceEntry, sourceOrder);
        this.contractsTransitionReceiptIdentity =
                ManagedIdentity.requireSha256(
                        contractsTransitionReceiptIdentity,
                        "contractsTransitionReceiptIdentity");
        this.commitCompanionIdentity = ManagedIdentity.requireSha256(
                commitCompanionIdentity, "commitCompanionIdentity");
        this.emittedEvents = immutableEvents(emittedEvents, documentId);
        this.processingGas = ManagedIdentity.requireSafeInteger(
                processingGas, "processingGas");
        requireKindSemantics();
        this.receiptIdentity = ManagedIdentity.verify(
                receiptIdentity,
                IDENTITY_DOMAIN,
                identityValue(),
                "receiptIdentity");
    }

    /** Derives the canonical identity from all exact receipt fields. */
    public static ManagedEpochReceipt identified(
            DocumentId documentId,
            long epoch,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            ExactValue afterDocument,
            String originalCauseIdentity,
            TimelineEntry sourceEntry,
            ExternalOrderKey sourceOrder,
            String contractsTransitionReceiptIdentity,
            String commitCompanionIdentity,
            List<ManagedEventOccurrence> emittedEvents,
            long processingGas) {
        ManagedEpochReceipt provisional = new ManagedEpochReceipt(
                ManagedIdentity.identify(
                        IDENTITY_DOMAIN,
                        identityValue(
                                documentId,
                                epoch,
                                kind,
                                beforeBlueId,
                                afterDocument,
                                originalCauseIdentity,
                                sourceEntry,
                                sourceOrder,
                                contractsTransitionReceiptIdentity,
                                commitCompanionIdentity,
                                emittedEvents,
                                processingGas)),
                documentId,
                epoch,
                kind,
                beforeBlueId,
                afterDocument,
                originalCauseIdentity,
                sourceEntry,
                sourceOrder,
                contractsTransitionReceiptIdentity,
                commitCompanionIdentity,
                emittedEvents,
                processingGas);
        return provisional;
    }

    public String receiptIdentity() {
        return receiptIdentity;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public long epoch() {
        return epoch;
    }

    public DocumentRevision.Kind kind() {
        return kind;
    }

    public Optional<String> beforeBlueId() {
        return Optional.ofNullable(beforeBlueId);
    }

    public String afterBlueId() {
        return afterDocument.blueId();
    }

    public ExactValue afterDocument() {
        return afterDocument;
    }

    public String originalCauseIdentity() {
        return originalCauseIdentity;
    }

    public Optional<TimelineEntry> sourceEntry() {
        return Optional.ofNullable(sourceEntry);
    }

    public Optional<ExternalOrderKey> sourceOrder() {
        return Optional.ofNullable(sourceOrder);
    }

    /**
     * Returns the Contracts source-revision evidence identity. It is a
     * transition-receipt identity when Contracts produced a document
     * transition, or the Contracts-derived state-only identity for an
     * unchanged, eventless managed application.
     */
    public String contractsTransitionReceiptIdentity() {
        return contractsTransitionReceiptIdentity;
    }

    public String commitCompanionIdentity() {
        return commitCompanionIdentity;
    }

    public List<ManagedEventOccurrence> emittedEvents() {
        return emittedEvents;
    }

    public long processingGas() {
        return processingGas;
    }

    private void requireKindSemantics() {
        if (kind == DocumentRevision.Kind.INITIALIZATION
                && (epoch != 0L || beforeBlueId != null)) {
            throw new IllegalArgumentException(
                    "INITIALIZATION requires epoch zero and no before state");
        }
        if (kind == DocumentRevision.Kind.EVENT_ONLY) {
            if (beforeBlueId == null
                    || !beforeBlueId.equals(afterDocument.blueId())
                    || emittedEvents.isEmpty()) {
                throw new IllegalArgumentException(
                        "EVENT_ONLY requires equal before/after BlueIds and events");
            }
        }
        if (sourceEntry != null && sourceOrder == null) {
            throw new IllegalArgumentException(
                    "A source entry requires its exact source order");
        }
    }

    private Map<String, Object> identityValue() {
        return identityValue(
                documentId,
                epoch,
                kind,
                beforeBlueId,
                afterDocument,
                originalCauseIdentity,
                sourceEntry,
                sourceOrder,
                contractsTransitionReceiptIdentity,
                commitCompanionIdentity,
                emittedEvents,
                processingGas);
    }

    private static Map<String, Object> identityValue(
            DocumentId documentId,
            long epoch,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            ExactValue afterDocument,
            String originalCauseIdentity,
            TimelineEntry sourceEntry,
            ExternalOrderKey sourceOrder,
            String contractsTransitionReceiptIdentity,
            String commitCompanionIdentity,
            List<ManagedEventOccurrence> emittedEvents,
            long processingGas) {
        DocumentId document = Objects.requireNonNull(documentId, "documentId");
        DocumentRevision.Kind revisionKind = Objects.requireNonNull(kind, "kind");
        ExactValue after = Objects.requireNonNull(afterDocument, "afterDocument");
        ExternalOrderKey order = sourceEntry == null ? sourceOrder
                : requireMatchingSourceOrder(sourceEntry, sourceOrder);
        List<ManagedEventOccurrence> events = immutableEvents(
                emittedEvents, document);
        ArrayList<Object> eventValues = new ArrayList<>();
        events.forEach(event -> eventValues.add(ManagedIdentity.fields(
                "managedEventIdentity", event.managedEventIdentity(),
                "ordinal", event.ordinal(),
                "eventOccurrenceOrdinal", event.eventOccurrenceOrdinal(),
                "sourceDocumentId", event.sourceDocumentId().value(),
                "eventOccurrenceIdentity", event.eventOccurrenceIdentity(),
                "eventBlueId", event.eventBlueId(),
                "publicAtSource", event.publicAtSource())));
        return ManagedIdentity.fields(
                "documentId", document.value(),
                "epoch", epoch,
                "kind", revisionKind.name(),
                "beforeBlueId", beforeBlueId,
                "afterBlueId", after.blueId(),
                "originalCauseIdentity", originalCauseIdentity,
                "sourceEntryBlueId",
                sourceEntry == null ? null : sourceEntry.blueId(),
                "sourceOrder",
                order == null ? null : ManagedIdentity.canonicalOrderComponents(
                        order.components()),
                "contractsTransitionReceiptIdentity",
                contractsTransitionReceiptIdentity,
                "commitCompanionIdentity", commitCompanionIdentity,
                "emittedEvents", eventValues,
                "processingGas", processingGas);
    }

    private static ExternalOrderKey requireMatchingSourceOrder(
            TimelineEntry sourceEntry,
            ExternalOrderKey sourceOrder) {
        TimelineEntry entry = Objects.requireNonNull(sourceEntry, "sourceEntry");
        ExternalOrderKey selected = sourceOrder == null
                ? entry.sourceOrderKey()
                : sourceOrder;
        if (!entry.sourceOrderKey().equals(selected)) {
            throw new IllegalArgumentException(
                    "sourceEntry and sourceOrder must match");
        }
        return selected;
    }

    private static List<ManagedEventOccurrence> immutableEvents(
            List<ManagedEventOccurrence> events,
            DocumentId documentId) {
        List<ManagedEventOccurrence> copy = List.copyOf(
                Objects.requireNonNull(events, "emittedEvents"));
        long priorOrdinal = -1L;
        for (ManagedEventOccurrence event : copy) {
            ManagedEventOccurrence occurrence = Objects.requireNonNull(
                    event, "emittedEvent");
            if (!occurrence.sourceDocumentId().equals(documentId)) {
                throw new IllegalArgumentException(
                        "Managed event source differs from receipt document");
            }
            if (occurrence.ordinal() != priorOrdinal + 1L) {
                throw new IllegalArgumentException(
                        "Managed event ordinals must be contiguous from zero");
            }
            priorOrdinal = occurrence.ordinal();
        }
        return copy;
    }
}
