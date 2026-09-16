package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Selected document/index rows only. Independent root descriptors are supplied by
 * the owning directory's pinned read view; this component neither publishes them
 * nor proves that a realm-wide AVL root permits independent publication.
 */
final class StoredDocumentIndexes {
    enum LineageRoot { DOCUMENT, AUTHORED, INITIALIZED, RETAINED, CURRENT }
    private final DocumentSessionStorage sessions;
    private final boolean controlledNamespace;
    private final StoreIndexCodecs.Binding<DocumentId, StoreIndexCodecs.SessionAddress> addresses;
    private final StoreIndexCodecs.Binding<DocumentId, Long> generations;
    private final StoreIndexCodecs.Binding<DocumentId, ManagedLineageIndex.Lineage> lineages;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<DocumentId, ManagedLineageIndex.Lineage>> authored;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<DocumentId, ManagedLineageIndex.Lineage>> initialized;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<DocumentId, ManagedLineageIndex.Lineage>> current;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<ManagedLineageIndex.RetainedKey, ManagedLineageIndex.RetainedState>> retained;

    StoredDocumentIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits mapLimits,
            DocumentSessionStorage.Limits sessionLimits) {
        this(objects, mapLimits, sessionLimits, null);
    }

    StoredDocumentIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits mapLimits,
            DocumentSessionStorage.Limits sessionLimits, RootedStorageCache cache) {
        controlledNamespace = RootedEngineStorage.isControlledNamespace(objects);
        sessions = new DocumentSessionStorage(objects, sessionLimits, cache);
        var codecs = new StoreIndexCodecs(objects, mapLimits);
        addresses = codecs.binding("sessions", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, codecs.sessions);
        generations = codecs.binding("graph-generations", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, codecs.generations);
        lineages = codecs.binding("lineage/documents", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, codecs.lineages);
        var lineageBucket = codecs.binding("lineage/document-bucket", EmbeddingBinding.DOCUMENT_ORDER, codecs.documents, codecs.lineages);
        var retainedBucket = codecs.binding("lineage/retained-bucket", ManagedLineageIndex.RETAINED_ORDER, codecs.retainedKeys, codecs.retainedStates);
        authored = codecs.binding("lineage/authored", EmbeddingBinding.TEXT_ORDER, codecs.text, lineageBucket.nested());
        initialized = codecs.binding("lineage/initialized", EmbeddingBinding.TEXT_ORDER, codecs.text, lineageBucket.nested());
        current = codecs.binding("lineage/current", EmbeddingBinding.TEXT_ORDER, codecs.text, lineageBucket.nested());
        retained = codecs.binding("lineage/retained", EmbeddingBinding.TEXT_ORDER, codecs.text, retainedBucket.nested());
    }

    StoreIndexCodecs.SessionAddress retainSession(DocumentSession session) {
        return new StoreIndexCodecs.SessionAddress(session.documentId(), sessions.retain(session));
    }

    PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> openSessions(byte[] selectedDescriptor) {
        return addresses.open(selectedDescriptor);
    }

    /** Explicit export of the supplied resident partition, never implicit cold-open enumeration. */
    PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> retainSessionPartition(
            PersistentOrderedMap<DocumentId, DocumentSession> partition) {
        return physical(() -> addresses.retain(partition.mapValuesPreservingShape(this::retainSession)));
    }

    ManagedLineageIndex retainLineagePartition(ManagedLineageIndex index) {
        return physical(() -> {
            var state = index.storedState();
            return ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(lineages.retain(state.documents()),
                    authored.retain(state.authored()), initialized.retain(state.initialized()), retained.retain(state.retained()),
                    current.retain(state.current()), state.copiedNodes()));
        });
    }

    /** Each family descriptor and its counters must come from the caller's one pinned directory view. */
    ManagedLineageIndex openLineages(Function<LineageRoot, byte[]> selectedRoots, int copiedNodes) {
        return physical(() -> ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(
                lineages.open(selectedRoots.apply(LineageRoot.DOCUMENT)), authored.open(selectedRoots.apply(LineageRoot.AUTHORED)),
                initialized.open(selectedRoots.apply(LineageRoot.INITIALIZED)), retained.open(selectedRoots.apply(LineageRoot.RETAINED)),
                current.open(selectedRoots.apply(LineageRoot.CURRENT)), copiedNodes)));
    }

    byte[] lineageRoot(ManagedLineageIndex index, LineageRoot selected) {
        var state = index.storedState();
        return switch (selected) {
            case DOCUMENT -> state.documents().storedRootDescriptor(); case AUTHORED -> state.authored().storedRootDescriptor();
            case INITIALIZED -> state.initialized().storedRootDescriptor(); case RETAINED -> state.retained().storedRootDescriptor();
            case CURRENT -> state.current().storedRootDescriptor();
        };
    }

    ClosureGraphGenerationInventory retainGenerationPartition(ClosureGraphGenerationInventory index) {
        return physical(() -> {
            var state = index.storedState();
            return ClosureGraphGenerationInventory.restoreStored(new ClosureGraphGenerationInventory.StoredState(
                    generations.retain(state.generations()), state.comparisons(), state.copiedNodes()));
        });
    }

    ClosureGraphGenerationInventory openGenerations(byte[] selectedDescriptor, int comparisons, int copiedNodes) {
        return physical(() -> ClosureGraphGenerationInventory.restoreStored(new ClosureGraphGenerationInventory.StoredState(
                generations.open(selectedDescriptor), comparisons, copiedNodes)));
    }

    byte[] generationRoot(ClosureGraphGenerationInventory index) { return index.storedState().generations().storedRootDescriptor(); }

    /** Complete selected bucket only; no other session bodies are needed for exact lineage resolution. */
    List<ManagedLineageIndex.Lineage> lineageMatches(ManagedLineageIndex index, LineageRoot kind, String blueId) {
        return physical(() -> {
            var state = index.storedState();
            var outer = switch (kind) {
                case AUTHORED -> state.authored(); case INITIALIZED -> state.initialized(); case CURRENT -> state.current();
                default -> throw new IllegalArgumentException("Not a lineage-match bucket");
            };
            var bucket = outer.get(blueId); if (bucket == null) return List.of();
            var result = new ArrayList<ManagedLineageIndex.Lineage>();
            for (var entry : bucket.entries()) {
                var row = entry.getValue();
                String identity = switch (kind) {
                    case AUTHORED -> row.authoredInitialBlueId(); case INITIALIZED -> row.initializedBlueId();
                    case CURRENT -> row.currentBlueId(); default -> throw new AssertionError();
                };
                require(entry.getKey().equals(row.documentId()) && blueId.equals(identity)
                        && sameLineage(row, state.documents().get(row.documentId()), controlledNamespace),
                        "Selected lineage bucket differs from its exact owner");
                result.add(row);
            }
            return List.copyOf(result);
        });
    }

    List<ManagedLineageIndex.RetainedState> retainedMatches(ManagedLineageIndex index, String blueId) {
        return physical(() -> {
            var state = index.storedState(); var bucket = state.retained().get(blueId);
            if (bucket == null) return List.of();
            var result = new ArrayList<ManagedLineageIndex.RetainedState>();
            for (var entry : bucket.entries()) {
                var row = entry.getValue(); var primary = state.documents().get(row.documentId());
                require(entry.getKey().equals(new ManagedLineageIndex.RetainedKey(row.documentId(), row.epoch()))
                        && blueId.equals(row.blueId()) && primary != null && row.epoch() < primary.retainedStates().size()
                        && row.equals(primary.retainedStates().get(Math.toIntExact(row.epoch()))),
                        "Selected retained-state bucket differs from its exact owner");
                result.add(row);
            }
            return List.copyOf(result);
        });
    }

    Owner openOwner(int maximumSelectedSessions) { return new Owner(maximumSelectedSessions); }

    WorkingSessions openWorkingSessions(PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> addresses,
            ManagedLineageIndex lineages, ClosureGraphGenerationInventory generations, int maximumSelectedSessions,
            java.util.function.BiConsumer<DocumentId, SelectedDocument> selectedCheck) {
        return new WorkingSessions(addresses, lineages, generations, maximumSelectedSessions, selectedCheck);
    }

    /** Same actual mutable instances during work; only complete final rows cross the storage boundary. */
    final class WorkingSessions implements AutoCloseable {
        private final Owner owner;
        private final PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> retainedAddresses;
        private final ManagedLineageIndex retainedLineages;
        private final ClosureGraphGenerationInventory retainedGenerations;
        private final java.util.function.BiConsumer<DocumentId, SelectedDocument> check;
        private final PersistentOrderedMap.ValueProjection<DocumentId, StoreIndexCodecs.SessionAddress, DocumentSession> projection;
        // Positive library-issued facts for this exact set of pinned indexes, bounded by Owner's session limit.
        private final Map<DocumentId, VerifiedSelection> verified = new HashMap<>();
        private boolean closed;

        private WorkingSessions(PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> addresses,
                ManagedLineageIndex lineages, ClosureGraphGenerationInventory generations, int maximumSelectedSessions,
                java.util.function.BiConsumer<DocumentId, SelectedDocument> selectedCheck) {
            owner = openOwner(maximumSelectedSessions); retainedAddresses = Objects.requireNonNull(addresses);
            retainedLineages = lineages; retainedGenerations = generations;
            check = Objects.requireNonNull(selectedCheck);
            projection = addresses.projectValues((id, address) -> selected(id, address).session(),
                    id -> owner.find(addresses, lineages, generations, id));
        }
        synchronized PersistentOrderedMap<DocumentId, DocumentSession> open() {
            require(!closed, "Working session scope is closed"); return projection.open();
        }
        DocumentSessionStorage.OpenScope viewScope() { return owner.viewScope(); }
        synchronized SelectedDocument selected(DocumentId id) {
            require(!closed, "Working session scope is closed");
            return selected(id, retainedAddresses.get(id));
        }
        private synchronized SelectedDocument selected(DocumentId id, StoreIndexCodecs.SessionAddress address) {
            require(!closed, "Working session scope is closed");
            require(address != null && id.equals(address.documentId()), "Selected address key differs from its owner");
            var prior = verified.get(id);
            if (prior != null) {
                require(prior.address().equals(address), "Selected retained address changed within one pinned working view");
                return prior.document();
            }
            var selected = owner.find(retainedAddresses, retainedLineages, retainedGenerations, id).orElseThrow();
            check.accept(id, selected);
            // Never grant a fact after a failed membership or owning-store crosslink check.
            verified.put(id, new VerifiedSelection(address, selected)); return selected;
        }

        /** Reauthenticate every selected original, even when the working map replaced or removed its row. */
        synchronized void preflightSelectedAuthority() {
            require(!closed, "Working session scope is closed");
            for (var original : owner.selectedDocuments()) {
                DocumentId id = original.session().documentId();
                var selected = owner.find(retainedAddresses, retainedLineages, retainedGenerations, id).orElseThrow();
                check.accept(id, selected);
            }
        }
        synchronized void clearVerifiedSelections() { verified.clear(); }

        synchronized PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> stage(
                PersistentOrderedMap<DocumentId, DocumentSession> current) {
            return physical(() -> {
                try {
                    preflightSelectedAuthority();
                    try (var retainedViews = owner.viewScope().openRetentionStage()) {
                        var finalRows = new IdentityHashMap<DocumentSession, StoreIndexCodecs.SessionAddress>();
                        var staging = current;
                        for (var selected : owner.selectedDocuments()) {
                            DocumentSession session = selected.session(); DocumentId id = session.documentId();
                            // Never resurrect an old selected instance after replacement or removal.
                            if (current.get(id) != session) continue;
                            check.accept(id, selected);
                            var retained = retainWorkingSession(session, retainedViews); finalRows.put(session, retained);
                            if (!retained.equals(retainedAddresses.get(id))) staging = staging.put(id, session).map();
                        }
                        return projection.stage(staging, (id, session) -> {
                            require(id.equals(session.documentId()), "Working session key differs from exact row owner");
                            return finalRows.computeIfAbsent(session, selected -> retainWorkingSession(selected, retainedViews));
                        });
                    }
                } finally {
                    clearVerifiedSelections();
                }
            });
        }
        private StoreIndexCodecs.SessionAddress retainWorkingSession(DocumentSession session,
                DocumentSessionStorage.OpenScope.RetentionStage retainedViews) {
            return new StoreIndexCodecs.SessionAddress(session.documentId(), retainedViews.retain(session));
        }
        @Override public synchronized void close() { closed = true; clearVerifiedSelections(); owner.close(); }
        private record VerifiedSelection(StoreIndexCodecs.SessionAddress address, SelectedDocument document) { }
    }

    /** Pinned retained authority is distinct from the owning attempt's mutable session. */
    record SelectedDocument(DocumentSession session, ManagedLineageIndex.Lineage retainedLineage, long retainedGraphGeneration,
            RootedDocumentView retainedView) { }

    /** No eviction of mutable selected sessions; retire this bounded owner with its pinned runtime view. */
    final class Owner implements AutoCloseable {
        private final DocumentSessionStorage.OpenScope views = sessions.openScope();
        private final Map<DocumentId, OwnedSession> selected = new HashMap<>();
        private final int maximumSessions;
        private boolean closed;
        Owner(int maximumSessions) {
            if (maximumSessions < 1) throw new IllegalArgumentException("Selected session bound must be positive");
            this.maximumSessions = maximumSessions;
        }
        synchronized DocumentSessionStorage.OpenScope viewScope() {
            require(!closed, "Selected document owner is closed"); return views;
        }
        synchronized List<SelectedDocument> selectedDocuments() {
            require(!closed, "Selected document owner is closed");
            return selected.values().stream().map(OwnedSession::document).toList();
        }
        synchronized Optional<SelectedDocument> find(PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> index,
                ManagedLineageIndex lineages, ClosureGraphGenerationInventory generations, DocumentId id) {
            return physical(() -> {
                require(!closed, "Selected document owner is closed");
                var address = index.get(id); var lineage = lineages.byDocumentId(id);
                Long generation = generations.storedState().generations().get(id);
                if (address == null) {
                    require(lineage == null && generation == null && !selected.containsKey(id),
                            "Absent session has retained or previously selected authority");
                    return Optional.empty();
                }
                require(address.documentId().equals(id) && lineage != null && generation != null,
                        "Selected session is missing or misbound in its exact indexes");
                OwnedSession prior = selected.get(id);
                if (prior != null) {
                    require(prior.address().equals(address.address())
                            && sameLineage(prior.document().retainedLineage(), lineage, controlledNamespace)
                            && prior.document().retainedGraphGeneration() == generation,
                            "Selected retained authority changed within one pinned owner");
                    requireLineageMembership(lineages, lineage, controlledNamespace);
                    return Optional.of(prior.document());
                }
                require(selected.size() < maximumSessions, "Selected session owner bound exceeded");
                DocumentSession session = views.open(address.documentId(), address.address());
                var sessionLineage = ManagedLineageIndex.Lineage.from(session);
                require(sameLineage(lineage, sessionLineage, controlledNamespace),
                        "Selected lineage differs from retained session history basis");
                if (session.rootedView() != null) require(session.rootedView().result().graphGeneration() == generation,
                        "Selected graph generation differs from its rooted publication");
                requireLineageMembership(lineages, lineage, controlledNamespace);
                var document = new SelectedDocument(session, lineage, generation, session.rootedView());
                // Failed initial validation must never register a mutable working session.
                selected.put(id, new OwnedSession(address.address(), document));
                return Optional.of(document);
            });
        }
        @Override public synchronized void close() { closed = true; selected.clear(); views.close(); }
    }

    static void requireLineageMembership(ManagedLineageIndex index, ManagedLineageIndex.Lineage row) {
        requireLineageMembership(index, row, false);
    }

    static void requireLineageMembership(ManagedLineageIndex index, ManagedLineageIndex.Lineage row, boolean controlledNamespace) {
        var state = index.storedState();
        for (var pair : List.of(Map.entry(state.authored(), row.authoredInitialBlueId()),
                Map.entry(state.initialized(), row.initializedBlueId()), Map.entry(state.current(), row.currentBlueId()))) {
            var bucket = pair.getKey().get(pair.getValue());
            require(bucket != null && sameLineage(row, bucket.get(row.documentId()), controlledNamespace),
                    "Selected lineage reverse membership is missing or stale");
        }
        // In the library-controlled namespace the reverse index was maintained with this exact immutable
        // history basis at publication. Selected reverse rows are still checked against their primary row.
        // Generic supplied roots must prove the complete reverse membership as before.
        if (controlledNamespace) return;
        for (var retained : row.retainedStates()) {
            var bucket = state.retained().get(retained.blueId());
            require(bucket != null && retained.equals(bucket.get(new ManagedLineageIndex.RetainedKey(row.documentId(), retained.epoch()))),
                    "Selected retained-state reverse membership is missing or stale");
        }
    }

    static boolean sameLineage(ManagedLineageIndex.Lineage expected, ManagedLineageIndex.Lineage actual,
            boolean controlledNamespace) {
        return controlledNamespace ? expected.sameIndexedHistory(actual) : expected.equals(actual);
    }

    private record OwnedSession(String address, SelectedDocument document) { }
}
