package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.sdk.ExactNodeProvider;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Complete rooted engine assembly from host-selected immutable state. The host
 * owns a coherent named-slot read, the exact journal, publication fences and
 * atomic publication. Retaining bytes here never commits document processing.
 */
public final class RootedEngineStorage {
    private static final String FORMAT = "blue-coordination/rooted-engine-storage/1";

    /**
     * Named storage-assembly bridge for host-owned cache capacity and lifetime.
     * No decoded artifact, verification insertion or implementation type escapes.
     */
    public static final class Cache implements AutoCloseable {
        private final RootedStorageCache delegate;

        /**
         * Creates the initially empty weighted LRU; zero total budget disables retention.
         * @param maximumWeightBytes estimated retained-weight budget, not an exact heap bound
         * @param maximumEntries largest retained artifact count
         * @param maximumEntryWeightBytes largest estimated weight of one retained artifact
         */
        public Cache(long maximumWeightBytes, int maximumEntries, long maximumEntryWeightBytes) {
            delegate = new RootedStorageCache(maximumWeightBytes, maximumEntries, maximumEntryWeightBytes);
        }

        /** Process totals; no keys, content or decoded objects are exposed. */
        public record Statistics(long hits, long misses, long loads, long evictions,
                int retainedEntries, long retainedWeightBytes, long incompatibleHits, long waits, long failedLoads) { }

        /** Returns process totals, not per-owner counters. @return cumulative cache statistics */
        public Statistics statistics() {
            var s = delegate.statistics();
            return new Statistics(s.hits(), s.misses(), s.loads(), s.evictions(), s.retainedEntries(),
                    s.retainedWeightBytes(), s.incompatibleHits(), s.waits(), s.failedLoads());
        }

        /** Clears retained artifacts without changing durable state or active owners. */
        public void clear() { delegate.clear(); }

        /** Retires the cache only; the host separately owns active-operation shutdown. */
        @Override public void close() { delegate.close(); }
    }

    /** Physical capacity controls only; these are not logical gas or protocol limits. */
    public record Limits(int indexNodeBytes, int keyBytes, int valueBytes, int descriptorBytes, int cachedNodes,
            int maximumRecordBytes, int maximumDepth, long maximumScopeBytes,
            int maximumSelectedSessions, int maximumSelectedBuckets, int maximumPendingEntries, long maximumPendingBytes,
            int logChunkBytes, int logValueBytes, int cachedLogChunks) {
        /** Rejects invalid physical capacities before opening any selected state. */
        public Limits {
            new PersistentMapStorage.Limits(indexNodeBytes, keyBytes, valueBytes, descriptorBytes, cachedNodes);
            new DocumentSessionStorage.Limits(maximumRecordBytes, maximumDepth, maximumScopeBytes);
            new PersistentAppendLogStorage.Limits(logChunkBytes, logValueBytes, descriptorBytes, cachedLogChunks);
            if (maximumSelectedSessions < 1 || maximumSelectedBuckets < 1 || maximumPendingEntries < 0 || maximumPendingBytes < 0)
                throw new IllegalArgumentException("Invalid rooted storage scope capacity");
        }
        PersistentMapStorage.Limits indexes() { return new PersistentMapStorage.Limits(indexNodeBytes, keyBytes, valueBytes, descriptorBytes, cachedNodes); }
        DocumentSessionStorage.Limits sessions() { return new DocumentSessionStorage.Limits(maximumRecordBytes, maximumDepth, maximumScopeBytes); }
        StoredInsertionOrderedMap.Limits pending() { return new StoredInsertionOrderedMap.Limits(indexes(), maximumRecordBytes, maximumPendingBytes, maximumPendingEntries); }
        PersistentAppendLogStorage.Limits logs() { return new PersistentAppendLogStorage.Limits(logChunkBytes, logValueBytes, descriptorBytes, cachedLogChunks); }
    }

