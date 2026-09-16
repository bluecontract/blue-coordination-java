package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.PublicEventOccurrence;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Complete document-state assembly over independently selected physical roots.
 * The caller owns the coherent directory read and atomic publication. Selection
 * is an in-process argument bundle, not a serialized global root or a CAS token.
 */
final class StoredDocumentStore {
    enum Root {
        SESSIONS, GRAPH_GENERATIONS,
        LINEAGE_DOCUMENT, LINEAGE_AUTHORED, LINEAGE_INITIALIZED, LINEAGE_RETAINED, LINEAGE_CURRENT,
        OCCURRENCE_PATH, OCCURRENCE_ORDERED, OCCURRENCE_ACTIVE, OCCURRENCE_OCCURRENCE_ID,
        OCCURRENCE_BINDING_ID, OCCURRENCE_DOCUMENT, OCCURRENCE_SOURCE, OCCURRENCE_ACTIVE_SOURCE,
        TOPOLOGY_COMPONENT, TOPOLOGY_TARGET, TOPOLOGY_SOURCE, TOPOLOGY_JOIN_MEMBERS, TOPOLOGY_JOIN_ROOTS,
        COMPONENT_LINEAGE, COMPONENT_STATE, COMPONENT_DOCUMENT,
        SUBSCRIPTION_SLOT, SUBSCRIPTION_IDENTITY, SUBSCRIPTION_DOCUMENT, SUBSCRIPTION_DEMAND,
        OUTBOX, CHECKPOINTS, PUBLICATIONS, ADMISSIONS, CLOSURES,
        RECEIPT_DOCUMENT, RECEIPT_IDENTITY,
        PLAN_IDENTITY, PLAN_CONSUMER, PLAN_SOURCE, PLAN_OCCURRENCE, PLAN_BARRIER, PLAN_ACTIVE_SOURCE,
        BARRIERS, WORK_WORK, WORK_PENDING, WORK_APPLICATION, WORK_APPLICATION_BY_WORK, WORK_DUE
    }

    /** Original operational counters/flags, never recomputed from a partial selected history. */
    record Metadata(long occurrenceGeneration, long componentGeneration, boolean rootedViews,
            int lineageCopies, int graphComparisons, int graphCopies,
            int subscriptionComparisons, int subscriptionCopies, int subscriptionVisited,
            int receiptComparisons, int receiptCopies, int activeBarriers, int catchUpComparisons, int catchUpCopies,
            int planComparisons, int planCopies, int workComparisons, int workCopies) {
        Metadata {
            require(occurrenceGeneration >= 0 && componentGeneration >= 0, "Negative document-store generation");
            for (int counter : new int[] {lineageCopies, graphComparisons, graphCopies, subscriptionComparisons,
                    subscriptionCopies, subscriptionVisited, receiptComparisons, receiptCopies, activeBarriers,
                    catchUpComparisons, catchUpCopies, planComparisons, planCopies, workComparisons, workCopies})
                require(counter >= 0, "Negative document-store counter");
        }
        static Metadata from(InMemoryDocumentStore.StoreState state) {
            var g = state.graphGenerations().storedState(); var s = state.closureSubscriptions().storedIndexes();
            var r = state.managedEpochReceipts().storedState(); var c = state.catchUpPlans().storedState();
            var p = c.plans().storedIndexes(); var w = c.work().storedIndexes();
            return new Metadata(state.occurrenceInventoryGeneration(), state.componentIndexGeneration(),
                    state.componentIndex().storedIndexes().rootedViews(), state.lineageIndex().storedState().copiedNodes(),
                    g.comparisons(), g.copiedNodes(), s.comparisons(), s.copiedNodes(), s.visitedRows(),
                    r.comparisons(), r.copiedNodes(), c.activeBarriers(), c.comparisons(), c.copiedNodes(),
                    p.comparisons(), p.copiedNodes(), w.comparisons(), w.copiedNodes());
        }
    }

    static final class Selection {
        private final EnumMap<Root, byte[]> roots = new EnumMap<>(Root.class);
        private final Metadata metadata;
        Selection(Map<Root, byte[]> selected, Metadata metadata) {
            require(selected.keySet().equals(EnumSet.allOf(Root.class)), "Incomplete document-store selection");
            selected.forEach((key, bytes) -> roots.put(key, Objects.requireNonNull(bytes, "selected root").clone()));
            this.metadata = Objects.requireNonNull(metadata);
        }
        byte[] root(Root root) { return roots.get(Objects.requireNonNull(root)).clone(); }
        Metadata metadata() { return metadata; }
    }

