package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
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
    private final LogicalDocumentInstances instances;
    private final LogicalInstanceHistory instanceHistory;
    private final LogicalPublicationInstances publicationInstances;
    private final LogicalOccurrenceInstances occurrenceInstances;
    private PersistentOrderedMap<DocumentInstanceRef, StoreIndexCodecs.SessionAddress> admissions;
    private PersistentOrderedMap<DocumentInstanceRef, PersistentOrderedMap<Boundary, StoreIndexCodecs.SessionAddress>> histories;
    private PersistentOrderedMap<String, PersistentOrderedMap<DocumentInstanceRef, Boolean>> identities;
    private PersistentOrderedMap<String, PersistentOrderedMap<InstanceSourceKeys.Epoch, ManagedLineageIndex.RetainedState>> positions;
    private Map<DocumentInstanceRef, NavigableMap<Boundary, DocumentSession>> pending = Map.of();
    private record PublicationImage(DocumentInstanceRef instance, DocumentSession session) { }
    private List<PublicationImage> publications = List.of();
    private LogicalExecutionPublications executionPublications;
    private java.util.function.BiFunction<DocumentInstanceRef, Long, InMemoryDocumentStore.ManagedEpochEvidence> receiptReader;
    private final Map<DocumentInstanceRef, ManagedEpochReceiptStore.DocumentHistory> pendingReceipts = new HashMap<>();

    <T> T protectRead(java.util.function.Supplier<T> reader) { return context.protect(reader); }

    void bindReceipts(java.util.function.BiFunction<DocumentInstanceRef, Long, InMemoryDocumentStore.ManagedEpochEvidence> reader) {
        require(receiptReader == null, "Retained receipt reader already bound"); receiptReader = Objects.requireNonNull(reader);
    }
    InMemoryDocumentStore.ManagedEpochEvidence receipt(DocumentInstanceRef ref, long epoch) {
        return context.protect(() -> {
            instances.requireRetained(ref);
            var local = pendingReceipts.get(ref);
            if (local == null) return Objects.requireNonNull(receiptReader).apply(ref, epoch);
            var row = local.receipt(epoch);
            return row == null ? new InMemoryDocumentStore.ManagedEpochEvidence(null, null)
                    : new InMemoryDocumentStore.ManagedEpochEvidence(row.publicReceipt(), row.transitionReceipt());
        });
    }
    DocumentSession latest(DocumentInstanceRef ref) {
        return context.protect(() -> {
            instances.requireRetained(ref);
            var local = pending.get(ref);
            if (local != null && !local.isEmpty()) return local.lastEntry().getValue();
            var rows = histories.get(ref).range(null, null);
            StoreIndexCodecs.SessionAddress last = null;
            while (rows.hasNext()) last = rows.next().getValue();
            return open(ref, Objects.requireNonNull(last, "Retained instance has no source history"));
        });
    }

    void bindExecutionPublications(StoredPublicationIndexes publication) {
        require(executionPublications == null, "Execution publications already bound");
        executionPublications = new LogicalExecutionPublications(context, instances, publicationInstances, publication);
    }

    StoredHistoricalSources(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            LogicalRecordContext context, DocumentSessionStorage.OpenScope views) {
        this.context = context; this.views = views;
        instances = context.instances(limits.valueBytes());
        instanceHistory = new LogicalInstanceHistory(context, instances, views, limits.valueBytes());
        publicationInstances = new LogicalPublicationInstances(context, instances, instanceHistory, views, limits.valueBytes());
        occurrenceInstances = new LogicalOccurrenceInstances(context, instances, limits.valueBytes());
        var c = new StoreIndexCodecs(objects, limits); var scope = LogicalRecordContext.runtimeScope();
        admissions = c.binding("source/admission/instances", InstanceSourceKeys.ORDER, InstanceSourceKeys.INSTANCE, c.sessions)
                .openLogical(context, Family.SOURCE_ADMISSION, scope, InstanceSourceKeys.INSTANCE);
        histories = c.binding("source/history/instances", Boundary::compareTo, BOUNDARY, c.sessions)
                .openLogicalBuckets(context, Family.SOURCE_HISTORY, scope, InstanceSourceKeys.ORDER,
                        InstanceSourceKeys.INSTANCE, BOUNDARY);
        identities = c.binding("source/identity/instances", InstanceSourceKeys.ORDER, InstanceSourceKeys.INSTANCE, c.membership)
                .openLogicalBuckets(context, Family.SOURCE_IDENTITY, scope, EmbeddingBinding.TEXT_ORDER,
                        OrderedRecordKey.text(), InstanceSourceKeys.INSTANCE);
        positions = c.binding("source/position/instances", InstanceSourceKeys.EPOCH_ORDER, InstanceSourceKeys.EPOCH, c.retainedStates)
                .openLogicalBuckets(context, Family.SOURCE_POSITION, scope, EmbeddingBinding.TEXT_ORDER,
                        OrderedRecordKey.text(), InstanceSourceKeys.EPOCH);
    }

    /** Called only after the complete atomic replacement is validated, before its nonthrowing state swap. */
    void published(Set<DocumentId> owners, InMemoryDocumentStore.StoreState replacement) {
        var next = new LinkedHashMap<>(pending);
        var appended = new ArrayList<>(publications);
        owners.forEach(instances::requireOrCreateInitial);
        for (var id : owners) {
            var ref = instances.requireOrCreateInitial(id);
            var session = Objects.requireNonNull(replacement.sessionIndex().get(id)).copyForAtomicPublication();
            require(session.rootedView() != null, "Historical source position has no rooted publication");
            session.rootedView().requirePublishedHead(id, session.epoch(), session.currentRepresentation().blueId());
            var rows = new TreeMap<Boundary, DocumentSession>(); rows.putAll(next.getOrDefault(ref, Collections.emptyNavigableMap()));
            rows.put(boundary(session), session); next.put(ref, Collections.unmodifiableNavigableMap(rows));
            occurrenceInstances.capture(ref, session);
            pendingReceipts.put(ref, replacement.managedEpochReceipts().storedState().documents().get(id));
            appended.add(new PublicationImage(ref, session));
        }
        var nextPending = Map.copyOf(next); var nextPublications = List.copyOf(appended);
        pending = nextPending; publications = nextPublications;
    }

    Optional<DocumentSession> admission(DocumentId id) {
        return instances.select(id).instance().flatMap(this::admission);
    }

    Optional<DocumentSession> admission(DocumentInstanceRef ref) {
        return context.protect(() -> {
            instances.requireRetained(ref);
            var row = admissions.get(ref);
            if (row != null) return Optional.of(open(ref, row));
            var local = pending.get(ref);
            return local == null ? Optional.empty() : Optional.of(local.firstEntry().getValue());
        });
    }

    Optional<DocumentSession> before(DocumentId id, ExternalOrderKey cutoff) {
        return instances.select(id).instance().flatMap(ref -> before(ref, cutoff));
    }

    Optional<DocumentSession> before(DocumentInstanceRef ref, ExternalOrderKey cutoff) {
        return context.protect(() -> {
            instances.requireRetained(ref);
            Objects.requireNonNull(cutoff); var bound = new Boundary(cutoff);
            Map.Entry<Boundary, StoreIndexCodecs.SessionAddress> last = null;
            var rows = histories.get(ref).range(null, bound);
            while (rows.hasNext()) last = rows.next();
            var local = pending.getOrDefault(ref, Collections.emptyNavigableMap()).lowerEntry(bound);
            if (local != null && (last == null || local.getKey().compareTo(last.getKey()) >= 0)) return Optional.of(local.getValue());
            if (last == null) return Optional.empty();
            var session = open(ref, last.getValue());
            require(last.getKey().equals(boundary(session)), "Source position key differs from its exact retained image");
            return Optional.of(session);
        });
    }

    boolean targetsRetiredOtherInstance(DocumentId observer, blue.language.processor.closure.ManagedOccurrenceBinding row,
            DocumentInstanceRef retiring) {
        return context.protect(() -> {
            var observerInstance = instances.select(observer).instance().orElseThrow();
            var target = occurrenceInstances.target(observerInstance, row);
            require(target.known(), "Incoming occurrence has no original instance association");
            return target.instance().filter(ref -> !ref.equals(retiring) && instances.retired(ref)).isPresent();
        });
    }

    Optional<DocumentInstanceRef> sourceInstance(DocumentId target, Collection<DocumentSession> observers) {
        return context.protect(() -> {
            var selected = new LinkedHashSet<Optional<DocumentInstanceRef>>();
            for (var observer : observers) {
                var ref = instances.requireOrCreateInitial(observer.documentId());
                if (observer.documentId().equals(target)) selected.add(Optional.of(ref));
                for (var occurrence : observer.rootedView().snapshot().occurrences()) {
                    if (!occurrence.targetDocumentId().value().equals(target.value())) continue;
                    var association = occurrenceInstances.target(ref, occurrence);
                    require(association.known(), "Retained occurrence has no instance association: " + occurrence.occurrenceIdentity());
                    selected.add(association.instance());
                }
            }
            if (selected.size() > 1) throw new ContractsClosureAdapter.ProjectionUnavailableException(
                    "INCOMPATIBLE_RETAINED_INSTANCE_ROLES target=" + target + " roles=" + selected);
            if (!selected.isEmpty()) return selected.iterator().next();
            var current = instances.select(target).instance();
            if (current.isPresent() && !current.get().equals(LogicalDocumentInstances.initialReference(target)))
                throw new ContractsClosureAdapter.ProjectionUnavailableException(
                        "EXPLICIT_REPLACEMENT_TARGET_SELECTION_REQUIRED target=" + current.get());
            return current;
        });
    }

    /** Resolves a forward role without consulting the current source or target binding. */
    Optional<DocumentSession> beforeFrom(DocumentId target, DocumentInstanceRef observer,
            DocumentSession retained, ExternalOrderKey cutoff) {
        return context.protect(() -> {
            instances.requireRetained(observer);
            require(observer.documentId().equals(retained.documentId()), "Retained observer differs from its instance");
            var selected = new LinkedHashSet<Optional<DocumentInstanceRef>>();
            for (var occurrence : retained.rootedView().snapshot().occurrences()) {
                if (!occurrence.targetDocumentId().value().equals(target.value())) continue;
                var association = occurrenceInstances.target(observer, occurrence);
                require(association.known(), "Retained occurrence has no instance association: " + occurrence.occurrenceIdentity());
                selected.add(association.instance());
            }
            require(!selected.isEmpty(), "Missing retained forward instance role: " + target);
            if (selected.size() != 1) throw new ContractsClosureAdapter.ProjectionUnavailableException(
                    "INCOMPATIBLE_RETAINED_INSTANCE_ROLES target=" + target + " roles=" + selected);
            return selected.iterator().next().flatMap(ref -> before(ref, cutoff));
        });
    }

    Optional<String> sourceWorkBlock(DocumentId target, Collection<DocumentSession> observers) {
        var selected = sourceInstance(target, observers);
        if (selected.isEmpty()) return Optional.empty();
        var active = instances.select(target);
        if (active.instance().equals(selected)) return Optional.empty();
        return Optional.of("RETIRED_SOURCE_INSTANCE_WORK_REQUIRED source=" + selected.orElseThrow()
                + " active=" + active.instance() + " bindingGeneration=" + active.generation());
    }

    private DocumentSession open(DocumentInstanceRef ref, StoreIndexCodecs.SessionAddress address) {
        require(address.documentId().equals(ref.documentId()), "Historical source address has another owner");
        var selected = views.open(ref.documentId(), address.address());
        return instanceHistory.open(instanceHistory.position(ref, selected));
    }
    private static Boundary boundary(DocumentSession session) {
        var positions = session.indexedState().rootedViewPositions();
        require(!positions.isEmpty(), "Historical source has no retained position");
        return new Boundary(positions.get(positions.size() - 1).boundary());
    }

    blue.coordination.api.DocumentInstancePosition instancePosition(DocumentSession session) {
        return instanceHistory.position(instances.requireOrCreateInitial(session.documentId()), session);
    }

    DocumentSession retainedInstance(blue.coordination.api.DocumentInstancePosition position) {
        return instanceHistory.open(position);
    }

    void retainPublication(Family kind, String identity, List<DocumentId> owners,
            PersistentOrderedMap<DocumentId, DocumentSession> sessions) {
        publicationInstances.retain(kind, identity, owners, sessions);
    }

    Map<DocumentId, DocumentSession> publicationSessions(Family kind, String identity, List<DocumentId> owners) {
        return publicationInstances.open(kind, identity, owners);
    }

    <T> Optional<T> retiredOriginalAdmission(String identity, List<DocumentId> documents, DocumentId document,
            Function<DocumentSession, T> projection) {
        return context.protect(() -> publicationInstances.retiredOriginalAdmission(identity, documents, document).map(projection));
    }

    void requireGlobalDrainSupported() { context.protect(() -> { instances.requireGlobalDrainSupported(); return null; }); }
    DocumentInstanceRef activeInstance(DocumentId owner) { return context.protect(() -> instances.requireOrCreateInitial(owner)); }
    void requireRetainedInstance(DocumentInstanceRef ref) { context.protect(() -> { instances.requireRetained(ref); return null; }); }

    boolean hasExecution(DocumentId observer, String identity) { return executionPublications.contains(observer, identity); }
    Optional<ContractsClosurePublicationReceipt> executionReceipt(DocumentId observer, String identity) {
        return executionPublications.get(observer, identity);
    }
    List<blue.coordination.api.DocumentRevision> executionCausal(DocumentInstanceRef observer, String identity, DocumentId member, String entry) {
        return executionPublications.causal(observer, identity, member, entry);
    }
    Optional<ContractsClosurePublicationReceipt> executionReceipt(DocumentInstanceRef observer, String identity) { return executionPublications.get(observer, identity); }
    void completedExecution(ContractsClosurePublicationReceipt receipt, InMemoryDocumentStore.StoreState replacement) {
        executionPublications.completed(receipt, replacement);
    }

    List<String> startingLiveRoleBlocks(blue.coordination.api.DocumentInstancePosition basis, DocumentSession session) {
        return occurrenceInstances.startingLiveRoleBlocks(basis.instance(), session);
    }
    void inheritStartingRoles(DocumentInstanceRef next, blue.coordination.api.DocumentInstancePosition basis, DocumentSession session) {
        occurrenceInstances.inheritStartingRoles(next, basis.instance(), session);
    }

    /** All historical records accompany the same final publication as the corresponding live state. */
    void stage() {
        try (var retention = views.openRetentionStage()) {
            for (var publication : publications) {
                    var ref = publication.instance(); var session = publication.session();
                    var id = session.documentId(); var history = histories.get(ref);
                    var address = new StoreIndexCodecs.SessionAddress(id, retention.retain(session));
                    instanceHistory.retain(ref, session, address.address());
                    if (admissions.get(ref) == null) admissions = admissions.put(ref, address).map();
                    history = history.put(boundary(session), address).map();
                    register(session.authoredInitialBlueId(), ref); register(session.currentRepresentation().blueId(), ref);
                    var revision = session.currentRevision(); var exact = revision.after().blueId();
                    register(exact, ref);
                    var key = new InstanceSourceKeys.Epoch(ref, revision.epoch());
                    var row = new ManagedLineageIndex.RetainedState(id, revision.epoch(), exact);
                    var bucket = positions.get(exact); var prior = bucket.get(key);
                    require(prior == null || prior.equals(row), "Immutable source epoch position changed");
                    positions = positions.put(exact, bucket.put(key, row).map()).map();
                    histories = histories.put(ref, history).map();
            }
        }
        admissions.selectLogicalRecords(); histories.selectLogicalRecords(); identities.selectLogicalRecords(); positions.selectLogicalRecords();
        occurrenceInstances.stage();
        if (executionPublications != null) executionPublications.stage();
    }

    private void register(String blueId, DocumentInstanceRef ref) {
        identities = identities.put(blueId, identities.get(blueId).put(ref, true).map()).map();
    }

    /** Read-only resolution index. Current owners still use their ordinary live authority. */
    ManagedLineageIndex lineages(ExternalOrderKey cutoff, ManagedLineageIndex live, Set<DocumentId> owners) {
        return lineages(cutoff, live, owners, id -> instances.select(id).instance());
    }

    ManagedLineageIndex lineages(ExternalOrderKey cutoff, ManagedLineageIndex live, Set<DocumentId> owners,
            Function<DocumentId, Optional<DocumentInstanceRef>> selection) {
        Function<DocumentId, ManagedLineageIndex.Lineage> lineage = id -> owners.contains(id) ? live.byDocumentId(id)
                : selection.apply(id).flatMap(ref -> before(ref, cutoff)).map(ManagedLineageIndex.Lineage::from).orElse(null);
        Function<String, List<ManagedLineageIndex.Lineage>> candidates = blueId -> {
            var ids = new TreeSet<DocumentId>(EmbeddingBinding.DOCUMENT_ORDER);
            for (var ref : identities.get(blueId).keys())
                if (selection.apply(ref.documentId()).filter(ref::equals).isPresent()) ids.add(ref.documentId());
            for (var publication : publications) {
                var session = publication.session();
                if (selection.apply(session.documentId()).filter(publication.instance()::equals).isPresent()
                        && (session.authoredInitialBlueId().equals(blueId) || session.currentRepresentation().blueId().equals(blueId)
                        || session.currentRevision().after().blueId().equals(blueId))) ids.add(session.documentId());
            }
            return ids.stream().map(lineage).filter(Objects::nonNull).toList();
        };
        var documents = lookup(EmbeddingBinding.DOCUMENT_ORDER, lineage);
        var authored = lineageBuckets(candidates, row -> row.authoredInitialBlueId());
        var initialized = lineageBuckets(candidates, row -> row.initializedBlueId());
        var current = lineageBuckets(candidates, row -> row.currentBlueId());
        var retained = lookup(EmbeddingBinding.TEXT_ORDER, blueId -> {
            var result = PersistentOrderedMap.<ManagedLineageIndex.RetainedKey, ManagedLineageIndex.RetainedState>empty(ManagedLineageIndex.RETAINED_ORDER);
            for (var row : candidates.apply(blueId)) {
                var ref = selection.apply(row.documentId()).orElseThrow();
                var from = new InstanceSourceKeys.Epoch(ref, 0);
                var to = new InstanceSourceKeys.Epoch(ref, Math.addExact(row.currentEpoch(), 1));
                var found = positions.get(blueId).range(from, to);
                while (found.hasNext()) {
                    var match = found.next();
                    require(blueId.equals(row.retainedBlueIdAt(match.getKey().epoch())), "Historical exact-state locator differs from retained history");
                    result = result.put(new ManagedLineageIndex.RetainedKey(row.documentId(), match.getKey().epoch()), match.getValue()).map();
                }
                for (var publication : publications) {
                    var session = publication.session();
                    if (!publication.instance().equals(ref) || !session.documentId().equals(row.documentId()) || boundary(session).compareTo(new Boundary(cutoff)) >= 0) continue;
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
