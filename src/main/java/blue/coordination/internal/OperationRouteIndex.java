package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.TimelineEntry;

import blue.coordination.processor.TimelineProviderSupport;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.ManagedScopeKey;
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
        prepareReplacement(List.of(new Replacement(
                documentId, surface, activeSubscriptions))).publish();
    }

    /**
     * Fully validates a set of document route replacements without publishing
     * any row. The returned handle performs only a generation CAS and one
     * precomputed map swap, so validation cannot fail after a durable document
     * transaction has committed.
     */
    synchronized PreparedReplacement prepareReplacement(
            List<Replacement> replacements) {
        List<Replacement> canonical = new ArrayList<>(Objects.requireNonNull(
                replacements, "replacements"));
        canonical.sort(Comparator.comparing(
                Replacement::documentId, EmbeddingBinding.DOCUMENT_ORDER));
        Set<DocumentId> unique = new LinkedHashSet<>();
        Map<DocumentId, Map<RouteKey, List<RouteRow>>> compiled =
                new LinkedHashMap<>();
        for (Replacement replacement : canonical) {
            Replacement checked = Objects.requireNonNull(
                    replacement, "replacement");
            if (!unique.add(checked.documentId())) {
                throw new IllegalArgumentException(
                        "Duplicate route replacement for "
                                + checked.documentId());
            }
            compiled.put(
                    checked.documentId(),
                    compile(
                            checked.documentId(),
                            checked.surface(),
                            checked.activeSubscriptions()));
        }

        Map<RouteKey, List<RouteRow>> preparedRows = copyRows(rows);
        Map<DocumentId, Set<RouteKey>> preparedKeys = copyKeys(
                keysByDocument);
        long retainedKeys = 0L;
        for (Replacement replacement : canonical) {
            DocumentId documentId = replacement.documentId();
            Map<RouteKey, List<RouteRow>> inserted = compiled.get(documentId);
            Set<RouteKey> candidates = new LinkedHashSet<>(
                    keysByDocument.getOrDefault(documentId, Set.of()));
            candidates.addAll(inserted.keySet());
            for (RouteKey key : candidates) {
                List<RouteRow> current = rows.getOrDefault(
                        key, List.of()).stream()
                        .filter(row -> row.documentId().equals(documentId))
                        .toList();
                if (current.equals(inserted.getOrDefault(key, List.of()))) {
                    retainedKeys = Math.addExact(retainedKeys, 1L);
                }
            }
            removeRows(preparedRows, preparedKeys, documentId);
            for (Map.Entry<RouteKey, List<RouteRow>> entry
                    : inserted.entrySet()) {
                List<RouteRow> targets = preparedRows.computeIfAbsent(
                        entry.getKey(), ignored -> new ArrayList<>());
                targets.addAll(entry.getValue());
                targets.sort(RouteRow.ORDER);
            }
            if (!inserted.isEmpty()) {
                preparedKeys.put(documentId,
                        new LinkedHashSet<>(inserted.keySet()));
            }
        }
        boolean changed = !rows.equals(preparedRows)
                || !keysByDocument.equals(preparedKeys);
        long resultingGeneration = changed
                ? Math.addExact(generation, 1L) : generation;
        Set<RouteKey> changedKeys = changedKeys(rows, preparedRows);
        long insertedRows = compiled.values().stream()
                .flatMap(value -> value.values().stream())
                .mapToLong(List::size)
                .sum();
        return new PreparedReplacement(
                generation,
                resultingGeneration,
                preparedRows,
                preparedKeys,
                changedKeys.size(),
                retainedKeys,
                insertedRows);
    }

    private Map<RouteKey, List<RouteRow>> compile(
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
                        definition.scopePath(),
                        subscription.order(),
                        subscription.startAfterExternalOrderKey(),
                        definition.sources());
                inserted.computeIfAbsent(
                        key, ignored -> new ArrayList<>()).add(row);
            }
        }
        inserted.values().forEach(value -> value.sort(RouteRow.ORDER));
        return inserted;
    }

    private synchronized void publish(PreparedReplacement prepared) {
        PreparedReplacement replacement = Objects.requireNonNull(
                prepared, "prepared");
        if (replacement.owner != this) {
            throw new IllegalArgumentException(
                    "Prepared routes belong to another route index");
        }
        if (replacement.published) {
            throw new IllegalStateException(
                    "Prepared routes were already published");
        }
        if (generation != replacement.expectedGeneration) {
            throw new IllegalStateException(
                    "Prepared route generation is stale: expected "
                            + replacement.expectedGeneration + " but found "
                            + generation);
        }
        rows.clear();
        rows.putAll(copyRows(replacement.rows));
        keysByDocument.clear();
        keysByDocument.putAll(copyKeys(replacement.keysByDocument));
        generation = replacement.resultingGeneration;
        replacement.published = true;
        metrics.add("routing.routeKeysRetained", replacement.retainedKeys);
        if (replacement.expectedGeneration
                == replacement.resultingGeneration) {
            metrics.increment("routing.surfacePublicationsSkipped");
            return;
        }
        metrics.increment("routing.surfaceCompilations");
        metrics.add("routing.routeKeysUpdated", replacement.changedKeys);
        metrics.add("routing.rowsCompiled", replacement.insertedRows);
    }

    private static Map<RouteKey, List<RouteRow>> copyRows(
            Map<RouteKey, List<RouteRow>> source) {
        Map<RouteKey, List<RouteRow>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                key, new ArrayList<>(value)));
        return result;
    }

    private static Map<DocumentId, Set<RouteKey>> copyKeys(
            Map<DocumentId, Set<RouteKey>> source) {
        Map<DocumentId, Set<RouteKey>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                key, new LinkedHashSet<>(value)));
        return result;
    }

    private static void removeRows(
            Map<RouteKey, List<RouteRow>> targetRows,
            Map<DocumentId, Set<RouteKey>> targetKeys,
            DocumentId documentId) {
        Set<RouteKey> existing = targetKeys.remove(documentId);
        if (existing == null) {
            return;
        }
        for (RouteKey key : existing) {
            List<RouteRow> targets = targetRows.get(key);
            if (targets == null) {
                continue;
            }
            targets.removeIf(row -> row.documentId().equals(documentId));
            if (targets.isEmpty()) {
                targetRows.remove(key);
            }
        }
    }

    private static Set<RouteKey> changedKeys(
            Map<RouteKey, List<RouteRow>> before,
            Map<RouteKey, List<RouteRow>> after) {
        Set<RouteKey> candidates = new LinkedHashSet<>(before.keySet());
        candidates.addAll(after.keySet());
        candidates.removeIf(key -> before.getOrDefault(key, List.of())
                .equals(after.getOrDefault(key, List.of())));
        return candidates;
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
                metrics.increment("routing.rowsInspected");
                if (row.accepts(entry)
                        && target.accepts(
                        row.documentId(), sessionResolver)) {
                    selected.add(row.documentId());
                }
            }
        }
        List<DocumentId> targets = selected.stream().sorted(
                EmbeddingBinding.DOCUMENT_ORDER).toList();
        metrics.add("routing.targetsSelected", targets.size());
        metrics.addNanos("process.routeLookup", System.nanoTime() - started);
        return Collections.unmodifiableList(new ArrayList<>(targets));
    }

    /**
     * Freezes the exact Root deliveries selected from one route generation.
     *
     * <p>The returned evidence is sufficient to construct the Contracts
     * direct-delivery snapshot. It deliberately contains no containing
     * document or ambient scope. Non-Root legacy operations remain routable
     * through {@link #route(TimelineEntry)} but are excluded from the 1.0
     * closure profile.</p>
     */
    public synchronized FrozenDirectDeliverySelection selectDirectDeliveries(
            TimelineEntry entry) {
        Objects.requireNonNull(entry, "entry");
        long started = System.nanoTime();
        metrics.increment("routing.lookups");
        DocumentTarget target = DocumentTarget.from(entry);
        Map<DirectDeliveryKey, RouteRow> selected = new LinkedHashMap<>();
        for (String eventKey
                : TimelineProviderSupport.exactTimelineEntryEventKeys(
                entry.timeline().timelineId(), entry.timeline().actorId())) {
            RouteKey routeKey = new RouteKey(
                    entry.operation(), entry.channel(), eventKey);
            for (RouteRow row : rows.getOrDefault(routeKey, List.of())) {
                metrics.increment("routing.rowsInspected");
                if (row.accepts(entry)
                        && target.accepts(
                        row.documentId(), sessionResolver)) {
                    DirectDeliveryKey key = new DirectDeliveryKey(
                            row.documentId(),
                            row.scopePath(),
                            routeKey.channel(),
                            TimelineProviderSupport
                                    .operationRequestLogicalDeliveryKey(
                                            routeKey.operation(),
                                            routeKey.channel()));
                    selected.putIfAbsent(key, row);
                }
            }
        }
        List<Map.Entry<DirectDeliveryKey, RouteRow>> canonical =
                new ArrayList<>(selected.entrySet());
        canonical.sort((left, right) -> {
            int comparison = RouteRow.DELIVERY_ORDER.compare(
                    left.getValue(), right.getValue());
            return comparison != 0
                    ? comparison
                    : DirectDeliveryKey.ORDER.compare(
                            left.getKey(), right.getKey());
        });
        List<FrozenDirectDelivery> deliveries = new ArrayList<>();
        long rawOccurrenceOrder = 0L;
        for (Map.Entry<DirectDeliveryKey, RouteRow> selectedEntry : canonical) {
            DirectDeliveryKey key = selectedEntry.getKey();
            if (!"/".equals(key.scopePath())) {
                continue;
            }
            deliveries.add(new FrozenDirectDelivery(
                    key.documentId(),
                    key.channelKey(),
                    key.logicalDeliveryKey(),
                    rawOccurrenceOrder));
            rawOccurrenceOrder = Math.addExact(rawOccurrenceOrder, 1L);
        }
        FrozenDirectDeliverySelection result =
                new FrozenDirectDeliverySelection(generation, deliveries);
        metrics.add("routing.targetsSelected", result.documentIds().size());
        metrics.add("routing.closureDeliveriesSelected", deliveries.size());
        metrics.addNanos("process.routeLookup", System.nanoTime() - started);
        return result;
    }

    /**
     * Revalidates one cohort's frozen direct rows after an unrelated route
     * publication. Original raw occurrence orders remain part of the frozen
     * Contracts input and are deliberately not renumbered here.
     */
    synchronized boolean revalidatesDirectDeliveries(
            TimelineEntry entry,
            List<DirectLogicalDelivery> frozenDeliveries) {
        List<DirectLogicalDelivery> expected = List.copyOf(
                Objects.requireNonNull(frozenDeliveries, "frozenDeliveries"));
        Set<DocumentId> documents = expected.stream()
                .map(delivery -> DocumentId.of(
                        delivery.targetDocumentId().value()))
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        Set<DeliveryProjection> expectedRows = expected.stream()
                .map(DeliveryProjection::from)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        Set<DeliveryProjection> actualRows = selectDirectDeliveries(entry)
                .deliveries().stream()
                .filter(delivery -> documents.contains(delivery.documentId()))
                .map(DeliveryProjection::from)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        return expectedRows.size() == expected.size()
                && expectedRows.equals(actualRows);
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

    /** One exact Root delivery selected from a frozen route generation. */
    record FrozenDirectDelivery(
            DocumentId documentId,
            String channelKey,
            String logicalDeliveryKey,
            long rawOccurrenceOrder) {
        FrozenDirectDelivery {
            documentId = Objects.requireNonNull(documentId, "documentId");
            channelKey = requireText(channelKey, "channelKey");
            logicalDeliveryKey = requireText(
                    logicalDeliveryKey, "logicalDeliveryKey");
            if (rawOccurrenceOrder < 0L) {
                throw new IllegalArgumentException(
                        "rawOccurrenceOrder must be non-negative");
            }
        }

        DirectLogicalDelivery toContractsEvidence() {
            return new DirectLogicalDelivery(
                    ManagedScopeKey.root(
                            new blue.language.processor.closure.DocumentId(
                                    documentId.value())),
                    channelKey,
                    logicalDeliveryKey,
                    rawOccurrenceOrder);
        }
    }

    /** Exact Root deliveries and the route generation that selected them. */
    record FrozenDirectDeliverySelection(
            long routeGeneration,
            List<FrozenDirectDelivery> deliveries) {
        FrozenDirectDeliverySelection {
            if (routeGeneration < 0L) {
                throw new IllegalArgumentException(
                        "routeGeneration must be non-negative");
            }
            deliveries = List.copyOf(Objects.requireNonNull(
                    deliveries, "deliveries"));
            for (int index = 0; index < deliveries.size(); index++) {
                FrozenDirectDelivery delivery = Objects.requireNonNull(
                        deliveries.get(index), "delivery");
                if (delivery.rawOccurrenceOrder() != index) {
                    throw new IllegalArgumentException(
                            "Direct delivery order is not contiguous");
                }
            }
        }

        List<DocumentId> documentIds() {
            return deliveries.stream()
                    .map(FrozenDirectDelivery::documentId)
                    .distinct()
                    .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                    .toList();
        }

        List<DirectLogicalDelivery> contractsEvidence() {
            return deliveries.stream()
                    .map(FrozenDirectDelivery::toContractsEvidence)
                    .toList();
        }
    }

    record Replacement(
            DocumentId documentId,
            RoutingSurface surface,
            List<SubscriptionDelta.Entry> activeSubscriptions) {
        Replacement {
            documentId = Objects.requireNonNull(documentId, "documentId");
            surface = Objects.requireNonNull(surface, "surface");
            activeSubscriptions = List.copyOf(Objects.requireNonNull(
                    activeSubscriptions, "activeSubscriptions"));
        }
    }

    final class PreparedReplacement {
        private final OperationRouteIndex owner;
        private final long expectedGeneration;
        private final long resultingGeneration;
        private final Map<RouteKey, List<RouteRow>> rows;
        private final Map<DocumentId, Set<RouteKey>> keysByDocument;
        private final long changedKeys;
        private final long retainedKeys;
        private final long insertedRows;
        private boolean published;

        private PreparedReplacement(
                long expectedGeneration,
                long resultingGeneration,
                Map<RouteKey, List<RouteRow>> rows,
                Map<DocumentId, Set<RouteKey>> keysByDocument,
                long changedKeys,
                long retainedKeys,
                long insertedRows) {
            this.owner = OperationRouteIndex.this;
            this.expectedGeneration = expectedGeneration;
            this.resultingGeneration = resultingGeneration;
            this.rows = copyRows(rows);
            this.keysByDocument = copyKeys(keysByDocument);
            this.changedKeys = changedKeys;
            this.retainedKeys = retainedKeys;
            this.insertedRows = insertedRows;
        }

        long expectedGeneration() {
            return expectedGeneration;
        }

        long resultingGeneration() {
            return resultingGeneration;
        }

        void publish() {
            owner.publish(this);
        }
    }

    private record DeliveryProjection(
            DocumentId documentId,
            String channelKey,
            String logicalDeliveryKey) {
        static DeliveryProjection from(FrozenDirectDelivery delivery) {
            return new DeliveryProjection(
                    delivery.documentId(),
                    delivery.channelKey(),
                    delivery.logicalDeliveryKey());
        }

        static DeliveryProjection from(DirectLogicalDelivery delivery) {
            return new DeliveryProjection(
                    DocumentId.of(delivery.targetDocumentId().value()),
                    delivery.channelKey(),
                    delivery.logicalDeliveryKey());
        }
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

    private record DirectDeliveryKey(
            DocumentId documentId,
            String scopePath,
            String channelKey,
            String logicalDeliveryKey) {
        private static final Comparator<DirectDeliveryKey> ORDER = Comparator
                .comparing(DirectDeliveryKey::documentId,
                        EmbeddingBinding.DOCUMENT_ORDER)
                .thenComparing(DirectDeliveryKey::scopePath,
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(DirectDeliveryKey::channelKey,
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(DirectDeliveryKey::logicalDeliveryKey,
                        EmbeddingBinding.TEXT_ORDER);

        private DirectDeliveryKey {
            documentId = Objects.requireNonNull(documentId, "documentId");
            scopePath = requireText(scopePath, "scopePath");
            channelKey = requireText(channelKey, "channelKey");
            logicalDeliveryKey = requireText(
                    logicalDeliveryKey, "logicalDeliveryKey");
        }
    }

    private record RouteRow(
            DocumentId documentId,
            String scopePath,
            int channelOrder,
            ExternalOrderKey startAfter,
            List<RoutingSurface.SourceAddress> sources) {
        private static final Comparator<RouteRow> ORDER = Comparator
                .comparing(RouteRow::documentId, EmbeddingBinding.DOCUMENT_ORDER)
                .thenComparing(RouteRow::scopePath, EmbeddingBinding.TEXT_ORDER)
                .thenComparingInt(RouteRow::channelOrder)
                .thenComparing(RouteRow::startAfter,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RouteRow::sources, RoutingSurface::compareSources);
        private static final Comparator<RouteRow> DELIVERY_ORDER = Comparator
                .comparingInt(RouteRow::channelOrder)
                .thenComparing(RouteRow::documentId,
                        EmbeddingBinding.DOCUMENT_ORDER)
                .thenComparing(RouteRow::scopePath, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(RouteRow::startAfter,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RouteRow::sources,
                        RoutingSurface::compareSources);

        private RouteRow {
            documentId = Objects.requireNonNull(documentId, "documentId");
            scopePath = requireText(scopePath, "scopePath");
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
