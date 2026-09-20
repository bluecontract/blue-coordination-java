package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Caller-pinned receipt indexes. No complete engine installation or publication occurs here. */
final class StoredManagedEpochIndexes {
    enum Root { DOCUMENT, IDENTITY }
    private final PersistentMapStorage.Limits limits;
    private final CoordinationImmutableObjectStore objectsForLogical;
    private final SessionRecordCodec rows;
    private final ClosureExecutionEvidenceStorageCodec execution;
    private final PersistentMapCodec<ManagedEpochReceiptStore.StoredReceipt> receipts;
    private final StoreIndexCodecs.Binding<Long, ManagedEpochReceiptStore.StoredReceipt> epochs;
    private final StoreIndexCodecs.Binding<DocumentId, ManagedEpochReceiptStore.DocumentHistory> documents;
    private final StoreIndexCodecs.Binding<String, ManagedEpochReceiptStore.StoredReceipt> identities;

    StoredManagedEpochIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        this(objects, limits, null);
    }

    StoredManagedEpochIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            RootedStorageCache cache) {
        this.limits = limits; this.objectsForLogical = objects;
        rows = new SessionRecordCodec(limits.valueBytes(), 128);
        execution = new ClosureExecutionEvidenceStorageCodec(limits.valueBytes(), 128,
                new StoredClosureResultCodec(limits.valueBytes(), 128, cache).configured());
        var codecs = new StoreIndexCodecs(objects, limits);
        receipts = receiptCodec((w, value) -> {
            rows.receipt(w, value.publicReceipt());
            optional(w, value.transitionReceipt(), (x, receipt) -> x.bytes(execution.encodeTransitionReceipt(receipt)));
        }, r -> ManagedEpochReceiptStore.StoredReceipt.verified(rows.receipt(r),
                optional(r, x -> execution.decodeTransitionReceipt(x.bytes(limits.valueBytes())))), cache);
        epochs = codecs.binding("managed-receipts/epochs", Long::compare, codecs.generations, receipts);
        PersistentMapCodec<ManagedEpochReceiptStore.DocumentHistory> histories = new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/receipt-history/1"; }
            public ManagedEpochReceiptStore.DocumentHistory prepareForStorage(ManagedEpochReceiptStore.DocumentHistory history) {
                var s = history.storedState();
                return ManagedEpochReceiptStore.DocumentHistory.restoreStored(new ManagedEpochReceiptStore.DocumentHistory.StoredState(
                        s.document(), epochs.retain(s.receipts()), s.latestEpoch(), s.currentRepresentation(), s.comparisons(), s.copiedNodes()));
            }
            public byte[] encode(ManagedEpochReceiptStore.DocumentHistory history) {
                var s = history.storedState();
                return SessionStorageWire.encode(limits.valueBytes(), w -> {
                    w.text(s.document().value()); w.bytes(s.receipts().storedRootDescriptor());
                    w.longValue(s.latestEpoch()); w.text(s.currentRepresentation()); w.integer(s.comparisons()); w.integer(s.copiedNodes());
                });
            }
            public ManagedEpochReceiptStore.DocumentHistory decode(byte[] bytes) {
                var state = SessionStorageWire.decode(bytes, limits.valueBytes(), r ->
                        new ManagedEpochReceiptStore.DocumentHistory.StoredState(DocumentId.of(text(r)),
                                epochs.open(r.bytes(limits.descriptorBytes())), r.longValue(), text(r), r.integer(), r.integer()));
                require(state.latestEpoch() >= 0 && (long) state.receipts().size() == state.latestEpoch() + 1,
                        "Receipt history size does not cover the retained epoch range");
                blue.language.identity.BlueIds.requireBlueIdOrCyclicMember(state.currentRepresentation(), "currentRepresentation");
                var first = state.receipts().get(0L); var last = state.receipts().get(state.latestEpoch());
                require(first != null && first.publicReceipt().kind() == DocumentRevision.Kind.INITIALIZATION
                        && first.publicReceipt().epoch() == 0 && first.publicReceipt().documentId().equals(state.document()),
                        "Receipt history has no exact initialization");
                require(last != null && last.publicReceipt().epoch() == state.latestEpoch()
                        && last.publicReceipt().documentId().equals(state.document()), "Receipt history has another final owner/epoch");
                return ManagedEpochReceiptStore.DocumentHistory.restoreStored(state);
            }
        };
        documents = codecs.binding("managed-receipts/documents", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, histories);
        identities = codecs.binding("managed-receipts/identities", EmbeddingBinding.TEXT_ORDER, codecs.text, receipts);
    }

    ManagedEpochReceiptStore openLogical(LogicalRecordContext context) {
        return ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(
                new LogicalReceiptHistories(context, epochs, objectsForLogical, limits).open(),
                identities.openLogical(context, Family.RECEIPT_IDENTITY, LogicalRecordContext.runtimeScope(), OrderedRecordKey.text()), 0, 0));
    }
    void selectLogical(ManagedEpochReceiptStore store) {
        store.storedState().documents().selectLogicalRecords(); store.storedState().identities().selectLogicalRecords();
    }

    ManagedEpochReceiptStore retainPartition(ManagedEpochReceiptStore store) {
        return physical(() -> {
            var s = store.storedState();
            return ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(documents.retain(s.documents()),
                    identities.retain(s.identities()), s.comparisons(), s.copiedNodes()));
        });
    }

    ManagedEpochReceiptStore open(Function<Root, byte[]> selectedRoots, int comparisons, int copiedNodes) {
        return physical(() -> ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(
                documents.open(selectedRoots.apply(Root.DOCUMENT)), identities.open(selectedRoots.apply(Root.IDENTITY)), comparisons, copiedNodes)));
    }

    byte[] root(ManagedEpochReceiptStore store, Root root) {
        return root == Root.DOCUMENT ? store.storedState().documents().storedRootDescriptor()
                : store.storedState().identities().storedRootDescriptor();
    }

    /** Validate both selected indexes, without enumerating other documents or receipts. */
    ManagedEpochReceiptStore.EvidenceRead exact(ManagedEpochReceiptStore store, DocumentId document, long epoch) {
        return physical(() -> {
            var history = store.storedState().documents().get(document);
            if (history != null) require(document.equals(history.documentId()), "Receipt document index has another owner");
            var selected = store.exactEvidence(document, epoch);
            if (!selected.found()) {
                require(history == null || epoch < 0 || epoch > history.latestEpoch(), "Missing in-range retained receipt");
                return selected;
            }
            require(selected.receipt().documentId().equals(document) && selected.receipt().epoch() == epoch, "Selected receipt key differs");
            var identity = store.storedState().identities().get(selected.receipt().receiptIdentity());
            require(identity != null, "Selected receipt identity index is missing");
            // StoredReceipt has exactly these two immutable fields. Sharing both
            // originals is complete equality; a matching logical receipt ID is not.
            if (identity.publicReceipt() != selected.receipt() || identity.transitionReceipt() != selected.transitionReceipt()) {
                var actual = ManagedEpochReceiptStore.StoredReceipt.verified(selected.receipt(), selected.transitionReceipt());
                require(Arrays.equals(receipts.encode(identity), receipts.encode(actual)), "Receipt indexes select different complete evidence");
            }
            return selected;
        });
    }

    /** Only complete immutable receipts are cached; history maps and selected-index checks are not. */
    private PersistentMapCodec<ManagedEpochReceiptStore.StoredReceipt> receiptCodec(
            BiConsumer<Writer, ManagedEpochReceiptStore.StoredReceipt> writer,
            Function<Reader, ManagedEpochReceiptStore.StoredReceipt> reader, RootedStorageCache cache) {
        PersistentMapCodec<ManagedEpochReceiptStore.StoredReceipt> raw = new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/managed-epoch-index/receipt/2"; }
            public byte[] encode(ManagedEpochReceiptStore.StoredReceipt value) {
                return SessionStorageWire.encode(limits.valueBytes(), w -> writer.accept(w, value));
            }
            public ManagedEpochReceiptStore.StoredReceipt decode(byte[] bytes) {
                return SessionStorageWire.decode(bytes, limits.valueBytes(), reader);
            }
        };
        var canonical = new CanonicalStorageCodec<>(raw, cache, limits.valueBytes(), 128);
        if (cache == null) return canonical;
        String family = raw.identity() + "/" + limits.valueBytes() + "/128/";
        return new PersistentMapCodec<>() {
            public String identity() { return raw.identity(); }
            public ManagedEpochReceiptStore.StoredReceipt decode(byte[] bytes) { return canonical.decode(bytes); }
            public byte[] encode(ManagedEpochReceiptStore.StoredReceipt value) {
                byte[] known = cache.canonicalEncoding(family, value);
                if (known != null) return known;
                byte[] encoded = raw.encode(value);
                if (cache.canRetainEncodedBytes(encoded.length)) {
                    // StoredReceipt has exactly two deeply immutable fields, both fully
                    // encoded here. Preserve the producer only after the ordinary cold
                    // decoder and exact canonical roundtrip have accepted its whole frame.
                    // This is content reuse, not proof that any index currently contains it.
                    cache.decodeVerifiedCanonical(family, encoded, frame -> {
                        var decoded = raw.decode(frame);
                        require(Arrays.equals(frame, raw.encode(decoded)), "Noncanonical produced managed receipt");
                        return value;
                    }, retained -> retained, ignored -> 0L, ignored -> true);
                }
                return encoded;
            }
        };
    }
}
