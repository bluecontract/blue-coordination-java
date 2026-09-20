package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.*;
import static blue.coordination.internal.OperationRouteIndex.*;

/** Route selection observes a complete bucket; replacing one document observes only its own member. */
final class LogicalRouteRows implements LogicalRecordMap.Source<RouteKey, List<RouteRow>> {
    private final LogicalRecordContext context;
    private final PersistentOrderedMap<RouteKey, PersistentOrderedMap<DocumentId, List<RouteRow>>> buckets;

    LogicalRouteRows(LogicalRecordContext context,
            PersistentOrderedMap<RouteKey, PersistentOrderedMap<DocumentId, List<RouteRow>>> buckets) {
        this.context = context; this.buckets = buckets;
    }
    List<RouteRow> forDocument(RouteKey key, DocumentId document) {
        var rows = buckets.get(key).get(document);
        if (rows == null) return List.of();
        for (var row : rows) if (!document.equals(row.documentId()))
            throw new IllegalStateException("Route member belongs to another document");
        return rows;
    }
    LogicalRouteRows replace(RouteKey key, DocumentId document, List<RouteRow> rows) {
        for (var row : rows) if (!document.equals(row.documentId()))
            throw new IllegalArgumentException("Route replacement belongs to another document");
        var bucket = buckets.get(key);
        var next = rows.isEmpty() ? bucket.remove(document).map() : bucket.put(document, List.copyOf(rows)).map();
        return new LogicalRouteRows(context, buckets.put(key, next).map());
    }
    void select() { buckets.selectLogicalRecords(); }
    PersistentOrderedMap<RouteKey, List<RouteRow>> all() {
        return PersistentOrderedMap.logical(RouteKey.ORDER, LogicalRecordMap.virtual(RouteKey.ORDER, context, this,
                (key, value) -> { throw new IllegalStateException("Route mutations must select a document member"); }, rows -> !rows.isEmpty()));
    }
    @Override public List<RouteRow> get(RouteKey key) {
        var result = new ArrayList<RouteRow>();
        for (var member : buckets.get(key).entries()) result.addAll(forDocument(key, member.getKey()));
        if (result.isEmpty()) return null;
        result.sort(RouteRow.ORDER); return List.copyOf(result);
    }
    @Override public boolean contains(RouteKey key) { return buckets.containsKey(key); }
    @Override public List<Map.Entry<RouteKey, List<RouteRow>>> entries() {
        return buckets.keys().stream().map(key -> Map.entry(key, Objects.requireNonNull(get(key)))).toList();
    }
    @Override public Map.Entry<RouteKey, List<RouteRow>> first(RouteKey lower, boolean exclusive, RouteKey upper) {
        var iterator = buckets.range(lower, upper);
        while (iterator.hasNext()) {
            var row = iterator.next();
            if (exclusive && lower != null && RouteKey.ORDER.compare(lower, row.getKey()) == 0) continue;
            return Map.entry(row.getKey(), Objects.requireNonNull(get(row.getKey())));
        }
        return null;
    }
}
