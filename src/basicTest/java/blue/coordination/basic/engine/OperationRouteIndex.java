package blue.coordination.basic.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Direct operation-aware index from exact dispatch headers to autonomous Roots.
 * Large request content is not part of the lookup key.
 */
public final class OperationRouteIndex {
    private final Map<RouteKey, List<DocumentId>> rows = new LinkedHashMap<>();
    private final Map<DocumentId, Set<RouteKey>> keysByDocument =
            new LinkedHashMap<>();
    private final EngineMetrics metrics;

    public OperationRouteIndex(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public synchronized void replace(
            DocumentId documentId,
            RoutingSurface surface) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(surface, "surface");
        remove(documentId);
        Set<RouteKey> inserted = new LinkedHashSet<>();
        for (RoutingSurface.Definition definition : surface.definitions()) {
            RouteKey key = new RouteKey(
                    definition.operation(),
                    definition.channelKey(),
                    definition.timelineId(),
                    definition.actorId());
            rows.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(documentId);
            inserted.add(key);
        }
        for (RouteKey key : inserted) {
            rows.get(key).sort(Comparator.naturalOrder());
        }
        keysByDocument.put(documentId, inserted);
        metrics.increment("routing.surfaceCompilations");
        metrics.add("routing.rowsCompiled", inserted.size());
    }

    public synchronized List<DocumentId> route(ExactTimelineEntry entry) {
        Objects.requireNonNull(entry, "entry");
        metrics.increment("routing.lookups");
        if (entry.processorManaged()) {
            return List.of(entry.target().orElseThrow());
        }
        RouteKey key = new RouteKey(
                entry.operation(),
                entry.channel(),
                entry.timeline().timelineId(),
                entry.timeline().actorId());
        List<DocumentId> targets = rows.getOrDefault(key, List.of());
        metrics.add("routing.targetsSelected", targets.size());
        return Collections.unmodifiableList(new ArrayList<>(targets));
    }

    public synchronized boolean routesTo(
            DocumentId documentId,
            ExactTimelineEntry entry) {
        return route(entry).contains(documentId);
    }

    public synchronized void remove(DocumentId documentId) {
        Set<RouteKey> existing = keysByDocument.remove(documentId);
        if (existing == null) {
            return;
        }
        for (RouteKey key : existing) {
            List<DocumentId> targets = rows.get(key);
            if (targets == null) {
                continue;
            }
            targets.remove(documentId);
            if (targets.isEmpty()) {
                rows.remove(key);
            }
        }
    }

    public synchronized int rowCount() {
        return rows.size();
    }

    public synchronized void clear() {
        rows.clear();
        keysByDocument.clear();
    }

    private record RouteKey(
            String operation,
            String channel,
            String timelineId,
            String actorId) {
        private RouteKey {
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
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