    /** Independently named selected descriptors; not a root-CAS token or an authority supplied by content. */
    public static final class Selection {
        private final Map<String, byte[]> slots;
        /** Defensively owns the host's coherent selected metadata. @param slots complete selected slots */
        public Selection(Map<String, byte[]> slots) {
            var copy = new LinkedHashMap<String, byte[]>();
            Objects.requireNonNull(slots).forEach((key, value) -> {
                require(key != null && !key.isBlank(), "Blank engine storage slot"); copy.put(key, Objects.requireNonNull(value).clone());
            }); this.slots = Collections.unmodifiableMap(copy);
        }
        /** Returns detached descriptors for host-controlled publication. @return independently named descriptors */
        public Map<String, byte[]> slots() {
            var copy = new LinkedHashMap<String, byte[]>(); slots.forEach((key, value) -> copy.put(key, value.clone()));
            return Collections.unmodifiableMap(copy);
        }
        byte[] required(String name) { var bytes = slots.get(name); require(bytes != null, "Missing engine storage slot " + name); return bytes.clone(); }
    }

    private final CoordinationImmutableObjectStore objects;
    private final Limits limits;
    private final DocumentSessionStorage sessions;
    private final StoredDocumentStore documents;
    private final WholeObjectStorage values;
    private final StoredWholeObjectIndex valueIndexes;
    private final StoredRouteIndexes routes;
    private final StoredActiveSourceIndexes active;
    private final EnginePendingStorage pending;
    private final StoredFeederProgress feeder;
    private final EngineControlStorageCodec control;

    private RootedEngineStorage(CoordinationImmutableObjectStore objects, Limits limits) {
        this(objects, limits, null);
    }

