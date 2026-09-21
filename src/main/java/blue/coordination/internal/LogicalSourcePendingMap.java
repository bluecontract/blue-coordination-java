package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.SourceHistoryRequest;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.internal.RootedSourceDiscoveryCoordinator.Pending;
import java.util.*;

/** Exact pending-work memberships for both requesting instances and original source instances. */
final class LogicalSourcePendingMap extends AbstractMap<String, Pending>
        implements RootedSourceDiscoveryCoordinator.PendingSelections {
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final Map<String, Pending> rows;
    private PersistentOrderedMap<DocumentInstanceRef, PersistentOrderedMap<String, Boolean>> owners, sources;
    private PersistentOrderedMap<String, Association> associations;
    private final Map<Pending, Association> selectedAssociations = new IdentityHashMap<>();
    private record Association(String key, DocumentInstanceRef source, List<DocumentInstanceRef> owners) {
        Association { owners = List.copyOf(owners); }
    }

    LogicalSourcePendingMap(LogicalPointStorage logical, Map<String, Pending> rows, PersistentMapStorage.Limits limits) {
        context = logical.context(); instances = context.instances(limits.valueBytes()); this.rows = rows;
        var membership = new PersistentMapCodec<Boolean>() {
            public String identity() { return "blue-coordination/source-pending-member/1"; }
            public byte[] encode(Boolean value) {
                if (!Boolean.TRUE.equals(value)) throw new IllegalArgumentException("Invalid pending member");
                return new byte[] {1};
            }
            public Boolean decode(byte[] bytes) {
                if (!Arrays.equals(bytes, new byte[] {1})) throw new IllegalArgumentException("Invalid pending member");
                return true;
            }
        };
        owners = buckets("owners", membership, limits); sources = buckets("sources", membership, limits);
        var codec = new PersistentMapCodec<Association>() {
            public String identity() { return "blue-coordination/source-pending-instances/1"; }
            public byte[] encode(Association value) {
                return SessionStorageWire.encode(limits.valueBytes(), w -> {
                    w.text(identity()); w.text(value.key()); reference(w, value.source()); w.integer(value.owners().size());
                    value.owners().forEach(ref -> reference(w, ref));
                });
            }
            public Association decode(byte[] bytes) {
                return SessionStorageWire.decode(bytes, limits.valueBytes(), r -> {
                    SessionStorageWire.require(identity().equals(r.text(r.remaining())), "Unknown pending instance format");
                    String key = r.text(r.remaining()); var source = reference(r); int count = r.integer();
                    SessionStorageWire.require(count > 0 && count <= r.remaining() / 8, "Invalid pending owner count");
                    var selected = new ArrayList<DocumentInstanceRef>();
                    for (int i = 0; i < count; i++) selected.add(reference(r));
                    SessionStorageWire.require(selected.equals(selected.stream().distinct().sorted(InstanceSourceKeys.ORDER).toList()),
                            "Pending owner instances are not canonical");
                    return new Association(key, source, selected);
                });
            }
        };
        associations = PersistentOrderedMap.logical(EmbeddingBinding.TEXT_ORDER, LogicalRecordMap.open(EmbeddingBinding.TEXT_ORDER,
                context, Family.SOURCE_PENDING, scope("instances"), OrderedRecordKey.text(), codec, limits.keyBytes(), limits.valueBytes()));
        logical.selectBeforeFlush(() -> { owners.selectLogicalRecords(); sources.selectLogicalRecords(); associations.selectLogicalRecords(); });
    }
    private PersistentOrderedMap<DocumentInstanceRef, PersistentOrderedMap<String, Boolean>> buckets(String name,
            PersistentMapCodec<Boolean> codec, PersistentMapStorage.Limits limits) {
        return new LogicalRecordBuckets<>(InstanceSourceKeys.ORDER, EmbeddingBinding.TEXT_ORDER,
                context, Family.SOURCE_PENDING, scope(name), InstanceSourceKeys.INSTANCE, OrderedRecordKey.text(), codec, limits).open();
    }
    private static Bytes scope(String name) { return new Bytes(OrderedRecordKey.text().encode("engine/source-pending-" + name + "/2")); }
    private static void reference(SessionStorageWire.Writer w, DocumentInstanceRef ref) { w.text(ref.documentId().value()); w.text(ref.instanceId()); }
    private static DocumentInstanceRef reference(SessionStorageWire.Reader r) {
        return new DocumentInstanceRef(DocumentId.of(r.text(r.remaining())), r.text(r.remaining()));
    }
    @Override public List<Pending> forRoot(DocumentId root) {
        return context.protect(() -> {
            var binding = instances.select(root);
            if (binding.instance().isEmpty() && binding.generation() != 0) return List.of();
            var ref = binding.instance().orElseGet(() -> LogicalDocumentInstances.initialReference(root));
            return selected(owners.get(ref), ref, false);
        });
    }
    @Override public List<Pending> forSource(DocumentInstanceRef source) {
        return context.protect(() -> selected(sources.get(source), source, true));
    }
    private List<Pending> selected(PersistentOrderedMap<String, Boolean> bucket, DocumentInstanceRef ref, boolean source) {
        var result = new ArrayList<Pending>();
        for (String key : bucket.keys()) {
            var row = Objects.requireNonNull(rows.get(key), "Pending instance membership lacks its primary row");
            var association = association(key, row);
            if (source ? !association.source().equals(ref) : !association.owners().contains(ref))
                throw new IllegalStateException("Pending membership differs from its original instance");
            result.add(row);
        }
        return List.copyOf(result);
    }
    private Association association(String physical, Pending row) {
        var value = Objects.requireNonNull(associations.get(physical), "Pending work lacks its original instance association");
        if (!value.key().equals(physical) || !value.source().documentId().equals(row.source())
                || !value.owners().stream().map(DocumentInstanceRef::documentId).toList().equals(row.invocation().rootedEvidence().context().entryOwners()
                        .stream().map(id -> DocumentId.of(id.value())).sorted(EmbeddingBinding.DOCUMENT_ORDER).toList()))
            throw new IllegalStateException("Pending work instance association differs from its canonical request");
        if (!physical(row.key(), value.owners(), value.source()).equals(physical))
            throw new IllegalStateException("Pending physical key differs from its instance association");
        selectedAssociations.put(row, value);
        value.owners().forEach(instances::requireRetained);
        if (value.source().equals(LogicalDocumentInstances.initialReference(row.source()))) instances.retainedInitial(row.source());
        else instances.requireRetained(value.source());
        return value;
    }
    @Override public void putForSource(Pending value, DocumentInstanceRef source) {
        context.protect(() -> {
            if (!source.documentId().equals(value.source())) throw new IllegalArgumentException("Pending source instance has another document");
            if (source.equals(LogicalDocumentInstances.initialReference(value.source()))) instances.retainedInitial(value.source());
            else instances.requireRetained(source);
            var selected = value.invocation().rootedEvidence().context().entryOwners().stream()
                    .map(id -> instances.requireOrCreateInitial(DocumentId.of(id.value()))).sorted(InstanceSourceKeys.ORDER).toList();
            var physical = physical(value.key(), selected, source);
            var next = new Association(physical, source, selected); var prior = rows.get(physical);
            if (prior != null && !association(physical, prior).equals(next)) throw new IllegalStateException("Pending canonical request cannot retarget instances");
            for (var owner : selected) owners = owners.put(owner, owners.get(owner).put(physical, true).map()).map();
            sources = sources.put(source, sources.get(source).put(physical, true).map()).map();
            associations = associations.put(physical, next).map(); rows.put(physical, value); return null;
        });
    }
    private static String physical(String canonical, List<DocumentInstanceRef> owners, DocumentInstanceRef source) {
        if (owners.stream().allMatch(ref -> ref.equals(LogicalDocumentInstances.initialReference(ref.documentId())))
                && source.equals(LogicalDocumentInstances.initialReference(source.documentId()))) return canonical;
        return LogicalSourceRequests.contextKey(owners, source) + "/" + canonical;
    }
    @Override public boolean currentOwners(Pending row) {
        return context.protect(() -> {
            var association = Objects.requireNonNull(selectedAssociations.get(row), "Pending owner association was not selected");
            return association.owners().stream().allMatch(ref -> instances.select(ref.documentId()).instance().equals(Optional.of(ref)));
        });
    }
    @Override public SourceHistoryRequest request(Pending row, SourceHistoryPrerequisite descriptor) {
        return context.protect(() -> {
            var selected = row.invocation().rootedEvidence().context().entryOwners().stream()
                    .map(id -> instances.requireOrCreateInitial(DocumentId.of(id.value()))).sorted(InstanceSourceKeys.ORDER).toList();
            for (String key : owners.get(selected.get(0)).keys()) {
                var candidate = rows.get(key);
                if (candidate != null && candidate.key().equals(row.key())) {
                    var value = association(key, candidate);
                    if (!value.owners().equals(selected)) throw new IllegalStateException("Source request owners differ");
                    return new SourceHistoryRequest(descriptor, value.owners(), value.source());
                }
            }
            throw new IllegalStateException("Source request lacks native pending association");
        });
    }
    @Override public Pending forRequest(SourceHistoryRequest request) {
        return context.protect(() -> {
            String canonical = request.prerequisite().requestingInvocationIdentity() + "/" + request.prerequisite().demandIdentity();
            String key = physical(canonical, request.requestingInstances(), request.sourceInstance());
            var row = rows.get(key);
            if (row != null) {
                var value = association(key, row);
                if (!value.source().equals(request.sourceInstance()) || !value.owners().equals(request.requestingInstances()))
                    throw new IllegalStateException("Source request differs from original pending association");
            }
            return row;
        });
    }
    @Override public void removeCurrent(Pending row) {
        context.protect(() -> {
            var root = DocumentId.of(row.invocation().rootedEvidence().context().entryOwners().iterator().next().value());
            var owner = instances.requireOrCreateInitial(root);
            for (String key : List.copyOf(owners.get(owner).keys())) {
                var candidate = rows.get(key);
                if (candidate != null && candidate.key().equals(row.key())) remove(key);
            }
            return null;
        });
    }
    @Override public Pending get(Object key) {
        return context.protect(() -> { var row = rows.get(key); if (row != null) association((String) key, row); return row; });
    }
    @Override public int size() { return context.protect(rows::size); }
    @Override public Pending put(String key, Pending value) {
        return context.protect(() -> {
            if (!key.equals(value.key())) throw new IllegalArgumentException("Foreign pending source key");
            var prior = rows.get(key);
            var source = prior == null ? LogicalDocumentInstances.initialReference(value.source()) : association(key, prior).source();
            if (prior == null && instances.select(value.source()).instance().filter(ref -> !ref.equals(source)).isPresent())
                throw new IllegalStateException("Pending replacement source requires explicit instance selection");
            putForSource(value, source); return prior;
        });
    }
    @Override public Pending remove(Object key) {
        return context.protect(() -> {
            var prior = rows.get(key);
            if (prior != null) {
                var value = association((String) key, prior);
                for (var owner : value.owners()) owners = owners.put(owner, owners.get(owner).remove((String) key).map()).map();
                sources = sources.put(value.source(), sources.get(value.source()).remove((String) key).map()).map();
                associations = associations.remove((String) key).map();
            }
            rows.remove(key); return prior;
        });
    }
    @Override public Set<Entry<String, Pending>> entrySet() {
        context.checkOpen(); return new AbstractSet<>() {
            public int size() { return LogicalSourcePendingMap.this.size(); }
            public Iterator<Entry<String, Pending>> iterator() {
                context.checkOpen(); var keys = List.copyOf(rows.keySet()).iterator();
                return new Iterator<>() {
                    String last; boolean removable;
                    public boolean hasNext() { context.checkOpen(); return keys.hasNext(); }
                    public Entry<String, Pending> next() {
                        context.checkOpen(); last = keys.next(); removable = true;
                        return new SimpleEntry<>(last, get(last)) {
                            @Override public Pending setValue(Pending value) {
                                var prior = put(getKey(), value); super.setValue(value); return prior;
                            }
                        };
                    }
                    public void remove() { context.checkOpen(); if (!removable) throw new IllegalStateException();
                        LogicalSourcePendingMap.this.remove(last); removable = false; }
                };
            }
        };
    }
}
