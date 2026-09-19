package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.processor.ExternalOrderKey;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Source-owned retained positions, addressed before an exclusive logical boundary.
 * A position holds the actual fully committed session image from that publication,
 * not a reconstruction from the current head. Appending a later position does not
 * change an earlier prefix. Equal-boundary applications replace that boundary's
 * complete image, and therefore invalidate readers that include the boundary.
 */
final class StoredHistoricalSources {
    record Boundary(ExternalOrderKey order) implements Comparable<Boundary> {
        @Override public int compareTo(Boundary other) {
            return Comparator.nullsFirst(ExternalOrderKey::compareTo).compare(order, other.order);
        }
    }
    private static final OrderedRecordKey<Boundary> BOUNDARY = new OrderedRecordKey<>() {
        public String identity() { return "blue-coordination/ordered-key/source-boundary/1"; }
        public byte[] encode(Boundary value) {
            if (value.order() == null) return new byte[] {0};
            byte[] order = OrderedRecordKey.externalOrder().encode(value.order());
            byte[] result = new byte[order.length + 1]; result[0] = 1;
            System.arraycopy(order, 0, result, 1, order.length); return result;
        }
        public Boundary decode(byte[] bytes) {
            if (Arrays.equals(bytes, new byte[] {0})) return new Boundary(null);
            require(bytes.length > 1 && bytes[0] == 1, "Invalid source history boundary");
            return new Boundary(OrderedRecordKey.externalOrder().decode(Arrays.copyOfRange(bytes, 1, bytes.length)));
        }
    };
    private final LogicalRecordContext context;
    private final DocumentSessionStorage.OpenScope views;
    private PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> admissions;
    private PersistentOrderedMap<DocumentId, PersistentOrderedMap<Boundary, StoreIndexCodecs.SessionAddress>> histories;
    private PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, Boolean>> identities;
    private PersistentOrderedMap<String, PersistentOrderedMap<ManagedLineageIndex.RetainedKey, ManagedLineageIndex.RetainedState>> positions;
    private Map<DocumentId, NavigableMap<Boundary, DocumentSession>> pending = Map.of();
    private List<DocumentSession> publications = List.of();

    StoredHistoricalSources(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            LogicalRecordContext context, DocumentSessionStorage.OpenScope views) {
        this.context = context; this.views = views;
        var c = new StoreIndexCodecs(objects, limits); var scope = LogicalRecordContext.runtimeScope();
        admissions = c.binding("source/admission", EmbeddingBinding.DOCUMENT_ORDER, c.documents, c.sessions)
                .openLogical(context, Family.SOURCE_ADMISSION, scope, OrderedRecordKey.document());
        histories = c.binding("source/history", Boundary::compareTo, BOUNDARY, c.sessions)
                .openLogicalBuckets(context, Family.SOURCE_HISTORY, scope, EmbeddingBinding.DOCUMENT_ORDER,
                        OrderedRecordKey.document(), BOUNDARY);
        identities = c.binding("source/identity", EmbeddingBinding.DOCUMENT_ORDER, c.documents, c.membership)
                .openLogicalBuckets(context, Family.SOURCE_IDENTITY, scope, EmbeddingBinding.TEXT_ORDER,
                        OrderedRecordKey.text(), OrderedRecordKey.document());
        var key = OrderedRecordKey.pair("source-position", OrderedRecordKey.document(), OrderedRecordKey.signedLong(),
                ManagedLineageIndex.RetainedKey::documentId, ManagedLineageIndex.RetainedKey::epoch, ManagedLineageIndex.RetainedKey::new);
        positions = c.binding("source/position", ManagedLineageIndex.RETAINED_ORDER, c.retainedKeys, c.retainedStates)
                .openLogicalBuckets(context, Family.SOURCE_POSITION, scope, EmbeddingBinding.TEXT_ORDER,
                        OrderedRecordKey.text(), key);
    }