    private RootedEngineStorage(CoordinationImmutableObjectStore objects, Limits limits, RootedStorageCache cache) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        sessions = new DocumentSessionStorage(objects, limits.sessions(), cache);
        documents = new StoredDocumentStore(objects, limits.indexes(), limits.logs(), limits.sessions(), cache);
        values = new WholeObjectStorage(objects, limits.maximumRecordBytes(), limits.maximumDepth(), cache);
        valueIndexes = new StoredWholeObjectIndex(objects, limits.indexes());
        routes = new StoredRouteIndexes(objects, limits.indexes()); active = new StoredActiveSourceIndexes(objects, limits.indexes());
        pending = new EnginePendingStorage(objects, limits.pending(), sessions, limits.maximumDepth(), cache);
        feeder = new StoredFeederProgress(objects, limits.pending(), sessions, limits.maximumDepth(), cache);
        control = new EngineControlStorageCodec(limits.maximumRecordBytes());
    }

    /**
     * Opens all engine families without replay or rebuilding routes from bodies.
     * @param objects immutable physical bytes
     * @param limits explicit physical bounds
     * @param selection complete host-pinned named slots
     * @param provider application content provider
     * @param journal coherent host-owned exact Timeline journal
     * @return one owned runtime scope
     */
    public static Scope open(CoordinationImmutableObjectStore objects, Limits limits, Selection selection,
            ExactNodeProvider provider, TimelineJournalStore journal) {
        return physical(() -> new RootedEngineStorage(objects, limits).new Scope(selection, provider, journal));
    }

    /**
     * Opens a fresh mutable owner using a host-lifetime immutable artifact cache.
     * @param objects selected immutable bytes, still authenticated on each cold-scope read
     * @param limits unchanged physical decoding and scope bounds
     * @param selection current coherent host selection
     * @param provider exact content provider
     * @param journal matching journal
     * @param cache host-owned cache; not closed with the returned owner
     * @return new operation scope, never a reused mutable engine
     */
    public static Scope open(CoordinationImmutableObjectStore objects, Limits limits, Selection selection,
            ExactNodeProvider provider, TimelineJournalStore journal, Cache cache) {
        return physical(() -> new RootedEngineStorage(objects, limits, Objects.requireNonNull(cache).delegate)
                .new Scope(selection, provider, journal));
    }

    /**
     * Explicit one-time resident partition conversion. Cold open never calls this.
     * The caller must own the supplied engine, retain its journal independently,
     * and publish the returned slots coherently before retiring the producer.
     * @param engine original owned resident engine
     * @param objects immutable physical bytes
     * @param limits physical bounds
     * @return uncommitted named descriptor set
     */
    public static Selection retainPartition(DefaultCoordinationEngine engine, CoordinationImmutableObjectStore objects, Limits limits) {
        Objects.requireNonNull(engine);
        synchronized (engine) {
            return physical(() -> {
                require(!engine.objects().hasRetainedBacking(), "Use the owning scope to stage an already storage-backed engine");
                var storage = new RootedEngineStorage(objects, limits);
                var parts = engine.storedParts(WholeObjectBacking.EMPTY);
                var documentSelection = storage.documents.retainPartition(parts.documents());
                var objectSelection = storage.valueIndexes.empty().stage(storage.values.retain(engine.objects().changes()));
                try (var views = storage.sessions.openScope(); var pending = storage.pending.empty(views, work(parts.documents()),
                        id -> parts.documents().admissionReceipts().get(id));
                        var feeder = storage.feeder.empty(views)) {
                    pending.retain(parts.plans(), parts.sources()); feeder.retain(parts.feederMaps());
                    return storage.selection(parts, documentSelection, objectSelection.selection(), pending.snapshot(), feeder.snapshot());
                }
            });
        }
    }

    /** Owned engine plus its bounded lazy identity scopes; retiring it does not publish its state. */
    public final class Scope implements AutoCloseable {
        private final StoredDocumentStore.Opened documentScope;
        private final StoredWholeObjectIndex.Opened initialObjects;
        private final EnginePendingStorage.Scope pendingScope;
        private final StoredFeederProgress.Scope feederScope;
        private DefaultCoordinationEngine engine;
        private boolean closed;

        private Scope(Selection selected, ExactNodeProvider provider, TimelineJournalStore journal) {
            Objects.requireNonNull(selected); Objects.requireNonNull(provider); Objects.requireNonNull(journal);
            require(selected.slots.keySet().equals(requiredSlots()), "Incomplete or foreign engine storage family set");
            var storedControl = control.decode(selected.required("control"));
            initialObjects = valueIndexes.open(new StoredWholeObjectIndex.Selection(selected.required("objects/entries"),
                    selected.required("objects/proofs"), selected.required("objects/members")));
            documentScope = documents.open(documentSelection(selected), limits.maximumSelectedSessions(), limits.maximumSelectedBuckets());
            EnginePendingStorage.Scope acquiredPending = null; StoredFeederProgress.Scope acquiredFeeder = null;
            try {
                var views = documentScope.viewScope();
                acquiredPending = pending.open(pendingSelection(selected), views,
                        id -> work(engine == null ? documentScope.state() : engine.documents().storedState()).apply(id),
                        id -> (engine == null ? documentScope.state() : engine.documents().storedState()).admissionReceipts().get(id));
                acquiredFeeder = feeder.open(feederSelection(selected), views);
                var metrics = new EngineMetrics(); var state = documentScope.state();
                Function<DocumentId, DocumentSession> getSession = id -> state.sessionIndex().get(id);
                var storedRoutes = routes.open(root -> selected.required("routes/" + root),
                        scalar(selected.required("routes/generation")), metrics, getSession,
                        id -> Objects.requireNonNull(getSession.apply(id)).currentRepresentation().blueId());
                var storedActive = active.open(root -> selected.required("active/" + root), metrics);
                engine = DefaultCoordinationEngine.restoreRooted(new DefaultCoordinationEngine.StoredParts(storedControl,
                        values.open(initialObjects), state, storedRoutes.storedIndexes(), storedActive.storedIndexes(),
                        acquiredPending.plans(), acquiredPending.sources(), acquiredFeeder.maps()), provider, journal);
                engine.documents().bindStoredPublicationReuse(documentScope.publicationReuse());
                pendingScope = acquiredPending; feederScope = acquiredFeeder;
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure, acquiredFeeder, acquiredPending, documentScope);
                throw failure;
            }
        }
        /**
         * Actual existing rooted engine, not an adapter implementing another algorithm.
         * A physical failure during an engine operation requires discarding this whole
         * scope; its partially executed mutable state must not subsequently be staged.
         * @return live engine
         */
        public synchronized DefaultCoordinationEngine engine() { ensureOpen(); return engine; }

        /** Prewrites final physical state only. The host must still validate its read set and publish atomically. @return named staged descriptors */
        public synchronized Selection stage() {
            ensureOpen();
            synchronized (engine) {
                return physical(() -> {
                    var parts = engine.storedParts(values.open(initialObjects));
                    var documents = documentScope.stage(parts.documents());
                    var objects = initialObjects.stage(values.retain(engine.objects().changes()));
                    return selection(parts, documents, objects.selection(), pendingScope.snapshot(), feederScope.snapshot());
                });
            }
        }
        private void ensureOpen() { require(!closed, "Rooted engine storage scope is closed"); }
        @Override public synchronized void close() {
            if (!closed) {
                closed = true;
                Throwable failure = closeAfterFailure(null, engine, pendingScope, feederScope, documentScope);
                if (failure instanceof Error error) throw error;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure != null) throw new IllegalStateException("Cannot retire rooted engine scope", failure);
            }
        }
    }

    private static Throwable closeAfterFailure(Throwable original, AutoCloseable... resources) {
        Throwable failure = original;
        for (var resource : resources) {
            if (resource == null) continue;
            try { resource.close(); }
            catch (Exception | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }

    private Selection selection(DefaultCoordinationEngine.StoredParts parts, StoredDocumentStore.Selection documentSelection,
            StoredWholeObjectIndex.Selection objectSelection, EnginePendingStorage.Snapshot pendingSelection,
            StoredFeederProgress.Snapshot feederSelection) {
        var slots = new LinkedHashMap<String, byte[]>(); slots.put("control", control.encode(parts.control()));
        slots.put("documents/metadata", metadata(documentSelection.metadata()));
        for (var root : StoredDocumentStore.Root.values()) slots.put("documents/" + root, documentSelection.root(root));
        slots.put("objects/entries", objectSelection.entries()); slots.put("objects/proofs", objectSelection.proofs()); slots.put("objects/members", objectSelection.members());
        var metrics = new EngineMetrics(); Function<DocumentId, DocumentSession> getSession = id -> parts.documents().sessionIndex().get(id);
        var originalRoutes = OperationRouteIndex.restoreIndexes(parts.routes(), metrics, getSession,
                id -> Objects.requireNonNull(getSession.apply(id)).currentRepresentation().blueId());
        var storedRoutes = routes.retainPartition(originalRoutes, metrics, getSession,
                id -> Objects.requireNonNull(getSession.apply(id)).currentRepresentation().blueId());
        slots.put("routes/generation", scalar(parts.routes().generation()));
        for (var root : StoredRouteIndexes.Root.values()) slots.put("routes/" + root, routes.root(storedRoutes, root));
        var storedActive = active.retainPartition(ContractsActiveSourceTimelineIndex.restoreIndexes(parts.activeSources(), metrics), metrics);
        for (var root : StoredActiveSourceIndexes.Root.values()) slots.put("active/" + root, active.root(storedActive, root));
        pendingSelection.roots().forEach((kind, snapshot) -> slots.put("pending/" + kind, insertion(snapshot)));
        feederSelection.roots().forEach((kind, snapshot) -> slots.put("feeder/" + kind, insertion(snapshot)));
        require(slots.keySet().equals(requiredSlots()), "Incomplete staged engine state"); return new Selection(slots);
    }

    private static Function<String, blue.coordination.api.ManagedEpochApplicationWork> work(InMemoryDocumentStore.StoreState state) {
        return id -> {
            var row = state.catchUpPlans().work(id).work();
            require(row != null && id.equals(row.workIdentity()), "Missing original retained work for source response"); return row;
        };
    }
    private Set<String> requiredSlots() {
        var names = new LinkedHashSet<>(List.of("control", "documents/metadata", "objects/entries", "objects/proofs", "objects/members", "routes/generation"));
        for (var root : StoredDocumentStore.Root.values()) names.add("documents/" + root);
        for (var root : StoredRouteIndexes.Root.values()) names.add("routes/" + root);
        for (var root : StoredActiveSourceIndexes.Root.values()) names.add("active/" + root);
        for (var kind : EnginePendingStorage.Kind.values()) names.add("pending/" + kind);
        for (var kind : StoredFeederProgress.Kind.values()) names.add("feeder/" + kind);
        return names;
    }
    private StoredDocumentStore.Selection documentSelection(Selection selected) {
        var roots = new EnumMap<StoredDocumentStore.Root, byte[]>(StoredDocumentStore.Root.class);
        for (var root : StoredDocumentStore.Root.values()) roots.put(root, selected.required("documents/" + root));
        return new StoredDocumentStore.Selection(roots, metadata(selected.required("documents/metadata")));
    }
    private EnginePendingStorage.Snapshot pendingSelection(Selection selected) {
        var roots = new EnumMap<EnginePendingStorage.Kind, StoredInsertionOrderedMap.Snapshot>(EnginePendingStorage.Kind.class);
        for (var kind : EnginePendingStorage.Kind.values()) roots.put(kind, insertion(selected.required("pending/" + kind)));
        return new EnginePendingStorage.Snapshot(roots);
    }
    private StoredFeederProgress.Snapshot feederSelection(Selection selected) {
        var roots = new EnumMap<StoredFeederProgress.Kind, StoredInsertionOrderedMap.Snapshot>(StoredFeederProgress.Kind.class);
        for (var kind : StoredFeederProgress.Kind.values()) roots.put(kind, insertion(selected.required("feeder/" + kind)));
        return new StoredFeederProgress.Snapshot(roots);
    }
    private byte[] insertion(StoredInsertionOrderedMap.Snapshot value) {
        return encode(limits.maximumRecordBytes(), w -> { w.text(FORMAT + "/insertion"); w.bytes(value.keys()); w.bytes(value.order()); w.longValue(value.nextSequence()); });
    }
    private StoredInsertionOrderedMap.Snapshot insertion(byte[] bytes) {
        return decode(bytes, limits.maximumRecordBytes(), r -> {
            require((FORMAT + "/insertion").equals(r.text(r.remaining())), "Wrong insertion descriptor format");
            return new StoredInsertionOrderedMap.Snapshot(r.bytes(limits.descriptorBytes()), r.bytes(limits.descriptorBytes()), r.longValue());
        });
    }
    private byte[] scalar(long value) { return encode(limits.descriptorBytes(), w -> { w.text(FORMAT + "/scalar"); w.longValue(value); }); }
    private long scalar(byte[] bytes) {
        return decode(bytes, limits.descriptorBytes(), r -> { require((FORMAT + "/scalar").equals(r.text(r.remaining())), "Wrong scalar format"); return r.longValue(); });
    }
    private byte[] metadata(StoredDocumentStore.Metadata m) {
        return encode(limits.descriptorBytes(), w -> {
            w.text(FORMAT + "/document-metadata"); w.longValue(m.occurrenceGeneration()); w.longValue(m.componentGeneration()); w.bool(m.rootedViews());
            w.integer(m.lineageCopies()); w.integer(m.graphComparisons()); w.integer(m.graphCopies());
            w.integer(m.subscriptionComparisons()); w.integer(m.subscriptionCopies()); w.integer(m.subscriptionVisited());
            w.integer(m.receiptComparisons()); w.integer(m.receiptCopies()); w.integer(m.activeBarriers());
            w.integer(m.catchUpComparisons()); w.integer(m.catchUpCopies()); w.integer(m.planComparisons()); w.integer(m.planCopies());
            w.integer(m.workComparisons()); w.integer(m.workCopies());
        });
    }
    private StoredDocumentStore.Metadata metadata(byte[] bytes) {
        return decode(bytes, limits.descriptorBytes(), r -> {
            require((FORMAT + "/document-metadata").equals(r.text(r.remaining())), "Wrong document metadata format");
            return new StoredDocumentStore.Metadata(r.longValue(), r.longValue(), r.bool(), r.integer(), r.integer(), r.integer(),
                    r.integer(), r.integer(), r.integer(), r.integer(), r.integer(), r.integer(), r.integer(), r.integer(),
                    r.integer(), r.integer(), r.integer(), r.integer());
        });
    }
}