    private final CoordinationImmutableObjectStore objects;
    private final PersistentMapStorage.Limits mapLimits;
    private final PersistentAppendLogStorage.Limits logLimits;
    private final DocumentSessionStorage.Limits sessionLimits;
    private final StoredDocumentIndexes documents;
    private final DocumentSessionStorage sessions;
    private final StoredOccurrenceIndexes occurrences;
    private final StoredTopologyIndexes topology;
    private final StoredComponentStateIndexes components;
    private final StoredSubscriptionIndexes subscriptions;
    private final StoredManagedEpochIndexes receipts;
    private final RootedStorageCache decodedCache;
    private final StoredCatchUpPlanIndexes plans;
    private final StoredCatchUpWorkIndexes work;

    StoredDocumentStore(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits mapLimits,
            PersistentAppendLogStorage.Limits logLimits, DocumentSessionStorage.Limits sessionLimits) {
        this(objects, mapLimits, logLimits, sessionLimits, null);
    }

    StoredDocumentStore(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits mapLimits,
            PersistentAppendLogStorage.Limits logLimits, DocumentSessionStorage.Limits sessionLimits,
            RootedStorageCache decodedCache) {
        this.objects = Objects.requireNonNull(objects); this.mapLimits = Objects.requireNonNull(mapLimits);
        this.logLimits = Objects.requireNonNull(logLimits); this.sessionLimits = Objects.requireNonNull(sessionLimits);
        this.decodedCache = decodedCache;
        documents = new StoredDocumentIndexes(objects, mapLimits, sessionLimits, decodedCache);
        sessions = new DocumentSessionStorage(objects, sessionLimits, decodedCache);
        occurrences = new StoredOccurrenceIndexes(objects, mapLimits); topology = new StoredTopologyIndexes(objects, mapLimits);
        components = new StoredComponentStateIndexes(objects, mapLimits, sessionLimits.maximumDepth());
        subscriptions = new StoredSubscriptionIndexes(objects, mapLimits); receipts = new StoredManagedEpochIndexes(objects, mapLimits, decodedCache);
        plans = new StoredCatchUpPlanIndexes(objects, mapLimits);
        work = new StoredCatchUpWorkIndexes(objects, mapLimits, sessionLimits.maximumDepth(), decodedCache);
    }

    /** Explicit resident partition conversion only; cold open never invokes this walk. */
    Selection retainPartition(InMemoryDocumentStore.StoreState state) {
        return physical(() -> {
            try (var scope = sessions.openScope(); var results = new StoredResultRows(objects, sessionLimits, decodedCache);
                    var publication = publication(scope, results)) {
                // These original result associations must exist before any new observed log row is encoded.
                var admissions = publication.retainAdmissions(state.admissionReceiptIndex());
                var closures = publication.retainClosures(state.closurePublicationReceiptIndex());
                return retained(state, documents.retainSessionPartition(state.sessionIndex()),
                        publication.retainGeneric(state.publicationReceiptIndex()), admissions, closures, results);
            }
        });
    }

    Opened open(Selection selection, int maximumSelectedSessions, int maximumSelectedIndexMaps) {
        return physical(() -> new Opened(selection, maximumSelectedSessions, maximumSelectedIndexMaps));
    }

    final class Opened implements AutoCloseable {
        private final StoredDocumentIndexes.WorkingSessions selectedSessions;
        private final StoredResultRows results;
        private final StoredPublicationIndexes publication;
        private final PersistentOrderedMap.ValueProjection<String, ContractsClosureAdmissionReceipt, ContractsClosureAdmissionReceipt> admissionValues;
        private final PersistentOrderedMap.ValueProjection<String, ContractsClosurePublicationReceipt, ContractsClosurePublicationReceipt> closureValues;
        private final InMemoryDocumentStore.StoreState initial;
        private final StoredDocumentReadChecks checks;
        private boolean closed;