    /** Called only after the complete atomic replacement is validated, before its nonthrowing state swap. */
    void published(Set<DocumentId> owners, InMemoryDocumentStore.StoreState replacement) {
        var next = new LinkedHashMap<>(pending);
        var appended = new ArrayList<>(publications);
        for (var id : owners) {
            var session = Objects.requireNonNull(replacement.sessionIndex().get(id)).copyForAtomicPublication();
            require(session.rootedView() != null, "Historical source position has no rooted publication");
            session.rootedView().requirePublishedHead(id, session.epoch(), session.currentRepresentation().blueId());
            var rows = new TreeMap<Boundary, DocumentSession>(); rows.putAll(next.getOrDefault(id, Collections.emptyNavigableMap()));
            rows.put(boundary(session), session); next.put(id, Collections.unmodifiableNavigableMap(rows));
            appended.add(session);
        }
        var nextPending = Map.copyOf(next); var nextPublications = List.copyOf(appended);
        pending = nextPending; publications = nextPublications;
    }

    Optional<DocumentSession> admission(DocumentId id) {
        var row = admissions.get(id);
        if (row != null) return Optional.of(open(id, row));
        var local = pending.get(id);
        return local == null ? Optional.empty() : Optional.of(local.firstEntry().getValue());
    }

    Optional<DocumentSession> before(DocumentId id, ExternalOrderKey cutoff) {
        Objects.requireNonNull(cutoff); var bound = new Boundary(cutoff);
        Map.Entry<Boundary, StoreIndexCodecs.SessionAddress> last = null;
        var rows = histories.get(id).range(null, bound);
        while (rows.hasNext()) last = rows.next();
        var local = pending.getOrDefault(id, Collections.emptyNavigableMap()).lowerEntry(bound);
        if (local != null && (last == null || local.getKey().compareTo(last.getKey()) >= 0)) return Optional.of(local.getValue());
        if (last == null) return Optional.empty();
        var session = open(id, last.getValue());
        require(last.getKey().equals(boundary(session)), "Source position key differs from its exact retained image");
        return Optional.of(session);
    }

    private DocumentSession open(DocumentId id, StoreIndexCodecs.SessionAddress address) {
        require(address.documentId().equals(id), "Historical source address has another owner");
        return views.open(id, address.address());
    }
    private static Boundary boundary(DocumentSession session) {
        var positions = session.indexedState().rootedViewPositions();
        require(!positions.isEmpty(), "Historical source has no retained position");
        return new Boundary(positions.get(positions.size() - 1).boundary());
    }

    /** All historical records accompany the same final publication as the corresponding live state. */
    void stage() {
        try (var retention = views.openRetentionStage()) {
            for (var session : publications) {
                    var id = session.documentId(); var history = histories.get(id);
                    var address = new StoreIndexCodecs.SessionAddress(id, retention.retain(session));
                    if (admissions.get(id) == null) admissions = admissions.put(id, address).map();
                    history = history.put(boundary(session), address).map();
                    register(session.authoredInitialBlueId(), id); register(session.currentRepresentation().blueId(), id);
                    var revision = session.currentRevision(); var exact = revision.after().blueId();
                    register(exact, id);
                    var key = new ManagedLineageIndex.RetainedKey(id, revision.epoch());
                    var row = new ManagedLineageIndex.RetainedState(id, revision.epoch(), exact);
                    var bucket = positions.get(exact); var prior = bucket.get(key);
                    require(prior == null || prior.equals(row), "Immutable source epoch position changed");
                    positions = positions.put(exact, bucket.put(key, row).map()).map();
                    histories = histories.put(id, history).map();
            }
        }
        admissions.selectLogicalRecords(); histories.selectLogicalRecords(); identities.selectLogicalRecords(); positions.selectLogicalRecords();
    }

    private void register(String blueId, DocumentId id) {
        identities = identities.put(blueId, identities.get(blueId).put(id, true).map()).map();
    }

