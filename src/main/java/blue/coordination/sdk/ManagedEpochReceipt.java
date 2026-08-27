package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete immutable SDK evidence for one committed managed source epoch.
 * Receipt epochs are non-negative; {@code -1} is only a selection sentinel.
 */
public final class ManagedEpochReceipt {
    private final blue.coordination.api.ManagedEpochReceipt receipt;
    private final TimelineEntrySnapshot sourceEntry;
    private final List<ManagedEventOccurrence> emittedEvents;

    ManagedEpochReceipt(
            blue.coordination.api.ManagedEpochReceipt receipt,
            TimelineEntrySnapshot sourceEntry) {
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.sourceEntry = sourceEntry;
        if (receipt.sourceEntry().isPresent() != (sourceEntry != null)) {
            throw new IllegalArgumentException(
                    "SDK source entry must match the low-level receipt");
        }
        this.emittedEvents = receipt.emittedEvents().stream()
                .map(ManagedEventOccurrence::wrap)
                .toList();
    }

    /** Canonical self-verifying Coordination receipt identity. */
    public String receiptIdentity() {
        return receipt.receiptIdentity();
    }

    /** Stable managed source lineage. */
    public DocumentId documentId() {
        return receipt.documentId();
    }

    /** Contiguous managed source epoch. */
    public long epoch() {
        return receipt.epoch();
    }

    /** Existing document revision vocabulary for this source transition. */
    public DocumentRevision.Kind kind() {
        return DocumentRevision.Kind.valueOf(receipt.kind().name());
    }

    /** Exact source state before this epoch, absent when not applicable. */
    public Optional<String> beforeBlueId() {
        return receipt.beforeBlueId();
    }

    /** Exact source state identity after this epoch. */
    public String afterBlueId() {
        return receipt.afterBlueId();
    }

    /** Complete exact source document after this epoch. */
    public ExactBlueValue afterDocument() {
        return ExactBlueValue.wrap(receipt.afterDocument());
    }

    /** Exact Contracts cause that originally produced this transition. */
    public String originalCauseIdentity() {
        return receipt.originalCauseIdentity();
    }

    /** Original external Timeline Entry, when this was an external transition. */
    public Optional<TimelineEntrySnapshot> sourceEntry() {
        return Optional.ofNullable(sourceEntry);
    }

    /** Original canonical source ordering evidence, when present. */
    public Optional<SourceOrder> sourceOrder() {
        return receipt.sourceOrder()
                .map(order -> new SourceOrder(order.components()));
    }

    /** Contracts source-revision evidence for this managed epoch. */
    public String contractsTransitionReceiptIdentity() {
        return receipt.contractsTransitionReceiptIdentity();
    }

    /** Platform commit companion that made this source epoch authoritative. */
    public String commitCompanionIdentity() {
        return receipt.commitCompanionIdentity();
    }

    /** Complete ordered duplicate-preserving Root event occurrences. */
    public List<ManagedEventOccurrence> emittedEvents() {
        return emittedEvents;
    }

    /** Exact semantic processing gas attributed to this source epoch. */
    public long processingGas() {
        return receipt.processingGas();
    }
}
