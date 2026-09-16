package blue.coordination.sdk;

import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.coordination.internal.RootedEngineStorage;
import blue.language.processor.NoncommittingExecutionException;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Complete selected rooted SDK storage assembly. The host supplies one coherent
 * set of named descriptors and the matching journal, and fences every selected
 * row/predicate when publishing later mutations. Descriptor bytes and hashes are
 * physical evidence, never permission to replace another owner's state.
 *
 * <p>Opening restores actual runtime state under a fresh SDK owner. It performs
 * no journal replay, registration, append, or PROCESS. Point bodies are decoded
 * only when selected. Staging retains immutable dependencies and returns named
 * descriptors; it neither publishes them nor creates a global realm CAS. The
 * host owns the immutable object store and journal lifetimes.</p>
 *
 * <p>A physical failure during SDK execution (including PROCESS) requires the
 * host to discard the entire scope without publication. Existing execution can
 * mutate several runtime-owned components before a later physical write fails;
 * this facade does not make those mutations an in-memory transaction. Reopen
 * from the still-committed selection and matching journal before retrying. A
 * failure confined to immutable prewrites in {@link Scope#stage()} may retry
 * stage on the same otherwise successful execution; no publication is implicit.</p>
 */
public final class RootedCoordinationStorage {
    private RootedCoordinationStorage() { }

    /**
     * Host-owned L1 for decoded immutable storage artifacts, shared across fresh
     * operation scopes. No database/client dependency or mutable engine is held.
     * Configure one cache per process runtime; close it only at host shutdown.
     */
    public static final class Cache implements AutoCloseable {
        private final RootedEngineStorage.Cache delegate;

        /**
         * Creates a weighted LRU, initially empty. Weight estimates are not exact
         * JVM heap measurements. A zero total budget disables retention.
         * @param maximumWeightBytes total estimated retained-weight budget
         * @param maximumEntries maximum retained artifact count
         * @param maximumEntryWeightBytes largest cacheable artifact weight
         */
        public Cache(long maximumWeightBytes, int maximumEntries, long maximumEntryWeightBytes) {
            delegate = new RootedEngineStorage.Cache(maximumWeightBytes, maximumEntries, maximumEntryWeightBytes);
        }

        /** Process-wide totals, not per-operation counters. */
        public record Statistics(long hits, long misses, long loads, long evictions,
                int retainedEntries, long retainedWeightBytes, long incompatibleHits, long waits, long failedLoads) { }

        /** Returns process totals without keys or content. @return cumulative cache statistics */
        public Statistics statistics() {
            var s = delegate.statistics();
            return new Statistics(s.hits(), s.misses(), s.loads(), s.evictions(), s.retainedEntries(),
                    s.retainedWeightBytes(), s.incompatibleHits(), s.waits(), s.failedLoads());
        }

        /** Drops cached artifacts; does not alter stored data, heads or active operations. */
        public void clear() { delegate.clear(); }

        /** Closes only the cache. The host separately owns active-operation shutdown. */
        @Override public void close() { delegate.close(); }
    }

    /**
     * Physical SDK limits only. Identity-pinned map values are not evicted.
     * At most five times {@code indexes.pinnedEntries() * maximumRowBytes}
     * encoded point-row bytes can be pinned, in addition to descriptor/node
     * caches. These conservative charges do not measure JVM heap.
     */
    public record SdkLimits(InsertionOrderedStorage.Limits indexes, int maximumRowBytes,
            int maximumDescriptorBytes, long maximumMemoBytes, int maximumMemoEntries, int maximumCodecBytes) {
        /** Checks scalar bounds without changing execution policy or semantic gas. */
        public SdkLimits {
            Objects.requireNonNull(indexes, "indexes");
            if (indexes.nodeBytes() <= 0 || indexes.keyBytes() <= 0 || indexes.indexValueBytes() <= 0
                    || indexes.descriptorBytes() <= 0 || indexes.cachedNodes() < 0 || indexes.recordBytes() <= 0
                    || indexes.pinnedBytes() < 0 || indexes.pinnedEntries() < 0 || maximumCodecBytes <= 0)
                throw new IllegalArgumentException("Invalid SDK storage bounds");
            new SdkPointStorage.Limits(maximumRowBytes, maximumDescriptorBytes, maximumMemoBytes, maximumMemoEntries);
            Math.multiplyExact(5L * indexes.pinnedEntries(), maximumRowBytes);
        }
        private SdkRuntimePointMaps.Limits maps() {
            return new SdkRuntimePointMaps.Limits(indexes, new SdkPointStorage.Limits(maximumRowBytes,
                    maximumDescriptorBytes, maximumMemoBytes, maximumMemoEntries), maximumCodecBytes);
        }
    }

    /** Explicit engine and SDK operational bounds; no new execution policy. */
    public record Limits(RootedEngineStorage.Limits engine, SdkLimits sdk) {
        /** Requires both bounded physical families. */
        public Limits { Objects.requireNonNull(engine, "engine"); Objects.requireNonNull(sdk, "sdk"); }
    }

    /**
     * Caller-pinned independent named slots. The host must preserve membership
     * and journal/read-set consistency; this is not a self-authorizing root.
     */
    public record Selection(Map<String, byte[]> slots) {
        /** Defensively owns every selected byte array. */
        public Selection { slots = SdkSelectionStorage.copy(slots); }
        @Override public Map<String, byte[]> slots() { return SdkSelectionStorage.copy(slots); }
    }

    /**
     * Opens a complete selected runtime without reconstructing its history.
     * @param objects immutable object storage, owned by the host
     * @param limits physical read/retention limits
     * @param selection coherent host-pinned named slots
     * @param provider ordinary read-only exact provider for subsequent work
     * @param journal matching selected journal storage, owned by the host
     * @return one new SDK/engine owner and its scopes
     */
    public static Scope open(CoordinationImmutableObjectStore objects, Limits limits, Selection selection,
            ExactNodeProvider provider, TimelineJournalStore journal) {
        return openSelected(objects, limits, selection, provider, journal, null);
    }

    /**
     * Opens a fresh selected runtime with an optional-retention host L1.
     * @param objects host-owned immutable physical bytes
     * @param limits unchanged physical read/retention limits
     * @param selection coherent current selected slots
     * @param provider read-only exact provider
     * @param journal matching current journal
     * @param cache host-owned cache, not closed when the operation scope closes
     * @return fresh SDK/engine owner sharing only verified immutable artifacts
     */
    public static Scope open(CoordinationImmutableObjectStore objects, Limits limits, Selection selection,
            ExactNodeProvider provider, TimelineJournalStore journal, Cache cache) {
        return openSelected(objects, limits, selection, provider, journal, Objects.requireNonNull(cache));
    }

    private static Scope openSelected(CoordinationImmutableObjectStore objects, Limits limits, Selection selection,
            ExactNodeProvider provider, TimelineJournalStore journal, Cache cache) {
        Objects.requireNonNull(objects); Objects.requireNonNull(limits); Objects.requireNonNull(selection);
        Objects.requireNonNull(provider); Objects.requireNonNull(journal);
        return physical(() -> {
            var codec = new SdkSelectionStorage(limits.sdk().maximumCodecBytes());
            var parts = codec.decode(selection.slots());
            var opening = new Opening();
            try {
                var coordination = new BlueCoordination(owner -> {
                    opening.runtime = SdkCoordinationRuntime.restore(owner, parts.configuration(), provider, scopedProvider -> {
                        var engineSelection = new RootedEngineStorage.Selection(parts.engine());
                        opening.engine = cache == null
                                ? RootedEngineStorage.open(objects, limits.engine(), engineSelection, scopedProvider, journal)
                                : RootedEngineStorage.open(objects, limits.engine(), engineSelection, scopedProvider, journal, cache.delegate);
                        return opening.engine.engine();
                    });
                    opening.maps = SdkRuntimePointMaps.open(opening.runtime, objects, limits.sdk().maps(), parts.maps());
                    opening.runtime.installPointMaps(opening.maps.maps());
                    return opening.runtime;
                });
                return new Scope(coordination, opening.engine, opening.maps, codec);
            } catch (RuntimeException | Error failure) {
                opening.closeAfter(failure); throw failure;
            }
        });
    }

    /**
     * Explicit one-time conversion of an already resident selected partition.
     * This enumerates that partition's existing maps; normal cold open/stage do
     * not call it. No selected descriptors or journal mutations are published.
     * @param coordination original resident owner, kept open
     * @param objects immutable destination object storage
     * @param limits operational bounds
     * @return independent named descriptors for host publication
     */
    public static Selection retainPartition(BlueCoordination coordination, CoordinationImmutableObjectStore objects, Limits limits) {
        Objects.requireNonNull(coordination); Objects.requireNonNull(objects); Objects.requireNonNull(limits);
        var runtime = coordination.runtimeForStorage();
        synchronized (runtime) {
            return physical(() -> {
                var engine = (DefaultCoordinationEngine) runtime.engine();
                try (var maps = SdkRuntimePointMaps.retain(runtime, objects, limits.sdk().maps())) {
                    var retained = RootedEngineStorage.retainPartition(engine, objects, limits.engine());
                    return new Selection(new SdkSelectionStorage(limits.sdk().maximumCodecBytes()).encode(
                            new SdkSelectionStorage.Parts(runtime.storageConfiguration(), maps.snapshot(), retained.slots())));
                }
            });
        }
    }

    /**
     * One actual SDK owner and all selected physical scopes; not a publication
     * transaction. Discard it after physical execution failure. Only a failure
     * confined to stage's immutable prewrites permits retrying stage in place.
     */
    public static final class Scope implements AutoCloseable {
        private final BlueCoordination coordination;
        private final SdkCoordinationRuntime runtime;
        private final RootedEngineStorage.Scope engine;
        private final SdkRuntimePointMaps maps;
        private final SdkSelectionStorage codec;
        private boolean closed;
        private Scope(BlueCoordination coordination, RootedEngineStorage.Scope engine,
                SdkRuntimePointMaps maps, SdkSelectionStorage codec) {
            this.coordination = coordination; this.runtime = coordination.runtimeForStorage();
            this.engine = engine; this.maps = maps; this.codec = codec;
        }
        /** Returns the live fresh SDK owner. @return SDK facade */
        public BlueCoordination coordination() {
            synchronized (runtime) { guard(); return coordination; }
        }
        /**
         * Finds a fresh owner-bound handle by retained catalog membership only,
         * including pending/non-ready documents. This does not load or validate
         * the session body; ordinary reads and execution retain their checks.
         * @param id exact retained document identity
         * @return handle owned by this live SDK, or empty when absent
         */
        public Optional<DocumentHandle> documentHandle(DocumentId id) {
            synchronized (runtime) {
                guard(); return physical(() -> runtime.findStoredDocumentHandle(id));
            }
        }
        /**
         * Finds one retained SDK registration without registering or appending.
         * @param timelineId exact registered Timeline identity
         * @return handle owned by this live SDK, including registered-empty Timelines
         */
        public Optional<TimelineHandle> timelineHandle(String timelineId) {
            synchronized (runtime) {
                guard();
                return physical(() -> Optional.ofNullable(maps.maps().timelines().get(
                        SdkPreconditions.requireText(timelineId, "timelineId"))));
            }
        }
        /**
         * Explicit selected identity inventory, including pending documents;
         * never reads session bodies. Linear in catalog size, not a scheduler.
         * @return detached identities in stable host presentation order
         */
        public List<DocumentId> documentIds() {
            synchronized (runtime) {
                guard(); return physical(() -> engine.engine().storedDocumentIds());
            }
        }
        /**
         * Explicit selected registration inventory, including empty Timelines.
         * Does not hydrate Timeline handles or load the journal.
         * @return detached identities in stable host presentation order
         */
        public List<String> timelineIds() {
            synchronized (runtime) {
                guard(); return physical(() -> maps.maps().timelines().keySet().stream().sorted().toList());
            }
        }
        /**
         * Retains immutable dependencies and captures independent named slots
         * under the SDK lock. The host must validate and atomically publish its
         * selected read/write sets; failure leaves only unreachable prewrites.
         * Retry in place is valid only for stage/prewrite failure, never after a
         * physical failure during the preceding SDK execution.
         * @return descriptor bytes, not publication authority
         */
        public Selection stage() {
            synchronized (runtime) {
                guard();
                return physical(() -> new Selection(codec.encode(new SdkSelectionStorage.Parts(
                        runtime.storageConfiguration(), maps.snapshot(), engine.stage().slots()))));
            }
        }
        private void guard() {
            if (closed) throw new CoordinationObjectStorageException("SDK storage scope is closed");
            physical(runtime::engine);
        }
        /** Closes the SDK and every physical scope, but not the host stores. */
        @Override public void close() {
            synchronized (runtime) {
                if (closed) return; closed = true;
                Throwable failure = closeOne(null, coordination::close);
                failure = closeOne(failure, maps::close);
                failure = closeOne(failure, engine::close);
                if (failure instanceof Error error) throw error;
                if (failure instanceof RuntimeException exception) throw exception;
            }
        }
    }

    private static final class Opening {
        private SdkCoordinationRuntime runtime;
        private RootedEngineStorage.Scope engine;
        private SdkRuntimePointMaps maps;
        private void closeAfter(Throwable failure) {
            if (runtime != null) closeOne(failure, runtime::close);
            if (maps != null) closeOne(failure, maps::close);
            if (engine != null) closeOne(failure, engine::close);
        }
    }
    private static Throwable closeOne(Throwable prior, Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException | Error failure) {
            if (prior == null) return failure;
            if (prior != failure) prior.addSuppressed(failure);
        }
        return prior;
    }
    private static <T> T physical(Supplier<T> operation) {
        try { return operation.get(); }
        catch (NoncommittingExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CoordinationObjectStorageException("Invalid rooted SDK storage", failure); }
    }
}
