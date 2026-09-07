package blue.coordination.internal;

import blue.coordination.api.DocumentId;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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
    private final Map<DocumentId, ContractsRootSourceSurface.Surface>
            surfacesByRoot = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, Set<DocumentId>> rootsByManagedDocument =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private Set<String> timelineIds = Set.of();

    ContractsActiveSourceTimelineIndex(Collection<DocumentId> publicRoots) {
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
        for (DocumentId root : affectedRoots) {
            surfacesByRoot.put(root, ContractsRootSourceSurface.resolve(
                    ContractsRootFeederWindow.LaneId.publicRoots(
                            List.of(root)),
                    inventory,
                    resolver));
        }
        rebuildIndexes();
    }

    /** Rebuilds the entire disposable index after a process restart. */
    synchronized void rebuild(InMemoryDocumentStore documents) {
        surfacesByRoot.clear();
        rootsByManagedDocument.clear();
        refresh(List.copyOf(publicRoots), documents);
    }

    /** Immutable O(1) snapshot used for journal entry filtering. */
    synchronized Set<String> timelineIds() {
        return timelineIds;
    }

    private void rebuildIndexes() {
        TreeSet<String> canonical = new TreeSet<>(EmbeddingBinding.TEXT_ORDER);
        TreeMap<DocumentId, Set<DocumentId>> reverse = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        surfacesByRoot.forEach((root, surface) -> {
            canonical.addAll(surface.timelineIds());
            for (DocumentId document : surface.managedDocuments()) {
                reverse.computeIfAbsent(
                                document,
                                ignored -> new TreeSet<>(
                                        EmbeddingBinding.DOCUMENT_ORDER))
                        .add(root);
            }
        });
        rootsByManagedDocument.clear();
        reverse.forEach((document, roots) -> rootsByManagedDocument.put(
                document,
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(roots))));
        timelineIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(canonical));
    }
}
