package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import static blue.coordination.internal.SessionStorageWire.*;

/** Selected publication-map bindings; the caller owns atomic root installation and the shared scopes. */
final class StoredPublicationIndexes implements AutoCloseable {
    private static final long PROOF_WEIGHT = 64L * 1024;
    // Reserve the whole additional memo budget plus its container, using the cache's 8x estimator.
    private static final long PROOF_DEPENDENCY_BYTES = (PROOF_WEIGHT + 256) / 8;
    private record VerifiedPublication(DocumentSessionStorage.WithViews<ContractsClosurePublicationReceipt> decoded,
            ManagedRepresentationVerificationMemo proofs) {
        private VerifiedPublication(DocumentSessionStorage.WithViews<ContractsClosurePublicationReceipt> decoded) {
            this(decoded, new ManagedRepresentationVerificationMemo(64, PROOF_WEIGHT));
        }
    }
    private final StoredPublicationReceiptReuse closureReuse;
    private final StoreIndexCodecs.Binding<String, Boolean> generic;
    private final StoreIndexCodecs.Binding<String, ContractsClosureAdmissionReceipt> admissions;
    private final StoreIndexCodecs.Binding<String, ContractsClosurePublicationReceipt> closures;
    private final StoreIndexCodecs.Binding<String, RootedDeclaredBirthRejection> rejections;
    private final StoredClosureReceiptReferenceCodec closureValues;

    StoredPublicationIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            DocumentSessionStorage sessions, DocumentSessionStorage.OpenScope scope, StoredResultRows resultRows,
            int maximumDepth) {
        this(objects, limits, sessions, scope, resultRows, maximumDepth, null);
    }

    StoredPublicationIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            DocumentSessionStorage sessions, DocumentSessionStorage.OpenScope scope, StoredResultRows resultRows,
            int maximumDepth, RootedStorageCache cache) {
        this(objects, limits, sessions, scope, resultRows, maximumDepth, cache, new StoredPublicationReceiptReuse(), null);
    }

    /** Test seam for owner eviction and actual enclosing canonical encoder calls; never replaces verification. */
    StoredPublicationIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            DocumentSessionStorage sessions, DocumentSessionStorage.OpenScope scope, StoredResultRows resultRows,
            int maximumDepth, RootedStorageCache cache, StoredPublicationReceiptReuse closureReuse,
            Runnable enclosingEncodeObserver) {
        Objects.requireNonNull(sessions); Objects.requireNonNull(scope); Objects.requireNonNull(resultRows);
        this.closureReuse = Objects.requireNonNull(closureReuse);
        var codecs = new StoreIndexCodecs(objects, limits);
        var core = new CoreReceiptStorageCodec(limits.valueBytes(), maximumDepth, cache);
        var receipts = new PublicationReceiptStorageCodec(limits.valueBytes(), maximumDepth, cache);
        String publicationFamily = "blue-coordination/publication-receipt-storage/1/" + limits.valueBytes() + "/" + maximumDepth;
        if (cache != null) closureReuse.bindProcessProofs(receipt -> {
            VerifiedPublication retained = cache.canonicalValue(publicationFamily, receipt);
            return retained == null ? null : retained.proofs();
        });
        generic = codecs.binding("publication/generic", EmbeddingBinding.TEXT_ORDER, codecs.text, codecs.membership);
        admissions = codecs.binding("publication/admission", EmbeddingBinding.TEXT_ORDER, codecs.text,
                codec("admission", value -> {
                    require(value.publicationOutcome() == ContractsClosureAdmissionReceipt.PublicationOutcome.PUBLISHED,
                            "Only newly published admission receipts are durable");
                    resultRows.retain(value.attempt().processResult()); return value;
                }, core::encodeAdmission, core::decodeAdmission));
        closureValues = new StoredClosureReceiptReferenceCodec(objects, limits.valueBytes(),
                codec("closure", value -> {
                    receipts.encodePublication(value, sessions::retainView);
                    if (value.commits()) resultRows.retain(value.attempt().processResult()); return value;
                }, value -> closureReuse.encode(value, selected -> {
                    byte[] canonical = cache == null ? null : cache.canonicalEncoding(publicationFamily, selected);
                    if (canonical != null) return canonical;
                    if (enclosingEncodeObserver != null) enclosingEncodeObserver.run();
                    return receipts.encodePublication(selected, sessions::viewAddress);
                }),
                bytes -> closureReuse.decode(bytes, selected -> {
                    if (cache == null) return receipts.decodePublication(selected, scope);
                    VerifiedPublication decoded = cache.decodeVerifiedCanonical(publicationFamily, selected,
                            frame -> new VerifiedPublication(scope.captureViews(() -> receipts.decodePublication(frame, scope))),
                            value -> value.decoded().value(),
                            value -> Math.addExact(value.decoded().views().encodedBytes(), PROOF_DEPENDENCY_BYTES),
                            value -> scope.acceptViews(value.decoded().views()));
                    return decoded.decoded().value();
                })));
        closures = codecs.binding("publication/closure", EmbeddingBinding.TEXT_ORDER, codecs.text, closureValues);
        rejections = codecs.binding("publication/declared-rejection", EmbeddingBinding.TEXT_ORDER, codecs.text,
                codec("declared-rejection", value -> {
                    receipts.encodeRejection(value, sessions::retainView); return value;
                }, value -> receipts.encodeRejection(value, sessions::viewAddress), bytes -> receipts.decodeRejection(bytes, scope)));
    }

    PersistentOrderedMap<String, Boolean> openGeneric(byte[] root) { return generic.open(root); }
    StoredPublicationReceiptReuse closureReuse() { return closureReuse; }
    @Override public void close() { closureValues.close(); closureReuse.close(); }
    PersistentOrderedMap<String, Boolean> retainGeneric(PersistentOrderedMap<String, Boolean> rows) { return generic.retain(rows); }
    PersistentOrderedMap<String, ContractsClosureAdmissionReceipt> openAdmissions(byte[] root) { return admissions.open(root); }
    PersistentOrderedMap<String, ContractsClosureAdmissionReceipt> retainAdmissions(PersistentOrderedMap<String, ContractsClosureAdmissionReceipt> rows) { return admissions.retain(rows); }
    PersistentOrderedMap<String, ContractsClosurePublicationReceipt> openClosures(byte[] root) { return closures.open(root); }
    PersistentOrderedMap<String, ContractsClosurePublicationReceipt> retainClosures(PersistentOrderedMap<String, ContractsClosurePublicationReceipt> rows) { return closures.retain(rows); }
    PersistentOrderedMap<String, RootedDeclaredBirthRejection> openRejections(byte[] root) { return rejections.open(root); }
    PersistentOrderedMap<String, RootedDeclaredBirthRejection> retainRejections(PersistentOrderedMap<String, RootedDeclaredBirthRejection> rows) { return rejections.retain(rows); }

    /** Selected working-value projection must call this with the actual AVL key and sibling roots. */
    static ContractsClosureAdmissionReceipt checkedAdmission(String key, ContractsClosureAdmissionReceipt row,
            PersistentOrderedMap<String, Boolean> generic, PersistentOrderedMap<String, ContractsClosurePublicationReceipt> closures) {
        require(key.equals(row.publicationIdentity()) && row.publicationOutcome() == ContractsClosureAdmissionReceipt.PublicationOutcome.PUBLISHED,
                "Selected admission key or durable outcome differs");
        require(Boolean.TRUE.equals(generic.get(key)) && !closures.containsKey(key), "Selected admission publication membership differs"); return row;
    }
    static ContractsClosurePublicationReceipt checkedClosure(String key, ContractsClosurePublicationReceipt row,
            PersistentOrderedMap<String, Boolean> generic, PersistentOrderedMap<String, ContractsClosureAdmissionReceipt> admissions) {
        require(key.equals(row.publicationIdentity()), "Selected closure publication key differs");
        require(Boolean.TRUE.equals(generic.get(key)) && !admissions.containsKey(key), "Selected closure publication membership differs"); return row;
    }
    static RootedDeclaredBirthRejection checkedRejection(String key, RootedDeclaredBirthRejection row) {
        require(key.equals(row.terminalKey()), "Selected declared rejection key differs"); return row;
    }

    private static <T> PersistentMapCodec<T> codec(String name, UnaryOperator<T> prepare,
            Function<T, byte[]> encode, Function<byte[], T> decode) {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/publication-index-row/" + name + "/1"; }
            public T prepareForStorage(T value) { return physical(() -> prepare.apply(value)); }
            public byte[] encode(T value) { return encode.apply(value); }
            public T decode(byte[] bytes) { return decode.apply(bytes); }
        };
    }
}
