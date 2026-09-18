package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.internal.RootedSourceDiscoveryCoordinator.Pending;
import java.util.*;

/** Pending source requests with one independently protected membership per requesting owner. */
final class LogicalSourcePendingMap extends AbstractMap<String, Pending>
        implements RootedSourceDiscoveryCoordinator.PendingSelections {
    private final LogicalRecordContext context;
    private final Map<String, Pending> rows;
    private PersistentOrderedMap<DocumentId, PersistentOrderedMap<String, Boolean>> owners;

    LogicalSourcePendingMap(LogicalPointStorage logical, Map<String, Pending> rows, PersistentMapStorage.Limits limits) {
        context = logical.context(); this.rows = rows;
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
        owners = new LogicalRecordBuckets<>(EmbeddingBinding.DOCUMENT_ORDER, EmbeddingBinding.TEXT_ORDER,
                context, Family.SOURCE_PENDING, new Bytes(OrderedRecordKey.text().encode("engine/source-pending-owners/1")),
                OrderedRecordKey.document(), OrderedRecordKey.text(), membership, limits).open();
        logical.selectBeforeFlush(() -> owners.selectLogicalRecords());
    }
    @Override public List<Pending> forRoot(DocumentId root) {
        return context.protect(() -> {
            var result = new ArrayList<Pending>();
            for (String key : owners.get(root).keys()) {
                var row = rows.get(key);
                if (row == null || !row.owns(root)) throw new IllegalStateException("Pending source membership differs from its owner");
                result.add(row);
            }
            return List.copyOf(result);
        });
    }
    private void replaceMembers(Pending prior, Pending next) {
        if (prior != null) for (var root : prior.invocation().rootedEvidence().context().entryOwners()) {
            var id = DocumentId.of(root.value()); var bucket = owners.get(id);
            if (!Boolean.TRUE.equals(bucket.get(prior.key()))) throw new IllegalStateException("Missing pending source membership");
            owners = owners.put(id, bucket.remove(prior.key()).map()).map();
        }
        if (next != null) for (var root : next.invocation().rootedEvidence().context().entryOwners()) {
            var id = DocumentId.of(root.value());
            owners = owners.put(id, owners.get(id).put(next.key(), true).map()).map();
        }
    }
    @Override public Pending get(Object key) { return context.protect(() -> rows.get(key)); }
    @Override public int size() { return context.protect(rows::size); }
    @Override public Pending put(String key, Pending value) {
        return context.protect(() -> {
            if (!key.equals(value.key())) throw new IllegalArgumentException("Foreign pending source key");
            var prior = rows.get(key); replaceMembers(prior, value); rows.put(key, value); return prior;
        });
    }
    @Override public Pending remove(Object key) {
        return context.protect(() -> {
            var prior = rows.get(key); replaceMembers(prior, null); rows.remove(key); return prior;
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
