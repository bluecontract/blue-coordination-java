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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Direct operation-aware index from exact external headers to managed documents.
 * Large request content is not part of the lookup key.
 */
final class OperationRouteIndex {
    static final String DIRECT_ROUTE_SNAPSHOTS = "routing.directSnapshots";
    static final String DIRECT_ROUTE_REVALIDATION_SNAPSHOTS =
            "routing.directRevalidationSnapshots";

    private PersistentOrderedMap<RouteKey, List<RouteRow>> rows =
            PersistentOrderedMap.empty(RouteKey.ORDER);
    private PersistentOrderedMap<DocumentId, Set<RouteKey>> keysByDocument =
            PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER);
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

        PersistentOrderedMap<RouteKey, List<RouteRow>> preparedRows = rows;
        PersistentOrderedMap<DocumentId, Set<RouteKey>> preparedKeys =
                keysByDocument;
        long comparisons = 0L;
        long copiedNodes = 0L;
        long retainedKeys = 0L;
        Set<RouteKey> changedRouteKeys = new LinkedHashSet<>();
        List<OperationRouteChange> operationRouteChanges = new ArrayList<>();
        for (Replacement replacement : canonical) {
            DocumentId documentId = replacement.documentId();
            Map<RouteKey, List<RouteRow>> inserted = compiled.get(documentId);
            PersistentOrderedMap.ReadResult<Set<RouteKey>> existingRead =
                    preparedKeys.read(documentId);
            comparisons = Math.addExact(
                    comparisons, existingRead.comparisons());
            Set<RouteKey> existing = existingRead.value() == null
                    ? Set.of() : existingRead.value();
            Set<RouteKey> candidates = new LinkedHashSet<>(
                    existing);
            candidates.addAll(inserted.keySet());
            OperationRouteProjection beforeOperationRoutes =
                    operationRoutes(documentId, existing, preparedRows);
            comparisons = Math.addExact(
                    comparisons, beforeOperationRoutes.comparisons());
            List<OperationRouteState> afterOperationRoutes =
                    operationRoutes(documentId, inserted);
            operationRouteChanges.addAll(operationRouteChanges(
                    documentId,
                    beforeOperationRoutes.routes(),
                    afterOperationRoutes));
            boolean documentChanged = false;
            for (RouteKey key : candidates) {
                PersistentOrderedMap.ReadResult<List<RouteRow>> currentRead =
                        preparedRows.read(key);
                comparisons = Math.addExact(
                        comparisons, currentRead.comparisons());
                List<RouteRow> currentRows = currentRead.value() == null
                        ? List.of() : currentRead.value();
                List<RouteRow> current = currentRows.stream()
                        .filter(row -> row.documentId().equals(documentId))
                        .toList();
                List<RouteRow> replacementRows = inserted.getOrDefault(
                        key, List.of());
                if (current.equals(replacementRows)) {
                    retainedKeys = Math.addExact(retainedKeys, 1L);
                } else {
                    documentChanged = true;
                    changedRouteKeys.add(key);
                }
            }
            if (!documentChanged) {
                continue;
            }
            for (RouteKey key : existing) {
                PersistentOrderedMap.ReadResult<List<RouteRow>> currentRead =
                        preparedRows.read(key);
                comparisons = Math.addExact(
                        comparisons, currentRead.comparisons());
                if (currentRead.value() == null) {
                    continue;
                }
                List<RouteRow> retained = currentRead.value().stream()
                        .filter(row -> !row.documentId().equals(documentId))
                        .toList();
                PersistentOrderedMap.Mutation<RouteKey, List<RouteRow>>
                        mutation = retained.isEmpty()
                                ? preparedRows.remove(key)
                                : preparedRows.put(key, retained);
                preparedRows = mutation.map();
                comparisons = Math.addExact(
                        comparisons, mutation.comparisons());
                copiedNodes = Math.addExact(
                        copiedNodes, mutation.copiedNodes());
            }
            PersistentOrderedMap.Mutation<DocumentId, Set<RouteKey>>
                    removedKeys = preparedKeys.remove(documentId);
            preparedKeys = removedKeys.map();
            comparisons = Math.addExact(
                    comparisons, removedKeys.comparisons());
            copiedNodes = Math.addExact(
                    copiedNodes, removedKeys.copiedNodes());
            for (Map.Entry<RouteKey, List<RouteRow>> entry
                    : inserted.entrySet()) {
                PersistentOrderedMap.ReadResult<List<RouteRow>> currentRead =
                        preparedRows.read(entry.getKey());
                comparisons = Math.addExact(
                        comparisons, currentRead.comparisons());
                List<RouteRow> targets = new ArrayList<>(
                        currentRead.value() == null
                                ? List.of() : currentRead.value());
                targets.addAll(entry.getValue());
                targets.sort(RouteRow.ORDER);
                PersistentOrderedMap.Mutation<RouteKey, List<RouteRow>>
                        mutation = preparedRows.put(
                                entry.getKey(), List.copyOf(targets));
                preparedRows = mutation.map();
                comparisons = Math.addExact(
                        comparisons, mutation.comparisons());
                copiedNodes = Math.addExact(
                        copiedNodes, mutation.copiedNodes());
            }
            if (!inserted.isEmpty()) {
                Set<RouteKey> insertedKeys = Collections.unmodifiableSet(
                        new LinkedHashSet<>(inserted.keySet()));
                PersistentOrderedMap.Mutation<DocumentId, Set<RouteKey>>
                        mutation = preparedKeys.put(documentId, insertedKeys);
                preparedKeys = mutation.map();
                comparisons = Math.addExact(
                        comparisons, mutation.comparisons());
                copiedNodes = Math.addExact(
                        copiedNodes, mutation.copiedNodes());
            }
        }
        boolean changed = !changedRouteKeys.isEmpty();
        long resultingGeneration = changed
                ? Math.addExact(generation, 1L) : generation;
        long insertedRows = compiled.values().stream()
                .flatMap(value -> value.values().stream())
                .mapToLong(List::size)
                .sum();
        return new PreparedReplacement(
                generation,
                resultingGeneration,
                preparedRows,
                preparedKeys,
                changedRouteKeys.size(),
                retainedKeys,
                insertedRows,
                comparisons,
                copiedNodes,
                operationRouteChanges);
    }

    private static OperationRouteProjection operationRoutes(
            DocumentId documentId,
            Set<RouteKey> routeKeys,
            PersistentOrderedMap<RouteKey, List<RouteRow>> sourceRows) {
        Map<RouteKey, List<RouteRow>> selected = new LinkedHashMap<>();
        long comparisons = 0L;
        for (RouteKey key : routeKeys.stream().sorted(RouteKey.ORDER)
                .toList()) {
            PersistentOrderedMap.ReadResult<List<RouteRow>> read =
                    sourceRows.read(key);
            comparisons = Math.addExact(comparisons, read.comparisons());
            List<RouteRow> value = read.value();
            if (value != null) {
                selected.put(key, value);
            }
        }
        return new OperationRouteProjection(
                operationRoutes(documentId, selected), comparisons);
    }

    private static List<OperationRouteState> operationRoutes(
            DocumentId documentId,
            Map<RouteKey, List<RouteRow>> sourceRows) {
        Map<OperationRouteIdentity, OperationRouteState> canonical =
                new java.util.TreeMap<>(OperationRouteIdentity.ORDER);
        sourceRows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(RouteKey.ORDER))
                .forEach(entry -> entry.getValue().stream()
                        .filter(row -> row.documentId().equals(documentId))
                        .forEach(row -> {
                            OperationRouteState state =
                                    new OperationRouteState(
                                            row.scopePath(),
                                            entry.getKey().operation(),
                                            entry.getKey().channel(),
                                            row.sources());
                            OperationRouteIdentity identity =
                                    OperationRouteIdentity.from(state);
                            OperationRouteState previous = canonical.putIfAbsent(
                                    identity, state);
                            if (previous != null && !previous.equals(state)) {
                                throw new IllegalStateException(
                                        "One operation route identity has "
                                                + "conflicting compiled rows "
                                                + documentId + " " + identity);
                            }
                        }));
        return List.copyOf(canonical.values());
    }

    private static List<OperationRouteChange> operationRouteChanges(
            DocumentId documentId,
            List<OperationRouteState> before,
            List<OperationRouteState> after) {
        Map<OperationRouteIdentity, OperationRouteState> beforeByIdentity =
                operationRoutesByIdentity(before);
        Map<OperationRouteIdentity, OperationRouteState> afterByIdentity =
                operationRoutesByIdentity(after);
        Set<OperationRouteIdentity> identities = new java.util.TreeSet<>(
                OperationRouteIdentity.ORDER);
        identities.addAll(beforeByIdentity.keySet());
        identities.addAll(afterByIdentity.keySet());
        ArrayList<OperationRouteChange> changes = new ArrayList<>();
        for (OperationRouteIdentity identity : identities) {
            OperationRouteState beforeState = beforeByIdentity.get(identity);
            OperationRouteState afterState = afterByIdentity.get(identity);
            if (Objects.equals(beforeState, afterState)) {
                continue;
            }
            OperationRouteChangeKind kind = beforeState == null
                    ? OperationRouteChangeKind.ADD
                    : afterState == null
                    ? OperationRouteChangeKind.REMOVE
                    : OperationRouteChangeKind.REPLACE;
            changes.add(new OperationRouteChange(
                    kind,
                    documentId,
                    Optional.ofNullable(beforeState),
                    Optional.ofNullable(afterState)));
        }
        return List.copyOf(changes);
    }

    private static Map<OperationRouteIdentity, OperationRouteState>
            operationRoutesByIdentity(List<OperationRouteState> routes) {
        Map<OperationRouteIdentity, OperationRouteState> result =
                new java.util.TreeMap<>(OperationRouteIdentity.ORDER);
        for (OperationRouteState route : routes) {
            OperationRouteState previous = result.putIfAbsent(
                    OperationRouteIdentity.from(route), route);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate operation route identity " + route);
            }
        }
        return result;
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
        rows = replacement.rows;
        keysByDocument = replacement.keysByDocument;
        generation = replacement.resultingGeneration;
        replacement.published = true;
        metrics.add("routing.routeKeysRetained", replacement.retainedKeys);
        metrics.add("routing.routeIndexComparisons", replacement.comparisons);
        metrics.add("routing.routeIndexNodesCopied", replacement.copiedNodes);
        if (replacement.expectedGeneration
                == replacement.resultingGeneration) {
            metrics.increment("routing.surfacePublicationsSkipped");
            return;
        }
        metrics.increment("routing.surfaceCompilations");
        metrics.add("routing.routeKeysUpdated", replacement.changedKeys);
        metrics.add("routing.rowsCompiled", replacement.insertedRows);
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
            List<RouteRow> routeRows = rows.get(new RouteKey(
                    entry.operation(), entry.channel(), eventKey));
            for (RouteRow row : routeRows == null ? List.<RouteRow>of()
                    : routeRows) {
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
        return selectDirectDeliveries(entry, false);
    }

    private FrozenDirectDeliverySelection selectDirectDeliveries(
            TimelineEntry entry,
            boolean revalidation) {
        Objects.requireNonNull(entry, "entry");
        long started = System.nanoTime();
        metrics.increment("routing.lookups");
        metrics.increment(revalidation
                ? DIRECT_ROUTE_REVALIDATION_SNAPSHOTS
                : DIRECT_ROUTE_SNAPSHOTS);
        DocumentTarget target = DocumentTarget.from(entry);
        Map<DirectDeliveryKey, RouteRow> selected = new LinkedHashMap<>();
        for (String eventKey
                : TimelineProviderSupport.exactTimelineEntryEventKeys(
                entry.timeline().timelineId(), entry.timeline().actorId())) {
            RouteKey routeKey = new RouteKey(
                    entry.operation(), entry.channel(), eventKey);
            List<RouteRow> routeRows = rows.get(routeKey);
            for (RouteRow row : routeRows == null ? List.<RouteRow>of()
                    : routeRows) {
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
        Set<DeliveryProjection> actualRows = selectDirectDeliveries(
                entry, true)
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
        prepareReplacement(List.of(new Replacement(
                documentId, new RoutingSurface(List.of(), false), List.of())))
                .publish();
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
        rows = PersistentOrderedMap.empty(RouteKey.ORDER);
        keysByDocument = PersistentOrderedMap.empty(
                EmbeddingBinding.DOCUMENT_ORDER);
        generation = nextGeneration;
    }

    synchronized RouteStructureSnapshot routeStructureSnapshotForTesting() {
        return new RouteStructureSnapshot(rows, keysByDocument);
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
        private final PersistentOrderedMap<RouteKey, List<RouteRow>> rows;
        private final PersistentOrderedMap<DocumentId, Set<RouteKey>>
                keysByDocument;
        private final long changedKeys;
        private final long retainedKeys;
        private final long insertedRows;
        private final long comparisons;
        private final long copiedNodes;
        private final List<OperationRouteChange> operationRouteChanges;
        private boolean published;

        private PreparedReplacement(
                long expectedGeneration,
                long resultingGeneration,
                PersistentOrderedMap<RouteKey, List<RouteRow>> rows,
                PersistentOrderedMap<DocumentId, Set<RouteKey>>
                        keysByDocument,
                long changedKeys,
                long retainedKeys,
                long insertedRows,
                long comparisons,
                long copiedNodes,
                List<OperationRouteChange> operationRouteChanges) {
            this.owner = OperationRouteIndex.this;
            this.expectedGeneration = expectedGeneration;
            this.resultingGeneration = resultingGeneration;
            this.rows = Objects.requireNonNull(rows, "rows");
            this.keysByDocument = Objects.requireNonNull(
                    keysByDocument, "keysByDocument");
            this.changedKeys = changedKeys;
            this.retainedKeys = retainedKeys;
            this.insertedRows = insertedRows;
            this.comparisons = comparisons;
            this.copiedNodes = copiedNodes;
            this.operationRouteChanges = List.copyOf(
                    Objects.requireNonNull(
                            operationRouteChanges,
                            "operationRouteChanges"));
        }

        long expectedGeneration() {
            return expectedGeneration;
        }

        long resultingGeneration() {
            return resultingGeneration;
        }

        /** Exact logical route delta compiled by this prepared replacement. */
        List<OperationRouteChange> operationRouteChanges() {
            return operationRouteChanges;
        }

        void publish() {
            owner.publish(this);
        }
    }

    /** Closed logical operation-route transition kind. */
    enum OperationRouteChangeKind {
        ADD,
        REMOVE,
        REPLACE
    }

    /** Exact externally routable operation state compiled for one document. */
    record OperationRouteState(
            String scopePath,
            String operation,
            String channel,
            List<RoutingSurface.SourceAddress> acceptedSources) {
        OperationRouteState {
            scopePath = requireText(scopePath, "scopePath");
            if (!scopePath.startsWith("/")) {
                throw new IllegalArgumentException(
                        "scopePath must be absolute");
            }
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
            acceptedSources = List.copyOf(Objects.requireNonNull(
                    acceptedSources, "acceptedSources"));
        }
    }

    /** One exact logical operation-route change from prepared reconciliation. */
    record OperationRouteChange(
            OperationRouteChangeKind kind,
            DocumentId documentId,
            Optional<OperationRouteState> before,
            Optional<OperationRouteState> after) {
        OperationRouteChange {
            kind = Objects.requireNonNull(kind, "kind");
            documentId = Objects.requireNonNull(documentId, "documentId");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            if ((kind == OperationRouteChangeKind.ADD
                    && (before.isPresent() || after.isEmpty()))
                    || (kind == OperationRouteChangeKind.REMOVE
                    && (before.isEmpty() || after.isPresent()))
                    || (kind == OperationRouteChangeKind.REPLACE
                    && (before.isEmpty() || after.isEmpty()))) {
                throw new IllegalArgumentException(
                        "Operation route change kind disagrees with its sides");
            }
        }
    }

    private record OperationRouteIdentity(
            String scopePath,
            String operation,
            String channel) {
        private static final Comparator<OperationRouteIdentity> ORDER =
                Comparator.comparing(
                                OperationRouteIdentity::scopePath,
                                EmbeddingBinding.TEXT_ORDER)
                        .thenComparing(
                                OperationRouteIdentity::operation,
                                EmbeddingBinding.TEXT_ORDER)
                        .thenComparing(
                                OperationRouteIdentity::channel,
                                EmbeddingBinding.TEXT_ORDER);

        private static OperationRouteIdentity from(
                OperationRouteState state) {
            return new OperationRouteIdentity(
                    state.scopePath(), state.operation(), state.channel());
        }

        private OperationRouteIdentity {
            scopePath = requireText(scopePath, "scopePath");
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
        }
    }

    private record OperationRouteProjection(
            List<OperationRouteState> routes,
            long comparisons) {
        private OperationRouteProjection {
            routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
            if (comparisons < 0L) {
                throw new IllegalArgumentException(
                        "comparisons must be non-negative");
            }
        }
    }

    record RouteStructureSnapshot(
            PersistentOrderedMap<RouteKey, List<RouteRow>> rows,
            PersistentOrderedMap<DocumentId, Set<RouteKey>> keysByDocument) {
        RouteStructureSnapshot {
            rows = Objects.requireNonNull(rows, "rows");
            keysByDocument = Objects.requireNonNull(
                    keysByDocument, "keysByDocument");
        }

        int sharedRouteNodes(RouteStructureSnapshot other) {
            return rows.sharedNodeCountForTesting(
                    Objects.requireNonNull(other, "other").rows);
        }

        int sharedDocumentNodes(RouteStructureSnapshot other) {
            return keysByDocument.sharedNodeCountForTesting(
                    Objects.requireNonNull(other, "other").keysByDocument);
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
        private static final Comparator<RouteKey> ORDER = Comparator
                .comparing(RouteKey::operation, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(RouteKey::channel, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(RouteKey::subscriptionKey,
                        EmbeddingBinding.TEXT_ORDER);

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
                    || node.getItems() != null
                    || node.getProperties() != null
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
                    ? session.currentRepresentation().blueId()
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
