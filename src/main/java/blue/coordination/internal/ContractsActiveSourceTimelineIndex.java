package blue.coordination.internal;

import blue.coordination.api.DocumentId;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Exact Timeline union for the configured public Root surfaces.
 *
 * <p>Successful publications refresh each configured Root whose last
 * committed source surface contains an affected document, as well as an
 * affected Root itself. The durable occurrence inventory remains
 * authoritative. Internal storage may restore the exact retained indexes;
 * explicit resident rebuild remains available.</p>
 */
final class ContractsActiveSourceTimelineIndex {
    private PersistentOrderedMap<DocumentId, Boolean> publicRoots =
            PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER);
    private final EngineMetrics metrics;
    private PersistentOrderedMap<DocumentId, ContractsRootSourceSurface.Surface>
            surfacesByRoot = PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER);
    private PersistentOrderedMap<DocumentId, Set<DocumentId>> rootsByManagedDocument =
            PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER);
    private PersistentOrderedMap<String, Long> timelineReferences =
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER);
    private Set<String> timelineIds = Set.of();
    private LogicalActiveSources logicalSources;
    private Function<DocumentId, ContractsRootSourceSurface.Surface> rootedSurface;

    /** Installed by the rooted adapter; each owner retains its own exact forward view. */
    synchronized void rootedSurfaceResolver(Function<DocumentId, ContractsRootSourceSurface.Surface> resolver) {
        if (rootedSurface != null) throw new IllegalStateException("Rooted surface resolver already installed");
        rootedSurface = Objects.requireNonNull(resolver);
    }


    ContractsActiveSourceTimelineIndex(Collection<DocumentId> publicRoots) {
        this(publicRoots, new EngineMetrics());
    }

    ContractsActiveSourceTimelineIndex(
            Collection<DocumentId> publicRoots,
            EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        TreeSet<DocumentId> canonical = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Objects.requireNonNull(publicRoots, "publicRoots").forEach(root ->
                canonical.add(Objects.requireNonNull(root, "publicRoot")));
        for (DocumentId root : canonical) this.publicRoots = this.publicRoots.put(root, true).map();
    }

    Set<DocumentId> logicalPublicRoots() {
        if (!publicRoots.isLogical()) throw new IllegalStateException("Not a logical Root index");
        return new java.util.AbstractSet<>() {
            public int size() { return publicRoots.size(); }
            public boolean contains(Object key) { return publicRoots.containsKey((DocumentId) key); }
            public java.util.Iterator<DocumentId> iterator() { return new PersistentMapView<>(publicRoots).keySet().iterator(); }
            public boolean add(DocumentId root) {
                boolean present = publicRoots.containsKey(root); addPublicRoots(Set.of(root)); return !present;
            }
        };
    }

    synchronized void addPublicRoots(Collection<DocumentId> roots) {
        var prepared = publicRoots;
        for (DocumentId root : Objects.requireNonNull(roots, "roots")) {
            prepared = prepared.put(Objects.requireNonNull(root, "publicRoot"), true).map();
        }
        publicRoots = prepared;
    }

    /** Refreshes configured Roots affected by one newly published cohort. */
    synchronized void refresh(
            Collection<DocumentId> affectedDocuments,
            InMemoryDocumentStore documents) {
        Objects.requireNonNull(affectedDocuments, "affectedDocuments");
        InMemoryDocumentStore store = Objects.requireNonNull(
                documents, "documents");
        if (rootedSurface != null) {
            Map<DocumentId, ContractsRootSourceSurface.Surface> replacements =
                    new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
            for (DocumentId owner : affectedDocuments) {
                if (!Boolean.TRUE.equals(publicRoots.get(owner))) continue;
                metrics.increment("sourceSurface.rootsResolved");
                replacements.put(owner, Objects.requireNonNull(rootedSurface.apply(owner)));
            }
            installReplacements(replacements);
            return;
        }
        refresh(
                affectedDocuments,
                store.occurrenceInventory(),
                documentId -> store.find(documentId)
                        .map(session -> session.layout().routingSurface()
                                .externalTimelineIds())
                        .orElse(List.of()));
    }

    /**
     * Refreshes every public Root whose active source surface contains an
     * affected document.
     *
     * <p>The reverse membership snapshot is derived from the last committed
     * surfaces. This catches descendant-only route changes and collection
     * activation/removal even when an uninterested ancestor did not join the
     * Contracts processing cohort. A topology mutation necessarily affects
     * its already-contained source, so the prior reverse snapshot also finds
     * the owning Root before the new surface is resolved.</p>
     */
    synchronized void refresh(
            Collection<DocumentId> affectedDocuments,
            ManagedOccurrenceInventory occurrences,
            Function<DocumentId, ? extends Collection<String>> timelines) {
        Objects.requireNonNull(affectedDocuments, "affectedDocuments");
        ManagedOccurrenceInventory inventory = Objects.requireNonNull(
                occurrences, "occurrences");
        Function<DocumentId, ? extends Collection<String>> resolver =
                Objects.requireNonNull(timelines, "timelines");
        TreeSet<DocumentId> affectedRoots = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : affectedDocuments) {
            DocumentId checked = Objects.requireNonNull(
                    documentId, "affectedDocument");
            if (Boolean.TRUE.equals(publicRoots.get(checked))) {
                affectedRoots.add(checked);
            }
            Set<DocumentId> retained = rootsByManagedDocument.get(checked);
            if (retained != null) affectedRoots.addAll(retained);
        }
        if (affectedRoots.isEmpty()) {
            return;
        }
        Map<DocumentId, ContractsRootSourceSurface.Surface> replacements =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId root : affectedRoots) {
            metrics.increment("sourceSurface.rootsResolved");
            replacements.put(root, ContractsRootSourceSurface.resolve(
                    ContractsRootFeederWindow.LaneId.publicRoots(List.of(root)),
                    inventory,
                    document -> {
                        metrics.increment("sourceSurface.documentsResolved");
                        return resolver.apply(document);
                    }));
        }
        installReplacements(replacements);
    }

    private void installReplacements(Map<DocumentId, ContractsRootSourceSurface.Surface> replacements) {
        // Physical writes may fail. Prepare all immutable map changes before
        // publishing any replacement, just as source resolution is staged above.
        var prepared = restoreIndexes(storedIndexes(), metrics);
        if (surfacesByRoot.isStored()) {
            SessionStorageWire.physical(() -> { replacements.forEach(prepared::replaceSurface); return true; });
        } else {
            replacements.forEach(prepared::replaceSurface);
        }
        install(prepared.storedIndexes());
    }

    /** Rebuilds the entire disposable index after a process restart. */
    synchronized void rebuild(InMemoryDocumentStore documents) {
        if (logicalSources != null) {
            var prepared = restoreIndexes(storedIndexes(), metrics);
            prepared.rootedSurface = rootedSurface;
            for (var root : publicRoots.keys()) prepared.replaceSurface(root, new ContractsRootSourceSurface.Surface(
                    ContractsRootFeederWindow.LaneId.publicRoots(List.of(root)), List.of(root), Set.of()));
            prepared.refresh(publicRoots.keys(), documents); install(prepared.storedIndexes()); return;
        }
        var prepared = restoreIndexes(new StoredIndexes(publicRoots, surfacesByRoot.emptyCopy(),
                rootsByManagedDocument.emptyCopy(), timelineReferences.emptyCopy()), metrics);
        prepared.rootedSurface = rootedSurface;
        prepared.refresh(publicRoots.keys(), documents);
        install(prepared.storedIndexes());
    }

    /** Immutable O(1) snapshot used for journal entry filtering. */
    synchronized Set<String> timelineIds() {
        return timelineIds;
    }

    private void replaceSurface(
            DocumentId root,
            ContractsRootSourceSurface.Surface surface) {
        ContractsRootSourceSurface.Surface prior = surfacesByRoot.get(root);
        if (surface.equals(prior)) {
            return;
        }
        if (logicalSources != null) {
            if (prior != null) SessionStorageWire.require(prior.lane().equals(ContractsRootFeederWindow.LaneId.publicRoots(List.of(root))),
                    "Retained source surface has foreign Root");
            var changed = logicalSources.replace(root, prior, surface);
            var nextSurfaces = surfacesByRoot.put(root, surface).map();
            logicalSources = changed; surfacesByRoot = nextSurfaces;
            rootsByManagedDocument = changed.memberships(); timelineReferences = changed.counts();
            return;
        }
        if (prior != null) {
            SessionStorageWire.require(prior.lane().equals(ContractsRootFeederWindow.LaneId.publicRoots(List.of(root))),
                    "Retained source surface has foreign Root");
            for (DocumentId document : prior.managedDocuments()) {
                Set<DocumentId> roots = new TreeSet<>(EmbeddingBinding.DOCUMENT_ORDER);
                roots.addAll(Objects.requireNonNull(rootsByManagedDocument.get(document)));
                if (!roots.remove(root)) {
                    throw new IllegalStateException(
                            "Missing retained source membership");
                }
                if (roots.isEmpty()) {
                    rootsByManagedDocument = rootsByManagedDocument.remove(document).map();
                } else {
                    rootsByManagedDocument = rootsByManagedDocument.put(document, Collections.unmodifiableSet(roots)).map();
                }
            }
            for (String timeline : prior.timelineIds()) {
                long count = Objects.requireNonNull(timelineReferences.get(timeline));
                if (count <= 0L) {
                    throw new IllegalStateException(
                            "Invalid retained Timeline count");
                }
                timelineReferences = count == 1L
                        ? timelineReferences.remove(timeline).map()
                        : timelineReferences.put(timeline, count - 1L).map();
            }
        }
        for (DocumentId document : surface.managedDocuments()) {
            Set<DocumentId> roots = new TreeSet<>(EmbeddingBinding.DOCUMENT_ORDER);
            Set<DocumentId> previous = rootsByManagedDocument.get(document);
            if (previous != null) roots.addAll(previous);
            roots.add(root);
            rootsByManagedDocument = rootsByManagedDocument.put(document, Collections.unmodifiableSet(roots)).map();
        }
        for (String timeline : surface.timelineIds()) {
            Long previous = timelineReferences.get(timeline);
            timelineReferences = timelineReferences.put(timeline,
                    previous == null ? 1L : Math.addExact(previous, 1L)).map();
        }
        surfacesByRoot = surfacesByRoot.put(root, surface).map();
    }

    synchronized StoredIndexes storedIndexes() {
        return new StoredIndexes(publicRoots, surfacesByRoot, rootsByManagedDocument, timelineReferences, logicalSources);
    }

    static ContractsActiveSourceTimelineIndex restoreIndexes(StoredIndexes state, EngineMetrics metrics) {
        var restored = new ContractsActiveSourceTimelineIndex(List.of(), metrics);
        restored.install(state);
        return restored;
    }

    private void install(StoredIndexes state) {
        logicalSources = state.logicalSources();
        publicRoots = state.publicRoots();
        surfacesByRoot = state.surfaces();
        rootsByManagedDocument = state.memberships();
        timelineReferences = state.timelineReferences();
        timelineIds = Collections.unmodifiableSet(new PersistentMapView<>(timelineReferences).keySet());
    }

    record StoredIndexes(
            PersistentOrderedMap<DocumentId, Boolean> publicRoots,
            PersistentOrderedMap<DocumentId, ContractsRootSourceSurface.Surface> surfaces,
            PersistentOrderedMap<DocumentId, Set<DocumentId>> memberships,
            PersistentOrderedMap<String, Long> timelineReferences, LogicalActiveSources logicalSources) {
        StoredIndexes(PersistentOrderedMap<DocumentId, Boolean> publicRoots,
                PersistentOrderedMap<DocumentId, ContractsRootSourceSurface.Surface> surfaces,
                PersistentOrderedMap<DocumentId, Set<DocumentId>> memberships, PersistentOrderedMap<String, Long> timelineReferences) {
            this(publicRoots, surfaces, memberships, timelineReferences, null);
        }
        StoredIndexes {
            Objects.requireNonNull(publicRoots, "publicRoots");
            Objects.requireNonNull(surfaces, "surfaces");
            Objects.requireNonNull(memberships, "memberships");
            Objects.requireNonNull(timelineReferences, "timelineReferences");
        }
    }
}