    /** Read-only resolution index. Current owners still use their ordinary live authority. */
    ManagedLineageIndex lineages(ExternalOrderKey cutoff, ManagedLineageIndex live, Set<DocumentId> owners) {
        Function<DocumentId, ManagedLineageIndex.Lineage> lineage = id -> owners.contains(id) ? live.byDocumentId(id)
                : before(id, cutoff).map(ManagedLineageIndex.Lineage::from).orElse(null);
        Function<String, List<ManagedLineageIndex.Lineage>> candidates = blueId -> {
            var ids = new TreeSet<DocumentId>(EmbeddingBinding.DOCUMENT_ORDER); ids.addAll(identities.get(blueId).keys());
            for (var session : publications)
                if (session.authoredInitialBlueId().equals(blueId) || session.currentRepresentation().blueId().equals(blueId)
                        || session.currentRevision().after().blueId().equals(blueId)) ids.add(session.documentId());
            return ids.stream().map(lineage).filter(Objects::nonNull).toList();
        };
        var documents = lookup(EmbeddingBinding.DOCUMENT_ORDER, lineage);
        var authored = lineageBuckets(candidates, row -> row.authoredInitialBlueId());
        var initialized = lineageBuckets(candidates, row -> row.initializedBlueId());
        var current = lineageBuckets(candidates, row -> row.currentBlueId());
        var retained = lookup(EmbeddingBinding.TEXT_ORDER, blueId -> {
            var result = PersistentOrderedMap.<ManagedLineageIndex.RetainedKey, ManagedLineageIndex.RetainedState>empty(ManagedLineageIndex.RETAINED_ORDER);
            for (var row : candidates.apply(blueId)) {
                var from = new ManagedLineageIndex.RetainedKey(row.documentId(), 0);
                var to = new ManagedLineageIndex.RetainedKey(row.documentId(), Math.addExact(row.currentEpoch(), 1));
                var found = positions.get(blueId).range(from, to);
                while (found.hasNext()) {
                    var match = found.next();
                    require(blueId.equals(row.retainedBlueIdAt(match.getKey().epoch())), "Historical exact-state locator differs from retained history");
                    result = result.put(match.getKey(), match.getValue()).map();
                }
                for (var session : publications) {
                    if (!session.documentId().equals(row.documentId()) || boundary(session).compareTo(new Boundary(cutoff)) >= 0) continue;
                    var revision = session.currentRevision();
                    if (revision.after().blueId().equals(blueId)) {
                        var key = new ManagedLineageIndex.RetainedKey(row.documentId(), revision.epoch());
                        result = result.put(key, new ManagedLineageIndex.RetainedState(row.documentId(), revision.epoch(), blueId)).map();
                    }
                }
            }
            return result;
        });
        return ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(documents, authored, initialized, retained, current, 0));
    }

    private PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, ManagedLineageIndex.Lineage>> lineageBuckets(
            Function<String, List<ManagedLineageIndex.Lineage>> candidates, Function<ManagedLineageIndex.Lineage, String> identity) {
        return lookup(EmbeddingBinding.TEXT_ORDER, blueId -> {
            var result = PersistentOrderedMap.<DocumentId, ManagedLineageIndex.Lineage>empty(EmbeddingBinding.DOCUMENT_ORDER);
            for (var row : candidates.apply(blueId)) if (blueId.equals(identity.apply(row))) result = result.put(row.documentId(), row).map();
            return result;
        });
    }
    private <K, V> PersistentOrderedMap<K, V> lookup(Comparator<? super K> order, Function<K, V> get) {
        return PersistentOrderedMap.logical(order, LogicalRecordMap.virtual(order, context, new LogicalRecordMap.Source<K, V>() {
            public V get(K key) { return get.apply(key); }
            public boolean contains(K key) { return get(key) != null; }
            public Map.Entry<K, V> first(K lower, boolean exclusive, K upper) { throw new IllegalStateException("Historical resolution requires an exact lookup"); }
            public List<Map.Entry<K, V>> entries() { throw new IllegalStateException("Historical resolution cannot enumerate the source registry"); }
        }, (key, value) -> { throw new IllegalStateException("Historical resolution is read-only"); }, Objects::nonNull));
    }
}
