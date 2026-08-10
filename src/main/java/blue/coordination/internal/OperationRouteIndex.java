package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.TimelineEntry;

import blue.coordination.processor.TimelineProviderSupport;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Direct operation-aware index from exact external headers to managed documents.
 * Large request content is not part of the lookup key.
 */
final class OperationRouteIndex {
    private final Map<RouteKey, List<RouteRow>> rows = new LinkedHashMap<>();
    private final Map<DocumentId, Set<RouteKey>> keysByDocument =
            new LinkedHashMap<>();
    private final EngineMetrics metrics;
    private final Function<DocumentId, DocumentSession> sessionResolver;
    private long generation;

    public OperationRouteIndex(EngineMetrics metrics) {
        this(metrics, ignored -> null);
    }

    public OperationRouteIndex(
            EngineMetrics metrics,
            Function<DocumentId, DocumentSession> sessionResolver) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.sessionResolver = Objects.requireNonNull(
                sessionResolver, "sessionResolver");
    }

    /** Replaces only one document's rows from its exact active intervals. */
    public synchronized void replace(
            DocumentId documentId,
            RoutingSurface surface,
            List<SubscriptionDelta.Entry> activeSubscriptions) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(activeSubscriptions, "activeSubscriptions");
        Map<OccurrenceKey, SubscriptionDelta.Entry> subscriptions =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry subscription : activeSubscriptions) {
            Objects.requireNonNull(subscription, "active subscription");
            if (!subscription.isActiveInterval()) {
                throw new IllegalStateException(
                        "Cannot index retired subscription at "
                                + subscription.scopePath() + "/"
                                + subscription.channelKey());
            }
            OccurrenceKey occurrence = new OccurrenceKey(
                    subscription.scopePath(), subscription.channelKey());
            if (subscriptions.putIfAbsent(
                    occurrence, subscription) != null) {
                throw new IllegalStateException(
                        "Duplicate active route occurrence at "
                                + subscription.scopePath() + "/"
                                + subscription.channelKey());
            }
        }
        Map<RouteKey, List<RouteRow>> inserted = new LinkedHashMap<>();
        for (RoutingSurface.Definition definition : surface.definitions()) {
            SubscriptionDelta.Entry subscription = subscriptions.get(
                    new OccurrenceKey(
                            definition.scopePath(),
                            definition.channelKey()));
            if (subscription == null) {
                continue;
            }
            for (String subscriptionKey : subscription.subscriptionKeys()) {
                RouteKey key = new RouteKey(
                        definition.operation(),
                        definition.channelKey(),
                        subscriptionKey);
                RouteRow row = new RouteRow(
                        documentId,
                        subscription.startAfterExternalOrderKey(),
                        definition.sources());
                inserted.computeIfAbsent(
                        key, ignored -> new ArrayList<>()).add(row);
            }
        }
        inserted.values().forEach(value -> value.sort(RouteRow.ORDER));
        // Validate and stage every exact row before replacing the currently
        // published route generation. Invalid evidence cannot partially
        // remove or publish one document's index rows.
        Set<RouteKey> candidates = new LinkedHashSet<>(
                keysByDocument.getOrDefault(documentId, Set.of()));
        candidates.addAll(inserted.keySet());
        List<RouteKey> changed = new ArrayList<>();
        for (RouteKey key : candidates) {
            List<RouteRow> current = rows.getOrDefault(key, List.of()).stream()
                    .filter(row -> row.documentId().equals(documentId))
                    .toList();
            if (current.equals(inserted.getOrDefault(key, List.of()))) {
                metrics.increment("routing.routeKeysRetained");
            } else {
                changed.add(key);
            }
        }
        if (changed.isEmpty()) {
            metrics.increment("routing.surfacePublicationsSkipped");
            return;
        }
        long nextGeneration = Math.addExact(generation, 1L);
        for (RouteKey key : changed) {
            List<RouteRow> targets = rows.get(key);
            if (targets != null) {
                targets.removeIf(row -> row.documentId().equals(documentId));
                if (targets.isEmpty()) {
                    rows.remove(key);
                }
            }
            targets = rows.computeIfAbsent(key, ignored -> new ArrayList<>());
            targets.addAll(inserted.getOrDefault(key, List.of()));
            targets.sort(RouteRow.ORDER);
            if (targets.isEmpty()) {
                rows.remove(key);
            }
        }
        if (inserted.isEmpty()) {
            keysByDocument.remove(documentId);
        } else {
            keysByDocument.put(
                    documentId, new LinkedHashSet<>(inserted.keySet()));
        }
        generation = nextGeneration;
        metrics.increment("routing.surfaceCompilations");
        metrics.add("routing.routeKeysUpdated", changed.size());
        metrics.add("routing.rowsCompiled", changed.stream()
                .mapToLong(key -> inserted.getOrDefault(key, List.of()).size())
                .sum());
    }

    public synchronized List<DocumentId> route(TimelineEntry entry) {
        Objects.requireNonNull(entry, "entry");
        long started = System.nanoTime();
        metrics.increment("routing.lookups");
        DocumentTarget target = DocumentTarget.from(entry);
        Set<DocumentId> selected = new LinkedHashSet<>();
        for (String eventKey
                : TimelineProviderSupport.exactTimelineEntryEventKeys(
                entry.timeline().timelineId(), entry.timeline().actorId())) {
            for (RouteRow row : rows.getOrDefault(new RouteKey(
                    entry.operation(), entry.channel(), eventKey), List.of())) {
                if (row.accepts(entry)
                        && target.accepts(
                        row.documentId(), sessionResolver)) {
                    selected.add(row.documentId());
                }
            }
        }
        List<DocumentId> targets = selected.stream().sorted().toList();
        metrics.add("routing.targetsSelected", targets.size());
        metrics.addNanos("process.routeLookup", System.nanoTime() - started);
        return Collections.unmodifiableList(new ArrayList<>(targets));
    }

    public synchronized boolean routesTo(
            DocumentId documentId,
            TimelineEntry entry) {
        return route(entry).contains(documentId);
    }

    public synchronized void remove(DocumentId documentId) {
        Objects.requireNonNull(documentId, "documentId");
        if (!keysByDocument.containsKey(documentId)) {
            return;
        }
        long nextGeneration = Math.addExact(generation, 1L);
        removeRows(documentId);
        generation = nextGeneration;
    }

    private void removeRows(DocumentId documentId) {
        Set<RouteKey> existing = keysByDocument.remove(documentId);
        if (existing == null) {
            return;
        }
        for (RouteKey key : existing) {
            List<RouteRow> targets = rows.get(key);
            if (targets == null) {
                continue;
            }
            targets.removeIf(row -> row.documentId().equals(documentId));
            if (targets.isEmpty()) {
                rows.remove(key);
            }
        }
    }

    public synchronized int rowCount() {
        return rows.size();
    }

    /** Monotonic identity of the currently published routing surface. */
    public synchronized long generation() {
        return generation;
    }

    public synchronized void clear() {
        if (rows.isEmpty() && keysByDocument.isEmpty()) {
            return;
        }
        long nextGeneration = Math.addExact(generation, 1L);
        rows.clear();
        keysByDocument.clear();
        generation = nextGeneration;
    }

    private record RouteKey(
            String operation,
            String channel,
            String subscriptionKey) {
        private RouteKey {
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
            subscriptionKey = requireText(
                    subscriptionKey, "subscriptionKey");
        }
    }

    private record OccurrenceKey(String scopePath, String channelKey) {
        private OccurrenceKey {
            scopePath = requireText(scopePath, "scopePath");
            channelKey = requireText(channelKey, "channelKey");
        }
    }

    private record RouteRow(
            DocumentId documentId,
            ExternalOrderKey startAfter,
            List<RoutingSurface.SourceAddress> sources) {
        private static final Comparator<RouteRow> ORDER = Comparator
                .comparing(RouteRow::documentId)
                .thenComparing(row -> row.startAfter() == null
                        ? ""
                        : row.startAfter().toString())
                .thenComparing(row -> row.sources().toString());

        private RouteRow {
            documentId = Objects.requireNonNull(documentId, "documentId");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        }

        private boolean accepts(TimelineEntry entry) {
            if (startAfter != null
                    && entry.sourceOrderKey().compareTo(startAfter) <= 0) {
                return false;
            }
            return sources.stream().anyMatch(source ->
                    source.timelineId().equals(entry.timeline().timelineId())
                            && source.actorId().equals(
                            entry.timeline().actorId()));
        }
    }

    private record DocumentTarget(
            String stateBlueId, boolean exact, boolean supported) {
        private static DocumentTarget from(TimelineEntry entry) {
            if (hasRuntimeValue(entry.exactEvent()
                    .canonicalAt("/onBehalfOf"), false)) {
                return new DocumentTarget(null, false, false);
            }
            FrozenNode document = entry.exactEvent()
                    .canonicalAt("/message/document");
            if (!hasRuntimeValue(document, true)) {
                return new DocumentTarget(null, false, true);
            }
            FrozenNode exactVersion = entry.exactEvent().canonicalAt(
                    "/message/requireExactDocumentVersion");
            boolean exact = exactVersion != null
                    && Boolean.TRUE.equals(exactVersion.getValue());
            return new DocumentTarget(
                    document.isReferenceOnly()
                            ? document.getReferenceBlueId()
                            : document.blueId(),
                    exact,
                    true);
        }

        private static boolean hasRuntimeValue(
                FrozenNode node, boolean typeCounts) {
            return node != null && (node.isReferenceOnly()
                    || node.getValue() != null
                    || node.getItems() != null && !node.getItems().isEmpty()
                    || node.getProperties() != null
                    && !node.getProperties().isEmpty()
                    || typeCounts && node.getType() != null);
        }

        private boolean accepts(
                DocumentId documentId,
                Function<DocumentId, DocumentSession> resolver) {
            if (!supported) {
                return false;
            }
            if (stateBlueId == null) {
                return true;
            }
            DocumentSession session = resolver.apply(documentId);
            return session != null && (exact
                    ? session.currentRevision().after().blueId()
                    .equals(stateBlueId)
                    : session.epochForState(stateBlueId).isPresent());
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
