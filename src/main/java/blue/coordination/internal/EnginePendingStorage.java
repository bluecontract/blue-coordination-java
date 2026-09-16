package blue.coordination.internal;

import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.SourceHistoryPrerequisiteResult;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Five exact pending/result maps in one selected owner; immutable prewrites are not publication. */
final class EnginePendingStorage {
    enum Kind { DRAFT, SELECTION, SOURCE_PENDING, SOURCE_SUBMITTED, SOURCE_COMPLETED }
    record Snapshot(Map<Kind, StoredInsertionOrderedMap.Snapshot> roots) {
        Snapshot {
            roots = Map.copyOf(roots);
            require(roots.keySet().equals(Set.of(Kind.values())), "Incomplete pending-map snapshot");
        }
    }

    private final CoordinationImmutableObjectStore objects;
    private final StoredInsertionOrderedMap.Limits limits;
    private final DocumentSessionStorage sessions;
    private final OperationPlanStorageCodec plans;
    private final SourceDiscoveryStorageCodec sources;
    private final CoreReceiptStorageCodec results;
    private final PersistentMapCodec<String> keys;

    EnginePendingStorage(CoordinationImmutableObjectStore objects, StoredInsertionOrderedMap.Limits limits,
            DocumentSessionStorage sessions, int maximumDepth) {
        this(objects, limits, sessions, maximumDepth, null);
    }

