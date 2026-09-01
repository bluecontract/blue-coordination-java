package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ManagedEventOccurrence;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedRootEventOccurrence;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, document-local index of authenticated managed epoch receipts.
 *
 * <p>Every mutation path-copies only the selected document history and the two
 * top-level indexes. Reads expose their exact balanced-tree work so locality
 * tests do not have to infer storage behavior from wall-clock timing.</p>
 */
final class ManagedEpochReceiptStore {
    private static final Comparator<Long> EPOCH_ORDER = Long::compare;
    private static final ManagedEpochReceiptStore EMPTY =
            new ManagedEpochReceiptStore(
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.DOCUMENT_ORDER),
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    0,
                    0);

    private final PersistentOrderedMap<DocumentId, DocumentHistory> byDocument;
    private final PersistentOrderedMap<String, StoredReceipt> byIdentity;
    private final int lastMutationComparisons;
    private final int lastMutationNodeCopies;

    private ManagedEpochReceiptStore(
            PersistentOrderedMap<DocumentId, DocumentHistory> byDocument,
            PersistentOrderedMap<String, StoredReceipt> byIdentity,
            int lastMutationComparisons,
            int lastMutationNodeCopies) {
        this.byDocument = Objects.requireNonNull(byDocument, "byDocument");
        this.byIdentity = Objects.requireNonNull(byIdentity, "byIdentity");
        this.lastMutationComparisons = requireNonNegative(
                lastMutationComparisons, "lastMutationComparisons");
        this.lastMutationNodeCopies = requireNonNegative(
                lastMutationNodeCopies, "lastMutationNodeCopies");
    }

    static ManagedEpochReceiptStore empty() {
        return EMPTY;
    }

    /** Appends one receipt, or preserves identity for an exact duplicate. */
    ManagedEpochReceiptStore withReceipt(ManagedEpochReceipt receipt) {
        return withReceipt(receipt, null);
    }

    /**
     * Appends public epoch evidence together with the exact Contracts input
     * later required to construct a typed managed-revision cause.
     */
    ManagedEpochReceiptStore withReceipt(
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        ManagedEpochReceipt selected = Objects.requireNonNull(
                receipt, "receipt");
        StoredReceipt selectedRow = StoredReceipt.verified(
                selected, transitionReceipt);
        PersistentOrderedMap.ReadResult<StoredReceipt> identityRead =
                byIdentity.read(selected.receiptIdentity());
        PersistentOrderedMap.ReadResult<DocumentHistory> documentRead =
                byDocument.read(selected.documentId());
        int readComparisons = Math.addExact(
                identityRead.comparisons(), documentRead.comparisons());

        StoredReceipt sameIdentity = identityRead.value();
        DocumentHistory history = documentRead.value();
        StoredReceipt sameEpoch = history == null ? null
                : history.receipt(selected.epoch());
        if (sameIdentity != null || sameEpoch != null) {
            if (sameIdentity != null
                    && sameEpoch != null
                    && sameIdentity.publicReceipt().receiptIdentity().equals(
                            selected.receiptIdentity())
                    && sameIdentity.publicReceipt().documentId().equals(
                            selected.documentId())
                    && sameIdentity.publicReceipt().epoch()
                            == selected.epoch()) {
                if (sameIdentity.transitionReceipt() == null
                        && transitionReceipt != null) {
                    return replacing(selectedRow, history, readComparisons);
                }
                if (sameIdentity.transitionReceipt() != null
                        && transitionReceipt != null
                        && !sameIdentity.transitionReceipt()
                                .transitionReceiptIdentity().equals(
                                        transitionReceipt
                                                .transitionReceiptIdentity())) {
                    throw new IllegalArgumentException(
                            "Managed epoch receipt has conflicting Contracts "
                                    + "transition evidence");
                }
                return this;
            }
            throw new IllegalArgumentException(
                    "Managed epoch receipt conflicts with durable identity or "
                            + "document epoch");
        }

        DocumentHistory advanced = history == null
                ? DocumentHistory.first(selectedRow)
                : history.appending(selectedRow);
        PersistentOrderedMap.Mutation<DocumentId, DocumentHistory>
                documentMutation = byDocument.put(
                        selected.documentId(), advanced);
        PersistentOrderedMap.Mutation<String, StoredReceipt>
                identityMutation = byIdentity.put(
                        selected.receiptIdentity(), selectedRow);
        int comparisons = Math.addExact(
                readComparisons,
                Math.addExact(
                        advanced.lastMutationComparisons(),
                        Math.addExact(
                                documentMutation.comparisons(),
                                identityMutation.comparisons())));
        int copies = Math.addExact(
                advanced.lastMutationNodeCopies(),
                Math.addExact(
                        documentMutation.copiedNodes(),
                        identityMutation.copiedNodes()));
        return new ManagedEpochReceiptStore(
                documentMutation.map(),
                identityMutation.map(),
                comparisons,
                copies);
    }

    /**
     * Advances only the exact component-representation cursor of one source
     * lineage. No managed epoch receipt is created or replaced.
     */
    ManagedEpochReceiptStore withComponentRepresentationRebind(
            DocumentId documentId,
            long epoch,
            String beforeBlueId,
            String afterBlueId,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        DocumentId document = Objects.requireNonNull(
                documentId, "documentId");
        String before = Objects.requireNonNull(beforeBlueId, "beforeBlueId");
        String after = Objects.requireNonNull(afterBlueId, "afterBlueId");
        ManagedDocumentTransitionReceipt transition = Objects.requireNonNull(
                transitionReceipt, "transitionReceipt");
        if (before.equals(after)
                || !transition.documentId().value().equals(document.value())
                || !transition.beforeBlueId().equals(before)
                || !transition.afterBlueId().equals(after)
                || !transition.emittedRootEvents().isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid component-representation transition for "
                            + document);
        }
        PersistentOrderedMap.ReadResult<DocumentHistory> read = byDocument.read(
                document);
        if (!read.found()) {
            throw new IllegalArgumentException(
                    "Component representation belongs to an absent receipt "
                            + "history " + document);
        }
        DocumentHistory rebound = read.value().rebinding(
                epoch, before, after);
        PersistentOrderedMap.Mutation<DocumentId, DocumentHistory> mutation =
                byDocument.put(document, rebound);
        return new ManagedEpochReceiptStore(
                mutation.map(),
                byIdentity,
                Math.addExact(
                        read.comparisons(),
                        Math.addExact(
                                rebound.lastMutationComparisons(),
                                mutation.comparisons())),
                Math.addExact(
                        rebound.lastMutationNodeCopies(),
                        mutation.copiedNodes()));
    }

    ReceiptRead exact(DocumentId documentId, long epoch) {
        DocumentId document = Objects.requireNonNull(documentId, "documentId");
        PersistentOrderedMap.ReadResult<DocumentHistory> documentRead =
                byDocument.read(document);
        if (!documentRead.found()) {
            return new ReceiptRead(null, documentRead.comparisons(), 0, 0);
        }
        PersistentOrderedMap.ReadResult<ManagedEpochReceipt> epochRead =
                documentRead.value().readPublic(epoch);
        return new ReceiptRead(
                epochRead.value(),
                Math.addExact(
                        documentRead.comparisons(), epochRead.comparisons()),
                epochRead.found() ? 1 : 0,
                0);
    }

    /** Opens one physical epoch row containing public and Contracts evidence. */
    EvidenceRead exactEvidence(DocumentId documentId, long epoch) {
        DocumentId document = Objects.requireNonNull(documentId, "documentId");
        PersistentOrderedMap.ReadResult<DocumentHistory> documentRead =
                byDocument.read(document);
        if (!documentRead.found()) {
            return new EvidenceRead(
                    null, null, documentRead.comparisons(), 0, 0);
        }
        PersistentOrderedMap.ReadResult<StoredReceipt> epochRead =
                documentRead.value().read(epoch);
        StoredReceipt stored = epochRead.value();
        return new EvidenceRead(
                stored == null ? null : stored.publicReceipt(),
                stored == null ? null : stored.transitionReceipt(),
                Math.addExact(
                        documentRead.comparisons(), epochRead.comparisons()),
                epochRead.found() ? 1 : 0,
                0);
    }

    ReceiptRead byIdentity(String receiptIdentity) {
        PersistentOrderedMap.ReadResult<StoredReceipt> rowRead =
                byIdentity.read(Objects.requireNonNull(
                        receiptIdentity, "receiptIdentity"));
        ManagedEpochReceipt receipt = rowRead.found()
                ? rowRead.value().publicReceipt()
                : null;
        return new ReceiptRead(
                receipt,
                rowRead.comparisons(),
                rowRead.found() ? 1 : 0,
                0);
    }

    TransitionReceiptRead exactTransition(
            DocumentId documentId, long epoch) {
        DocumentId document = Objects.requireNonNull(documentId, "documentId");
        PersistentOrderedMap.ReadResult<DocumentHistory> documentRead =
                byDocument.read(document);
        if (!documentRead.found()) {
            return new TransitionReceiptRead(
                    null, documentRead.comparisons(), 0, 0);
        }
        PersistentOrderedMap.ReadResult<StoredReceipt> epochRead =
                documentRead.value().read(epoch);
        ManagedDocumentTransitionReceipt transition = epochRead.found()
                ? epochRead.value().transitionReceipt()
                : null;
        return new TransitionReceiptRead(
                transition,
                Math.addExact(
                        documentRead.comparisons(), epochRead.comparisons()),
                epochRead.found() ? 1 : 0,
                0);
    }

    TransitionReceiptRead transitionByEpochReceiptIdentity(
            String receiptIdentity) {
        PersistentOrderedMap.ReadResult<StoredReceipt> read = byIdentity.read(
                Objects.requireNonNull(receiptIdentity, "receiptIdentity"));
        ManagedDocumentTransitionReceipt transition = read.found()
                ? read.value().transitionReceipt()
                : null;
        return new TransitionReceiptRead(
                transition, read.comparisons(), read.found() ? 1 : 0, 0);
    }

    ReceiptAudit audit(DocumentId documentId) {
        PersistentOrderedMap.ReadResult<DocumentHistory> read = byDocument.read(
                Objects.requireNonNull(documentId, "documentId"));
        List<ManagedEpochReceipt> receipts = read.found()
                ? read.value().receipts()
                : List.of();
        return new ReceiptAudit(receipts, read.comparisons(), receipts.size(), 0);
    }

    long latestEpoch(DocumentId documentId) {
        DocumentHistory history = byDocument.get(Objects.requireNonNull(
                documentId, "documentId"));
        return history == null ? -1L : history.latestEpoch();
    }

    /**
     * Narrow immutable-store seam used only by fail-closed corruption tests.
     * It bypasses cross-model verification while retaining persistent
     * path-copy semantics; production writes always use {@link #withReceipt}.
     */
    ManagedEpochReceiptStore withUnverifiedEvidenceForTesting(
            DocumentId documentId,
            long epoch,
            ManagedEpochReceipt publicReceipt,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        DocumentId document = Objects.requireNonNull(
                documentId, "documentId");
        PersistentOrderedMap.ReadResult<DocumentHistory> historyRead =
                byDocument.read(document);
        if (!historyRead.found()) {
            throw new IllegalArgumentException(
                    "Cannot tamper with absent managed receipt history");
        }
        StoredReceipt prior = historyRead.value().receipt(epoch);
        if (prior == null) {
            throw new IllegalArgumentException(
                    "Cannot tamper with an absent managed receipt epoch");
        }
        if (publicReceipt == null && transitionReceipt != null) {
            throw new IllegalArgumentException(
                    "Contracts evidence requires a public epoch receipt");
        }
        if (publicReceipt != null
                && (!document.equals(publicReceipt.documentId())
                        || epoch != publicReceipt.epoch())) {
            throw new IllegalArgumentException(
                    "Test evidence replacement must retain its storage key");
        }

        StoredReceipt replacement = publicReceipt == null ? null
                : new StoredReceipt(publicReceipt, transitionReceipt);
        DocumentHistory changedHistory = historyRead.value()
                .withUnverifiedEvidenceForTesting(epoch, replacement);
        PersistentOrderedMap.Mutation<DocumentId, DocumentHistory>
                historyMutation = changedHistory == null
                        ? byDocument.remove(document)
                        : byDocument.put(document, changedHistory);
        PersistentOrderedMap.Mutation<String, StoredReceipt> oldIdentity =
                byIdentity.remove(prior.publicReceipt().receiptIdentity());
        PersistentOrderedMap<String, StoredReceipt> changedIdentities =
                oldIdentity.map();
        int identityComparisons = oldIdentity.comparisons();
        int identityCopies = oldIdentity.copiedNodes();
        if (replacement != null) {
            PersistentOrderedMap.Mutation<String, StoredReceipt> inserted =
                    changedIdentities.put(
                            replacement.publicReceipt().receiptIdentity(),
                            replacement);
            changedIdentities = inserted.map();
            identityComparisons = Math.addExact(
                    identityComparisons, inserted.comparisons());
            identityCopies = Math.addExact(
                    identityCopies, inserted.copiedNodes());
        }
        return new ManagedEpochReceiptStore(
                historyMutation.map(),
                changedIdentities,
                Math.addExact(
                        historyRead.comparisons(),
                        Math.addExact(
                                historyMutation.comparisons(),
                                identityComparisons)),
                Math.addExact(
                        historyMutation.copiedNodes(), identityCopies));
    }

    int documentCount() {
        return byDocument.size();
    }

    int receiptCount() {
        return byIdentity.size();
    }

    int lastMutationComparisons() {
        return lastMutationComparisons;
    }

    int lastMutationNodeCopies() {
        return lastMutationNodeCopies;
    }

    void assertStructurallyValid() {
        byDocument.assertStructurallyValid();
        byIdentity.assertStructurallyValid();
        for (DocumentHistory history : byDocument.values()) {
            history.assertStructurallyValid();
        }
    }

    record ReceiptRead(
            ManagedEpochReceipt receipt,
            int indexComparisons,
            int receiptRowsRead,
            int unrelatedDocumentReads) {
        ReceiptRead {
            requireNonNegative(indexComparisons, "indexComparisons");
            requireNonNegative(receiptRowsRead, "receiptRowsRead");
            requireNonNegative(
                    unrelatedDocumentReads, "unrelatedDocumentReads");
        }

        boolean found() {
            return receipt != null;
        }
    }

    record ReceiptAudit(
            List<ManagedEpochReceipt> receipts,
            int indexComparisons,
            int receiptRowsRead,
            int unrelatedDocumentReads) {
        ReceiptAudit {
            receipts = List.copyOf(Objects.requireNonNull(
                    receipts, "receipts"));
            requireNonNegative(indexComparisons, "indexComparisons");
            requireNonNegative(receiptRowsRead, "receiptRowsRead");
            requireNonNegative(
                    unrelatedDocumentReads, "unrelatedDocumentReads");
        }
    }

    record TransitionReceiptRead(
            ManagedDocumentTransitionReceipt transitionReceipt,
            int indexComparisons,
            int receiptRowsRead,
            int unrelatedDocumentReads) {
        TransitionReceiptRead {
            requireNonNegative(indexComparisons, "indexComparisons");
            requireNonNegative(receiptRowsRead, "receiptRowsRead");
            requireNonNegative(
                    unrelatedDocumentReads, "unrelatedDocumentReads");
        }

        boolean found() {
            return transitionReceipt != null;
        }
    }

    record EvidenceRead(
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transitionReceipt,
            int indexComparisons,
            int receiptRowsRead,
            int unrelatedDocumentReads) {
        EvidenceRead {
            requireNonNegative(indexComparisons, "indexComparisons");
            requireNonNegative(receiptRowsRead, "receiptRowsRead");
            requireNonNegative(
                    unrelatedDocumentReads, "unrelatedDocumentReads");
            if (receipt == null && transitionReceipt != null) {
                throw new IllegalArgumentException(
                        "Contracts evidence requires a public epoch receipt");
            }
        }

        boolean found() {
            return receipt != null;
        }
    }

    private ManagedEpochReceiptStore replacing(
            StoredReceipt selected,
            DocumentHistory history,
            int readComparisons) {
        DocumentHistory replaced = history.replacing(selected);
        ManagedEpochReceipt receipt = selected.publicReceipt();
        PersistentOrderedMap.Mutation<DocumentId, DocumentHistory>
                documentMutation = byDocument.put(
                        receipt.documentId(), replaced);
        PersistentOrderedMap.Mutation<String, StoredReceipt> identityMutation =
                byIdentity.put(receipt.receiptIdentity(), selected);
        return new ManagedEpochReceiptStore(
                documentMutation.map(),
                identityMutation.map(),
                Math.addExact(
                        readComparisons,
                        Math.addExact(
                                replaced.lastMutationComparisons(),
                                Math.addExact(
                                        documentMutation.comparisons(),
                                        identityMutation.comparisons()))),
                Math.addExact(
                        replaced.lastMutationNodeCopies(),
                        Math.addExact(
                                documentMutation.copiedNodes(),
                                identityMutation.copiedNodes())));
    }

    private static int requireNonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    private static final class DocumentHistory {
        private final DocumentId documentId;
        private final PersistentOrderedMap<Long, StoredReceipt> receipts;
        private final long latestEpoch;
        private final String currentRepresentationBlueId;
        private final int lastMutationComparisons;
        private final int lastMutationNodeCopies;

        private DocumentHistory(
                DocumentId documentId,
                PersistentOrderedMap<Long, StoredReceipt> receipts,
                long latestEpoch,
                String currentRepresentationBlueId,
                int lastMutationComparisons,
                int lastMutationNodeCopies) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.receipts = Objects.requireNonNull(receipts, "receipts");
            this.latestEpoch = latestEpoch;
            this.currentRepresentationBlueId = Objects.requireNonNull(
                    currentRepresentationBlueId,
                    "currentRepresentationBlueId");
            this.lastMutationComparisons = lastMutationComparisons;
            this.lastMutationNodeCopies = lastMutationNodeCopies;
        }

        static DocumentHistory first(StoredReceipt stored) {
            ManagedEpochReceipt receipt = stored.publicReceipt();
            if (receipt.epoch() != 0L
                    || receipt.kind() != DocumentRevision.Kind.INITIALIZATION) {
                throw new IllegalArgumentException(
                        "A managed receipt history must begin with epoch-zero "
                                + "initialization");
            }
            PersistentOrderedMap.Mutation<Long, StoredReceipt> mutation =
                    PersistentOrderedMap
                            .<Long, StoredReceipt>empty(EPOCH_ORDER)
                            .put(0L, stored);
            return new DocumentHistory(
                    receipt.documentId(),
                    mutation.map(),
                    0L,
                    receipt.afterBlueId(),
                    mutation.comparisons(),
                    mutation.copiedNodes());
        }

        DocumentHistory appending(StoredReceipt stored) {
            ManagedEpochReceipt receipt = stored.publicReceipt();
            if (!documentId.equals(receipt.documentId())) {
                throw new IllegalArgumentException(
                        "Managed receipt changed source document");
            }
            long expectedEpoch = Math.addExact(latestEpoch, 1L);
            if (receipt.epoch() != expectedEpoch) {
                throw new IllegalArgumentException(
                        "Managed receipt epochs must be contiguous: expected "
                                + expectedEpoch + ", actual " + receipt.epoch());
            }
            if (receipt.beforeBlueId().isEmpty()
                    || !currentRepresentationBlueId.equals(
                            receipt.beforeBlueId().get())) {
                throw new IllegalArgumentException(
                    "Managed receipt before BlueId does not continue the "
                                + "current component representation");
            }
            PersistentOrderedMap.Mutation<Long, StoredReceipt> mutation =
                    receipts.put(receipt.epoch(), stored);
            return new DocumentHistory(
                    documentId,
                    mutation.map(),
                    receipt.epoch(),
                    receipt.afterBlueId(),
                    mutation.comparisons(),
                    mutation.copiedNodes());
        }

        DocumentHistory rebinding(
                long epoch,
                String beforeBlueId,
                String afterBlueId) {
            if (epoch != latestEpoch
                    || !currentRepresentationBlueId.equals(beforeBlueId)
                    || beforeBlueId.equals(afterBlueId)) {
                throw new IllegalArgumentException(
                        "Component representation does not continue the exact "
                                + "receipt cursor for " + documentId);
            }
            return new DocumentHistory(
                    documentId,
                    receipts,
                    latestEpoch,
                    afterBlueId,
                    0,
                    0);
        }

        StoredReceipt receipt(long epoch) {
            return receipts.get(epoch);
        }

        PersistentOrderedMap.ReadResult<StoredReceipt> read(long epoch) {
            return receipts.read(epoch);
        }

        PersistentOrderedMap.ReadResult<ManagedEpochReceipt> readPublic(
                long epoch) {
            PersistentOrderedMap.ReadResult<StoredReceipt> read = read(epoch);
            return new PersistentOrderedMap.ReadResult<>(
                    read.found() ? read.value().publicReceipt() : null,
                    read.comparisons());
        }

        List<ManagedEpochReceipt> receipts() {
            return receipts.values().stream()
                    .map(StoredReceipt::publicReceipt)
                    .toList();
        }

        DocumentHistory replacing(StoredReceipt stored) {
            ManagedEpochReceipt receipt = stored.publicReceipt();
            if (!documentId.equals(receipt.documentId())
                    || receipt.epoch() > latestEpoch
                    || receipts.get(receipt.epoch()) == null) {
                throw new IllegalArgumentException(
                        "Cannot enrich an unknown managed epoch receipt");
            }
            PersistentOrderedMap.Mutation<Long, StoredReceipt> mutation =
                    receipts.put(receipt.epoch(), stored);
            return new DocumentHistory(
                    documentId,
                    mutation.map(),
                    latestEpoch,
                    currentRepresentationBlueId,
                    mutation.comparisons(),
                    mutation.copiedNodes());
        }

        DocumentHistory withUnverifiedEvidenceForTesting(
                long epoch, StoredReceipt stored) {
            if (receipts.get(epoch) == null) {
                throw new IllegalArgumentException(
                        "Cannot replace an unknown managed epoch receipt");
            }
            PersistentOrderedMap.Mutation<Long, StoredReceipt> mutation =
                    stored == null
                            ? receipts.remove(epoch)
                            : receipts.put(epoch, stored);
            if (mutation.map().size() == 0) {
                return null;
            }
            List<StoredReceipt> retained = mutation.map().values();
            StoredReceipt latest = retained.get(retained.size() - 1);
            return new DocumentHistory(
                    documentId,
                    mutation.map(),
                    latest.publicReceipt().epoch(),
                    latest.publicReceipt().afterBlueId(),
                    mutation.comparisons(),
                    mutation.copiedNodes());
        }

        long latestEpoch() {
            return latestEpoch;
        }

        int lastMutationComparisons() {
            return lastMutationComparisons;
        }

        int lastMutationNodeCopies() {
            return lastMutationNodeCopies;
        }

        void assertStructurallyValid() {
            receipts.assertStructurallyValid();
        }
    }

    private record StoredReceipt(
            ManagedEpochReceipt publicReceipt,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        private StoredReceipt {
            publicReceipt = Objects.requireNonNull(
                    publicReceipt, "publicReceipt");
        }

        static StoredReceipt verified(
                ManagedEpochReceipt receipt,
                ManagedDocumentTransitionReceipt transition) {
            if (transition == null) {
                return new StoredReceipt(receipt, null);
            }
            if (!receipt.contractsTransitionReceiptIdentity().equals(
                    transition.transitionReceiptIdentity())
                    || !receipt.documentId().value().equals(
                            transition.documentId().value())
                    || !receipt.originalCauseIdentity().equals(
                            transition.originalCauseIdentity())
                    || !receipt.afterBlueId().equals(transition.afterBlueId())
                    || receipt.processingGas() != transition.admittedGas()
                    || (receipt.beforeBlueId().isPresent()
                            && !receipt.beforeBlueId().get().equals(
                                    transition.beforeBlueId()))) {
                throw new IllegalArgumentException(
                        "Contracts transition receipt does not match the "
                                + "managed epoch receipt");
            }
            List<ManagedEventOccurrence> publicEvents = receipt.emittedEvents();
            List<ManagedRootEventOccurrence> contractsEvents =
                    transition.emittedRootEvents();
            if (publicEvents.size() != contractsEvents.size()) {
                throw new IllegalArgumentException(
                        "Contracts transition event count does not match the "
                                + "managed epoch receipt");
            }
            for (int index = 0; index < publicEvents.size(); index++) {
                ManagedEventOccurrence exposed = publicEvents.get(index);
                ManagedRootEventOccurrence exact = contractsEvents.get(index);
                if (exposed.ordinal() != exact.ordinal()
                        || exposed.eventOccurrenceOrdinal()
                                != exact.occurrenceOrdinal()
                        || !exposed.sourceDocumentId().value().equals(
                                exact.sourceDocumentId().value())
                        || !exposed.eventOccurrenceIdentity().equals(
                                exact.occurrenceIdentity())
                        || !exposed.eventBlueId().equals(exact.eventBlueId())
                        || exposed.publicAtSource()
                                != exact.publicAtSource()) {
                    throw new IllegalArgumentException(
                            "Contracts transition event does not match managed "
                                    + "epoch occurrence " + index);
                }
            }
            return new StoredReceipt(receipt, transition);
        }
    }
}
