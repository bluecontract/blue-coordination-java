package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.*;

/** Shared source unions retain independent root memberships instead of shared mutable counts. */
final class LogicalActiveSources {
    private final PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> documents;
    private final PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, Boolean>> timelines;

    LogicalActiveSources(PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> documents,
            PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, Boolean>> timelines) {
        this.documents = documents; this.timelines = timelines;
    }
    LogicalActiveSources replace(DocumentId root, ContractsRootSourceSurface.Surface before, ContractsRootSourceSurface.Surface after) {
        var nextDocuments = documents; var nextTimelines = timelines;
        if (before != null) {
            for (var document : before.managedDocuments()) {
                var bucket = nextDocuments.get(document);
                if (!Boolean.TRUE.equals(bucket.get(root))) throw new IllegalStateException("Missing retained source membership");
                nextDocuments = nextDocuments.put(document, bucket.remove(root).map()).map();
            }
            for (var timeline : before.timelineIds()) {
                var bucket = nextTimelines.get(timeline);
                if (!Boolean.TRUE.equals(bucket.get(root))) throw new IllegalStateException("Missing retained Timeline membership");
                nextTimelines = nextTimelines.put(timeline, bucket.remove(root).map()).map();
            }
        }
        for (var document : after.managedDocuments())
            nextDocuments = nextDocuments.put(document, nextDocuments.get(document).put(root, true).map()).map();
        for (var timeline : after.timelineIds())
            nextTimelines = nextTimelines.put(timeline, nextTimelines.get(timeline).put(root, true).map()).map();
        return new LogicalActiveSources(nextDocuments, nextTimelines);
    }
    PersistentOrderedMap<DocumentId, Set<DocumentId>> memberships() {
        return documents.logicalValues(bucket -> Collections.unmodifiableSet(new LinkedHashSet<>(bucket.keys())),
                value -> { throw new IllegalStateException("Source memberships must change one Root at a time"); }, value -> !value.isEmpty());
    }
    PersistentOrderedMap<String, Long> counts() {
        var source = new LogicalRecordMap.Source<String, Long>() {
            public Long get(String key) { int count = timelines.get(key).size(); return count == 0 ? null : (long) count; }
            public boolean contains(String key) { return timelines.containsKey(key); }
            public List<Map.Entry<String, Long>> entries() {
                return timelines.keys().stream().map(key -> Map.entry(key, Objects.requireNonNull(get(key)))).toList();
            }
            public Map.Entry<String, Long> first(String lower, boolean exclusive, String upper) {
                var iterator = timelines.range(lower, upper);
                while (iterator.hasNext()) {
                    var row = iterator.next();
                    if (exclusive && lower != null && lower.equals(row.getKey())) continue;
                    return Map.entry(row.getKey(), Objects.requireNonNull(get(row.getKey())));
                }
                return null;
            }
        };
        return PersistentOrderedMap.logical(EmbeddingBinding.TEXT_ORDER,
                LogicalRecordMap.virtual(EmbeddingBinding.TEXT_ORDER, timelines.logicalContext(), source,
                        (key, value) -> { throw new IllegalStateException("Source counts are derived from Root memberships"); }, value -> true));
    }
    void select() { documents.selectLogicalRecords(); timelines.selectLogicalRecords(); }
}
