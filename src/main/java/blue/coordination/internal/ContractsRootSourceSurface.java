package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ManagedOccurrenceBinding;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Active Root-lane union of independently managed document source surfaces. */
final class ContractsRootSourceSurface {
    private ContractsRootSourceSurface() {
    }

    /**
     * Resolves one lane's active managed-document and Timeline union.
     *
     * <p>Traversal is host-only and follows authored active Process Embedded
     * edges from the public Roots. It is cycle-safe, ignores inactive retained
     * rows, and never exposes reverse containment to document execution.</p>
     */
    static Surface resolve(
            ContractsRootFeederWindow.LaneId lane,
            ManagedOccurrenceInventory occurrences,
            Function<DocumentId, ? extends Collection<String>> timelines) {
        ContractsRootFeederWindow.LaneId selectedLane =
                Objects.requireNonNull(lane, "lane");
        ManagedOccurrenceInventory inventory = Objects.requireNonNull(
                occurrences, "occurrences");
        Function<DocumentId, ? extends Collection<String>> resolver =
                Objects.requireNonNull(timelines, "timelines");

        TreeSet<DocumentId> documents = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Deque<DocumentId> pending = new ArrayDeque<>();
        selectedLane.roots().forEach(pending::addLast);
        while (!pending.isEmpty()) {
            DocumentId document = pending.removeFirst();
            if (!documents.add(document)) {
                continue;
            }
            for (ManagedOccurrenceBinding row
                    : inventory.activeRowsFrom(document)) {
                pending.addLast(coordinationId(row.targetDocumentId()));
            }
        }

        TreeSet<String> timelineIds = new TreeSet<>(
                EmbeddingBinding.TEXT_ORDER);
        for (DocumentId document : documents) {
            Collection<String> resolved = Objects.requireNonNull(
                    resolver.apply(document),
                    "timeline resolver result for " + document);
            for (String timelineId : resolved) {
                String checked = Objects.requireNonNull(
                        timelineId, "timelineId");
                if (checked.isBlank()) {
                    throw new IllegalArgumentException(
                            "timelineId must not be blank");
                }
                timelineIds.add(checked);
            }
        }
        return new Surface(
                selectedLane,
                List.copyOf(documents),
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(timelineIds)));
    }

    private static DocumentId coordinationId(
            blue.language.processor.closure.DocumentId documentId) {
        return DocumentId.of(Objects.requireNonNull(
                documentId, "documentId").value());
    }

    /** Immutable exact membership used by one public Root feeder/window. */
    record Surface(
            ContractsRootFeederWindow.LaneId lane,
            List<DocumentId> managedDocuments,
            Set<String> timelineIds) {
        Surface {
            lane = Objects.requireNonNull(lane, "lane");
            managedDocuments = List.copyOf(Objects.requireNonNull(
                    managedDocuments, "managedDocuments"));
            timelineIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            timelineIds, "timelineIds")));
            if (!managedDocuments.containsAll(lane.roots())) {
                throw new IllegalArgumentException(
                        "Source surface must contain every lane Root");
            }
        }
    }
}