    EnginePendingStorage(CoordinationImmutableObjectStore objects, StoredInsertionOrderedMap.Limits limits,
            DocumentSessionStorage sessions, int maximumDepth, RootedStorageCache cache) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        this.sessions = Objects.requireNonNull(sessions);
        plans = new OperationPlanStorageCodec(limits.maximumRecordBytes(), maximumDepth);
        sources = new SourceDiscoveryStorageCodec(limits.maximumRecordBytes(), maximumDepth, cache);
        results = new CoreReceiptStorageCodec(limits.maximumRecordBytes(), maximumDepth, cache);
        keys = new StoreIndexCodecs(objects, limits.indexes()).text;
    }

    Scope open(Snapshot selected, DocumentSessionStorage.OpenScope views,
            Function<String, ManagedEpochApplicationWork> originalWork,
            Function<String, ContractsClosureAdmissionReceipt> originalAdmission) {
        return new Scope(Objects.requireNonNull(selected), views, originalWork, originalAdmission);
    }

    Scope empty(DocumentSessionStorage.OpenScope views, Function<String, ManagedEpochApplicationWork> originalWork,
            Function<String, ContractsClosureAdmissionReceipt> originalAdmission) {
        return new Scope(null, views, originalWork, originalAdmission);
    }

    final class Scope implements AutoCloseable {
        private final Map<Kind, StoredInsertionOrderedMap<String, ?>> maps = new EnumMap<>(Kind.class);
        private final DocumentSessionStorage.OpenScope views;
        private final Function<String, ManagedEpochApplicationWork> originalWork;
        private final Function<String, ContractsClosureAdmissionReceipt> originalAdmission;
        private final NamedMap<ContractsManagedDraftPlan> drafts;
        private final NamedMap<ContractsManagedEpochSelectionPlan> selections;
        private final NamedMap<RootedSourceDiscoveryCoordinator.Pending> pending;
        private final NamedMap<RootedSourceDiscoveryCoordinator.Prepared> submitted;
        private final NamedMap<SourceHistoryPrerequisiteResult> completed;
        private boolean closed;

        private Scope(Snapshot selected, DocumentSessionStorage.OpenScope views,
                Function<String, ManagedEpochApplicationWork> originalWork,
                Function<String, ContractsClosureAdmissionReceipt> originalAdmission) {
            this.views = Objects.requireNonNull(views); this.originalWork = Objects.requireNonNull(originalWork);
            this.originalAdmission = Objects.requireNonNull(originalAdmission);
            try {
                drafts = map(Kind.DRAFT, selected,
                        (key, value) -> plans.encode(new OperationPlanStorageCodec.Plans(key, value, null)),
                        (key, bytes) -> {
                            var p = plans.decode(key, bytes); require(p.selection() == null, "Draft row also contains another plan");
                            return Objects.requireNonNull(p.draft());
                        }, (key, value) -> { });
                selections = map(Kind.SELECTION, selected,
                        (key, value) -> plans.encode(new OperationPlanStorageCodec.Plans(key, null, value)),
                        (key, bytes) -> {
                            var p = plans.decode(key, bytes); require(p.draft() == null, "Selection row also contains another plan");
                            return Objects.requireNonNull(p.selection());
                        }, (key, value) -> { });
                pending = map(Kind.SOURCE_PENDING, selected,
                        (key, value) -> sources.encodePending(value, sessions::retainView),
                        (key, bytes) -> sources.decodePending(key, bytes, views),
                        (key, value) -> {
                            require(key.equals(value.key()), "Pending source map has foreign key");
                            var historical = value.invocation().rootedEvidence().historicalOrigin();
                            require(historical == null || value.cutoff().equals(historical.logicalBoundary()),
                                    "Pending source changed its original historical boundary");
                        });
                submitted = map(Kind.SOURCE_SUBMITTED, selected,
                        (key, value) -> sources.encodePrepared(value, sessions::retainView),
                        (key, bytes) -> sources.decodePrepared(key, bytes, views), this::validateSubmitted);
                completed = map(Kind.SOURCE_COMPLETED, selected, this::encodeCompleted, this::decodeCompleted, this::validateCompleted);
            } catch (RuntimeException failure) {
                maps.values().forEach(StoredInsertionOrderedMap::close); throw failure;
            }
        }

        ContractsClosureAdapter.StoredPlans plans() { ensureOpen(); return new ContractsClosureAdapter.StoredPlans(drafts, selections); }
        RootedSourceDiscoveryCoordinator.StoredMaps sources() {
            ensureOpen(); return new RootedSourceDiscoveryCoordinator.StoredMaps(pending, completed, submitted);
        }

        Snapshot snapshot() {
            ensureOpen(); var selected = new EnumMap<Kind, StoredInsertionOrderedMap.Snapshot>(Kind.class);
            maps.forEach((kind, map) -> selected.put(kind, map.snapshot())); return new Snapshot(selected);
        }

        /** Explicit transfer of only the supplied selected owner's resident maps. Cold open never calls this. */
        void retain(ContractsClosureAdapter.StoredPlans p, RootedSourceDiscoveryCoordinator.StoredMaps s) {
            ensureOpen();
            require(maps.values().stream().allMatch(Map::isEmpty), "Pending transfer requires empty selected indexes");
            drafts.putAll(p.drafts()); selections.putAll(p.selections()); pending.putAll(s.pending());
            submitted.putAll(s.submitted()); completed.putAll(s.completed());
        }

        private void validateSubmitted(String key, RootedSourceDiscoveryCoordinator.Prepared value) {
            var d = value.descriptor(); require(key.equals(d.selectionIdentity()), "Submitted source map has foreign key");
            var request = pending.get(d.requestingInvocationIdentity() + "/" + d.demandIdentity());
            // A terminal parent can retire its old pending row while the source receipt
            // and submitted operation remain available for lost-response reconciliation.
            if (request == null) return;
            require(request.source().equals(d.sourceDocumentId()) && request.authored().blueId().equals(d.authoredBlueId())
                    && request.cutoff().equals(d.cutoffExclusive())
                    && request.invocation().rootedEvidence().context().canonicalRootDocumentId().value().equals(d.requestingRoot().value()),
                    "Submitted source differs from its original pending request");
            require(value.completeness() == null || value.completeness().graphGeneration()
                    == request.invocation().input().snapshot().graphGeneration(), "Submitted source completeness changed the requester graph");
        }

        private void validateCompleted(String key, SourceHistoryPrerequisiteResult value) {
            require(key.equals(value.selection().selectionIdentity()), "Completed source map has foreign key");
            var original = submitted.get(key);
            require(original != null && original.descriptor().equals(value.selection()), "Source response lost its original submitted operation");
            SourcePrerequisiteResultStorageValidation.require(original, value, originalAdmission, results);
            require(value.admission().map(a -> a.published()).orElse(false)
                    || value.processing().map(p -> p.committedProcessTransitions() > 0L).orElse(false),
                    "Completed source map cannot retain a noncommitting response");
        }

        private byte[] encodeCompleted(String key, SourceHistoryPrerequisiteResult value) {
            return SessionStorageWire.encode(limits.maximumRecordBytes(), w -> {
                w.text("blue-coordination/source-completed/1"); sources.descriptor(w, value.selection());
                optional(w, value.admission().orElse(null), (out, a) -> out.bytes(results.encodeAdmission(a)));
                optional(w, value.processing().orElse(null), (out, p) -> out.bytes(results.encodeDrain(p, originalWork)));
                w.bool(value.replayed());
            });
        }

        private SourceHistoryPrerequisiteResult decodeCompleted(String key, byte[] bytes) {
            var value = SessionStorageWire.decode(bytes, limits.maximumRecordBytes(), r -> {
                require("blue-coordination/source-completed/1".equals(text(r)), "Wrong source response format");
                return new SourceHistoryPrerequisiteResult(sources.descriptor(r),
                        java.util.Optional.ofNullable(optional(r, in -> results.decodeAdmission(in.bytes(limits.maximumRecordBytes())))),
                        java.util.Optional.ofNullable(optional(r, in -> results.decodeDrain(in.bytes(limits.maximumRecordBytes())))), r.bool());
            });
            require(key.equals(value.selection().selectionIdentity()) && Arrays.equals(bytes, encodeCompleted(key, value)),
                    "Noncanonical or foreign complete source response");
            return value;
        }

        private <V> NamedMap<V> map(Kind kind, Snapshot selected, BiFunction<String, V, byte[]> encodeValue,
                BiFunction<String, byte[], V> decodeValue, BiConsumer<String, V> validate) {
            var codec = new PersistentMapCodec<Named<V>>() {
                public String identity() { return "blue-coordination/pending-record/" + kind.name() + "/1"; }
                public Named<V> prepareForStorage(Named<V> row) {
                    // Only this mutation hook can retain view dependencies. Reads encode
                    // the packet already checked by the exact inner codec.
                    byte[] value = encodeValue.apply(row.key(), row.value());
                    byte[] packet = SessionStorageWire.encode(limits.maximumRecordBytes(), w -> { w.text(row.key()); w.bytes(value); });
                    return new Named<>(row.key(), row.value(), packet);
                }
                public byte[] encode(Named<V> row) {
                    require(row.packet() != null, "Pending row has not been prepared"); return row.packet().clone();
                }
                public Named<V> decode(byte[] bytes) {
                    return SessionStorageWire.decode(bytes, limits.maximumRecordBytes(), r -> {
                        String key = text(r); V value = decodeValue.apply(key, r.bytes(limits.maximumRecordBytes()));
                        return new Named<>(key, value, bytes.clone());
                    });
                }
            };
            var storage = new StoredInsertionOrderedMap.Storage<>(objects, limits, "engine/" + kind.name(),
                    "blue-codepoint-text/1", EmbeddingBinding.TEXT_ORDER, keys, codec);
            var map = selected == null ? storage.empty() : storage.open(selected.roots().get(kind));
            maps.put(kind, map); return new NamedMap<>(map, validate);
        }

        private final class NamedMap<V> extends AbstractMap<String, V> {
            private final StoredInsertionOrderedMap<String, Named<V>> map;
            private final BiConsumer<String, V> validate;
            private NamedMap(StoredInsertionOrderedMap<String, Named<V>> map, BiConsumer<String, V> validate) {
                this.map = map; this.validate = validate;
            }
            private V selected(String key, Named<V> row) {
                if (row == null) return null;
                require(key.equals(row.key()), "Pending index points to another record key");
                validate.accept(key, row.value()); return row.value();
            }
            @Override public int size() { ensureOpen(); return map.size(); }
            @Override public boolean isEmpty() { ensureOpen(); return map.isEmpty(); }
            @Override public boolean containsKey(Object key) { ensureOpen(); return map.containsKey(key); }
            @Override public V get(Object key) { ensureOpen(); return physical(() -> selected((String) key, map.get(key))); }
            @Override public V put(String key, V value) {
                ensureOpen(); return physical(() -> {
                    validate.accept(key, value); var old = map.put(key, new Named<>(key, Objects.requireNonNull(value), null));
                    return old == null ? null : old.value();
                });
            }
            @Override public V remove(Object key) {
                ensureOpen(); var old = map.remove(key); return old == null ? null : old.value();
            }
            @Override public boolean remove(Object key, Object value) {
                var actual = get(key); if (actual == null || !actual.equals(value)) return false;
                remove(key); return true;
            }
            @Override public void clear() { ensureOpen(); map.clear(); }
            @Override public Set<Entry<String, V>> entrySet() {
                ensureOpen(); return new AbstractSet<>() {
                    public int size() { return NamedMap.this.size(); }
                    public Iterator<Entry<String, V>> iterator() {
                        var entries = map.entrySet().iterator();
                        return new Iterator<>() {
                            public boolean hasNext() { ensureOpen(); return entries.hasNext(); }
                            public Entry<String, V> next() {
                                ensureOpen(); var e = entries.next();
                                return new SimpleImmutableEntry<>(e.getKey(), selected(e.getKey(), e.getValue()));
                            }
                            public void remove() { ensureOpen(); entries.remove(); }
                        };
                    }
                };
            }
        }

        private void ensureOpen() { require(!closed, "Engine pending storage scope is closed"); }
        @Override public void close() { if (!closed) { closed = true; maps.values().forEach(StoredInsertionOrderedMap::close); } }
    }

    private record Named<V>(String key, V value, byte[] packet) { }
}
