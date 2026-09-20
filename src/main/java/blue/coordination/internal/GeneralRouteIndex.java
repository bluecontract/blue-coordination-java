package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.TimelineEntry;
import blue.coordination.processor.TimelineProviderSupport;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import java.util.*;

/** Scoped channel candidates; registered functions, not this index, decide delivery. */
final class GeneralRouteIndex {
    record Row(DocumentId documentId, String scopePath, String channelKey, int order,
            ExternalOrderKey startAfter, List<RoutingSurface.SourceAddress> sources) {
        static final Comparator<Row> ORDER = Comparator.comparingInt(Row::order)
                .thenComparing(Row::documentId, EmbeddingBinding.DOCUMENT_ORDER)
                .thenComparing(Row::scopePath, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(Row::channelKey, EmbeddingBinding.TEXT_ORDER);
        Row {
            Objects.requireNonNull(documentId); Objects.requireNonNull(scopePath); Objects.requireNonNull(channelKey);
            if (!scopePath.startsWith("/") || channelKey.isBlank() || order < 0) throw new IllegalArgumentException("Invalid channel row");
            sources = List.copyOf(sources);
            var canonical = new RoutingSurface.ChannelDefinition(scopePath, channelKey, sources).sources();
            if (!sources.equals(canonical)) throw new IllegalArgumentException("Noncanonical general channel sources");
        }
        boolean accepts(TimelineEntry entry) {
            return (startAfter == null || startAfter.compareTo(entry.sourceOrderKey()) < 0)
                    && sources.stream().anyMatch(source -> source.timelineId().equals(entry.timeline().timelineId())
                            && source.actorId().equals(entry.timeline().actorId()));
        }
    }
    private final PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, List<Row>>> buckets;
    private final PersistentOrderedMap<DocumentId, Set<String>> documents;

    GeneralRouteIndex(PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, List<Row>>> buckets,
            PersistentOrderedMap<DocumentId, Set<String>> documents) {
        this.buckets = Objects.requireNonNull(buckets); this.documents = Objects.requireNonNull(documents);
    }
    static GeneralRouteIndex empty() {
        return new GeneralRouteIndex(PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER));
    }
    GeneralRouteIndex cleared() {
        var result = this;
        for (var document : documents.keys()) result = result.replace(document, new RoutingSurface(List.of(), false), List.of());
        return result;
    }
    void selectLogical() {
        buckets.selectLogicalRecords(); documents.selectLogicalRecords();
    }
    PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, List<Row>>> buckets() { return buckets; }
    PersistentOrderedMap<DocumentId, Set<String>> documents() { return documents; }
    boolean contains(DocumentId document) { return documents.containsKey(document); }

    GeneralRouteIndex replace(DocumentId document, RoutingSurface surface, List<SubscriptionDelta.Entry> subscriptions) {
        var inserted = new LinkedHashMap<String, List<Row>>();
        for (var channel : surface.channels()) for (var subscription : subscriptions) {
            if (!channel.scopePath().equals(subscription.scopePath()) || !channel.channelKey().equals(subscription.channelKey())) continue;
            if (!subscription.isActiveInterval()) throw new IllegalArgumentException("Retired general channel subscription");
            for (String key : subscription.subscriptionKeys()) inserted.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new Row(document, channel.scopePath(), channel.channelKey(), subscription.order(),
                            subscription.startAfterExternalOrderKey(), channel.sources()));
        }
        inserted.replaceAll((key, values) -> values.stream().distinct().sorted(Row.ORDER).toList());
        var oldKeys = documents.get(document);
        var keys = new LinkedHashSet<String>(oldKeys == null ? Set.of() : oldKeys); keys.addAll(inserted.keySet());
        var next = buckets; boolean changed = false;
        for (String key : keys) {
            var bucket = next.get(key);
            if (bucket == null) bucket = PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER);
            var old = bucket.get(document); var replacement = inserted.get(key);
            if (Objects.equals(old, replacement)) continue;
            changed = true;
            bucket = replacement == null ? bucket.remove(document).map() : bucket.put(document, replacement).map();
            next = next.put(key, bucket).map();
        }
        if (!changed) return this;
        var nextDocuments = inserted.isEmpty() ? documents.remove(document).map()
                : documents.put(document, Collections.unmodifiableSet(new LinkedHashSet<>(inserted.keySet()))).map();
        return new GeneralRouteIndex(next, nextDocuments);
    }
    List<Row> candidates(TimelineEntry entry, EngineMetrics metrics) {
        Set<Row> selected = new LinkedHashSet<>();
        for (String key : TimelineProviderSupport.exactTimelineEntryEventKeys(entry.timeline().timelineId(), entry.timeline().actorId())) {
            var bucket = buckets.get(key);
            if (bucket == null) continue;
            for (var member : bucket.entries()) for (var row : member.getValue()) {
                if (!member.getKey().equals(row.documentId())) throw new IllegalStateException("Foreign general route member");
                metrics.increment("routing.generalRowsInspected");
                if (row.accepts(entry)) selected.add(row);
            }
        }
        return selected.stream().sorted(Row.ORDER).toList();
    }
}