        private Opened(Selection selected, int maximumSelectedSessions, int maximumSelectedIndexMaps) {
            var m = selected.metadata();
            var lineages = documents.openLineages(family(selected, "LINEAGE_"), m.lineageCopies());
            var generations = documents.openGenerations(selected.root(Root.GRAPH_GENERATIONS), m.graphComparisons(), m.graphCopies());
            var occurrence = occurrences.open(family(selected, "OCCURRENCE_"));
            var graph = topology.open(family(selected, "TOPOLOGY_"), m.rootedViews());
            var component = components.open(family(selected, "COMPONENT_"));
            var subscription = subscriptions.open(family(selected, "SUBSCRIPTION_"), m.subscriptionComparisons(), m.subscriptionCopies(), m.subscriptionVisited());
            var receipt = receipts.open(family(selected, "RECEIPT_"), m.receiptComparisons(), m.receiptCopies());
            var plan = plans.open(family(selected, "PLAN_"), m.planComparisons(), m.planCopies());
            var barriers = plans.openBarriers(selected.root(Root.BARRIERS));
            var works = work.open(family(selected, "WORK_"), m.workComparisons(), m.workCopies());
            var catchUp = CatchUpPlanStore.restoreStored(new CatchUpPlanStore.StoredState(plan, barriers, works,
                    m.activeBarriers(), m.catchUpComparisons(), m.catchUpCopies()));
            selectedSessions = documents.openWorkingSessions(documents.openSessions(selected.root(Root.SESSIONS)),
                    lineages, generations, maximumSelectedSessions,
                    (id, original) -> topology.requireJoinRoot(graph, id, original.retainedView()));
            results = new StoredResultRows(objects, sessionLimits, decodedCache);
            StoredDocumentReadChecks openingChecks = null;
            StoredPublicationIndexes openingPublication = null;
            try {
                publication = publication(selectedSessions.viewScope(), results);
                openingPublication = publication;
                var generic = publication.openGeneric(selected.root(Root.PUBLICATIONS));
                var admissions = publication.openAdmissions(selected.root(Root.ADMISSIONS));
                var closures = publication.openClosures(selected.root(Root.CLOSURES));
                admissionValues = admissions.projectValues((key, row) -> {
                    StoredPublicationIndexes.checkedAdmission(key, row, generic, closures);
                    InMemoryDocumentStore.StoreState.requireRetainedResult(row.documentIds(), row.attempt().processResult(),
                            new PersistentMapView<>(selectedSessions.open()), "Stored admission receipt"); return row;
                });
                closureValues = closures.projectValues((key, row) -> {
                    StoredPublicationIndexes.checkedClosure(key, row, generic, admissions);
                    InMemoryDocumentStore.StoreState.requireRetainedClosureReceipt(row,
                            new PersistentMapView<>(selectedSessions.open()), "Stored closure receipt"); return row;
                });
                var raw = InMemoryDocumentStore.StoreState.trustedTransition(selectedSessions.open(), lineages,
                        occurrence, m.occurrenceGeneration(), graph, m.componentGeneration(), generations,
                        component, subscription, outbox(results).open(selected.root(Root.OUTBOX)).workingCopy(),
                        checkpoints(results).open(selected.root(Root.CHECKPOINTS)).workingCopy(), generic,
                        admissionValues.open(), closureValues.open(), receipt, catchUp);
                openingChecks = new StoredDocumentReadChecks(maximumSelectedIndexMaps, raw, occurrences, topology, components,
                        subscriptions, receipts, plans, work, selectedSessions::selected,
                        RootedEngineStorage.isControlledNamespace(objects));
                initial = openingChecks.open(); checks = openingChecks;
            } catch (RuntimeException failure) {
                if (openingChecks != null) openingChecks.close();
                if (openingPublication != null) openingPublication.close();
                results.close(); selectedSessions.close(); throw failure;
            }
        }
        InMemoryDocumentStore.StoreState state() { require(!closed, "Document-store scope is closed"); return initial; }
        DocumentSessionStorage.OpenScope viewScope() { require(!closed, "Document-store scope is closed"); return selectedSessions.viewScope(); }
        StoredPublicationReceiptReuse publicationReuse() { require(!closed, "Document-store scope is closed"); return publication.closureReuse(); }

        /** Prewrites only; failures leave the pinned selection and working mutable rows unchanged. */
        Selection stage(InMemoryDocumentStore.StoreState complete) {
            return physical(() -> {
                try {
                    require(!closed, "Document-store scope is closed");
                    // This must precede every prewrite, including receipt staging and removed/replaced sessions.
                    selectedSessions.preflightSelectedAuthority();
                    var admissions = admissionValues.stage(complete.admissionReceiptIndex(), (key, row) -> row);
                    var closures = closureValues.stage(complete.closurePublicationReceiptIndex(), (key, row) -> row);
                    return retained(checks.stage(complete), selectedSessions.stage(complete.sessionIndex()),
                            publication.retainGeneric(complete.publicationReceiptIndex()), admissions, closures, results);
                } finally {
                    // Receipt/check staging can fail before WorkingSessions.stage is reached.
                    selectedSessions.clearVerifiedSelections();
                }
            });
        }
        @Override public void close() { if (!closed) { closed = true; publication.close(); checks.close(); results.close(); selectedSessions.close(); } }
    }

