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
 * Disposable exact Timeline union for the configured public Root surfaces.
 *
 * <p>Successful publications refresh each configured Root whose last
 * committed source surface contains an affected document, as well as an
 * affected Root itself. The durable occurrence inventory remains
 * authoritative; restart may rebuild every configured Root contribution from
 * it.</p>
 */
final class ContractsActiveSourceTimelineIndex {
    private final TreeSet<DocumentId> publicRoots;
    private final EngineMetrics metrics;
    private final Map<DocumentId, ContractsRootSourceSurface.Surface>
            surfacesByRoot = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, Set<DocumentId>> rootsByManagedDocument =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private PersistentOrderedMap<String, Long> timelineReferences =
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER);
    private Set<String> timelineIds = Set.of();

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
        this.publicRoots = canonical;
    }

    synchronized void addPublicRoots(Collection<DocumentId> roots) {
        Objects.requireNonNull(roots, "roots").forEach(root ->
                publicRoots.add(Objects.requireNonNull(root, "publicRoot")));
    }

    /** Refreshes configured Roots affected by one newly published cohort. */
    synchronized void refresh(
            Collection<DocumentId> affectedDocuments,
            InMemoryDocumentStore documents) {
        Objects.requireNonNull(affectedDocuments, "affectedDocuments");
        InMemoryDocumentStore store = Objects.requireNonNull(
                documents, "documents");
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
            if (publicRoots.contains(checked)) {
                affectedRoots.add(checked);
            }
            affectedRoots.addAll(rootsByManagedDocument.getOrDefault(
                    checked, Set.of()));
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
        replacements.forEach(this::replaceSurface);
        timelineIds = Collections.unmodifiableSet(
                new PersistentMapView<>(timelineReferences).keySet());
    }

    /** Rebuilds the entire disposable index after a process restart. */
    synchronized void rebuild(InMemoryDocumentStore documents) {
        surfacesByRoot.clear();
        rootsByManagedDocument.clear();
        timelineReferences = PersistentOrderedMap.empty(
                EmbeddingBinding.TEXT_ORDER);
        timelineIds = Set.of();
        refresh(List.copyOf(publicRoots), documents);
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
        if (prior != null) {
            for (DocumentId document : prior.managedDocuments()) {
                Set<DocumentId> roots = Objects.requireNonNull(
                        rootsByManagedDocument.get(document));
                if (!roots.remove(root)) {
                    throw new IllegalStateException(
                            "Missing retained source membership");
                }
                if (roots.isEmpty()) {
                    rootsByManagedDocument.remove(document);
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
            rootsByManagedDocument.computeIfAbsent(
                            document,
                            ignored -> new TreeSet<>(
                                    EmbeddingBinding.DOCUMENT_ORDER))
                    .add(root);
        }
        for (String timeline : surface.timelineIds()) {
            Long previous = timelineReferences.get(timeline);
            timelineReferences = timelineReferences.put(timeline,
                    previous == null ? 1L : Math.addExact(previous, 1L)).map();
        }
        surfacesByRoot.put(root, surface);
    }
}
