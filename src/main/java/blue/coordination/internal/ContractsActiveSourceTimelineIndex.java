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

/**
 * Disposable exact Timeline union for the configured public Root surfaces.
 *
 * <p>Successful publications refresh only configured Roots in the affected
 * connected cohort. The durable occurrence inventory remains authoritative;
 * restart may rebuild every configured Root contribution from it.</p>
 */
final class ContractsActiveSourceTimelineIndex {
    private final TreeSet<DocumentId> publicRoots;
    private final Map<DocumentId, ContractsRootSourceSurface.Surface>
            surfacesByRoot = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
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

    /** Refreshes configured Roots present in one newly published cohort. */
    synchronized void refresh(
            Collection<DocumentId> affectedDocuments,
            InMemoryDocumentStore documents) {
        Objects.requireNonNull(affectedDocuments, "affectedDocuments");
        InMemoryDocumentStore store = Objects.requireNonNull(
                documents, "documents");
        TreeSet<DocumentId> affectedRoots = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : affectedDocuments) {
            DocumentId checked = Objects.requireNonNull(
                    documentId, "affectedDocument");
            if (publicRoots.contains(checked)) {
                affectedRoots.add(checked);
            }
        }
        if (affectedRoots.isEmpty()) {
            return;
        }
        ManagedOccurrenceInventory occurrences = store.occurrenceInventory();
        for (DocumentId root : affectedRoots) {
            surfacesByRoot.put(root, resolve(root, occurrences, store));
        }
        rebuildTimelineUnion();
    }

    /** Rebuilds the entire disposable index after a process restart. */
    synchronized void rebuild(InMemoryDocumentStore documents) {
        surfacesByRoot.clear();
        refresh(List.copyOf(publicRoots), documents);
    }

    /** Immutable O(1) snapshot used for journal entry filtering. */
    synchronized Set<String> timelineIds() {
        return timelineIds;
    }

    private void rebuildTimelineUnion() {
        TreeSet<String> canonical = new TreeSet<>(EmbeddingBinding.TEXT_ORDER);
        surfacesByRoot.values().forEach(surface ->
                canonical.addAll(surface.timelineIds()));
        timelineIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(canonical));
    }

    private static ContractsRootSourceSurface.Surface resolve(
            DocumentId root,
            ManagedOccurrenceInventory occurrences,
            InMemoryDocumentStore documents) {
        return ContractsRootSourceSurface.resolve(
                ContractsRootFeederWindow.LaneId.publicRoots(List.of(root)),
                occurrences,
                documentId -> documents.find(documentId)
                        .map(session -> session.layout().routingSurface()
                                .externalTimelineIds())
                        .orElse(List.of()));
    }
}