    private Selection retained(InMemoryDocumentStore.StoreState s,
            PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> addresses,
            PersistentOrderedMap<String, Boolean> generic,
            PersistentOrderedMap<String, ContractsClosureAdmissionReceipt> admissions,
            PersistentOrderedMap<String, ContractsClosurePublicationReceipt> closures, StoredResultRows results) {
        var roots = new EnumMap<Root, byte[]>(Root.class);
        roots.put(Root.SESSIONS, addresses.storedRootDescriptor());
        var lineage = documents.retainLineagePartition(s.lineageIndex());
        putFamily(roots, "LINEAGE_", StoredDocumentIndexes.LineageRoot.values(), key -> documents.lineageRoot(lineage, key));
        roots.put(Root.GRAPH_GENERATIONS, documents.generationRoot(documents.retainGenerationPartition(s.graphGenerations())));
        var occurrence = occurrences.retainPartition(s.occurrenceInventory());
        putFamily(roots, "OCCURRENCE_", StoredOccurrenceIndexes.Root.values(), key -> occurrences.root(occurrence, key));
        var graph = topology.retainPartition(s.componentIndex());
        putFamily(roots, "TOPOLOGY_", StoredTopologyIndexes.Root.values(), key -> topology.root(graph, key));
        var component = components.retainPartition(s.componentStateInventory());
        putFamily(roots, "COMPONENT_", StoredComponentStateIndexes.Root.values(), key -> components.root(component, key));
        var subscription = subscriptions.retainPartition(s.closureSubscriptions());
        putFamily(roots, "SUBSCRIPTION_", StoredSubscriptionIndexes.Root.values(), key -> subscriptions.root(subscription, key));
        var receipt = receipts.retainPartition(s.managedEpochReceipts());
        putFamily(roots, "RECEIPT_", StoredManagedEpochIndexes.Root.values(), key -> receipts.root(receipt, key));
        var c = s.catchUpPlans().storedState(); var plan = plans.retainPartition(c.plans()); var works = work.retainPartition(c.work());
        putFamily(roots, "PLAN_", StoredCatchUpPlanIndexes.Root.values(), key -> plans.root(plan, key));
        roots.put(Root.BARRIERS, plans.retainBarriers(c.barriers()).storedRootDescriptor());
        putFamily(roots, "WORK_", StoredCatchUpWorkIndexes.Root.values(), key -> work.root(works, key));
        roots.put(Root.PUBLICATIONS, generic.storedRootDescriptor()); roots.put(Root.ADMISSIONS, admissions.storedRootDescriptor());
        roots.put(Root.CLOSURES, closures.storedRootDescriptor());
        roots.put(Root.OUTBOX, outbox(results).retain(s.outboxLog()).storedRootDescriptor());
        roots.put(Root.CHECKPOINTS, checkpoints(results).retain(s.checkpointEvidenceLog()).storedRootDescriptor());
        return new Selection(roots, Metadata.from(s));
    }

    private StoredPublicationIndexes publication(DocumentSessionStorage.OpenScope scope, StoredResultRows results) {
        return new StoredPublicationIndexes(objects, mapLimits, sessions, scope, results, sessionLimits.maximumDepth(), decodedCache);
    }
    private PersistentAppendLogStorage<PublicEventOccurrence> outbox(StoredResultRows results) {
        return new PersistentAppendLogStorage<>(objects, "blue-coordination/document-store/outbox/1", results.outboxCodec(), logLimits);
    }
    private PersistentAppendLogStorage<CheckpointWrite> checkpoints(StoredResultRows results) {
        return new PersistentAppendLogStorage<>(objects, "blue-coordination/document-store/checkpoints/1", results.checkpointCodec(), logLimits);
    }
    private static <E extends Enum<E>> Function<E, byte[]> family(Selection selection, String prefix) {
        return key -> selection.root(Root.valueOf(prefix + key.name()));
    }
    private static <E extends Enum<E>> void putFamily(Map<Root, byte[]> roots, String prefix, E[] values, Function<E, byte[]> descriptor) {
        for (E key : values) roots.put(Root.valueOf(prefix + key.name()), descriptor.apply(key));
    }
}
