package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.TimelineEntry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selects one root's exact next obligation; it never executes or publishes. */
final class RootedCheckpointDriver {
    private final InMemoryDocumentStore documents;
    private final ContractsClosureAdapter adapter;

    RootedCheckpointDriver(InMemoryDocumentStore documents, ContractsClosureAdapter adapter) {
        this.documents = documents;
        this.adapter = adapter;
    }

    Selection select(DocumentId root, List<TimelineEntry> entries) {
        DocumentSession session = documents.require(root);
        RootedDocumentView view = java.util.Objects.requireNonNull(session.rootedView(), "Root has no rooted profile view");
        view.requireOwnerHead(root, session.currentRepresentation().blueId());
        var component = view.snapshot().components().stream().filter(c -> c.orderedMemberDocumentIds()
                .contains(ContractsClosureAdapter.closureId(root))).findFirst().orElseThrow();
        Set<DocumentId> owners = new LinkedHashSet<>(component.orderedMemberDocumentIds().stream()
                .map(ContractsClosureAdapter::coordinationId).toList());
        Set<DocumentId> excluded = new LinkedHashSet<>();
        documents.sessions().forEach(s -> { if (!owners.contains(s.documentId())) excluded.add(s.documentId()); });
        boolean historyOutstanding = owners.stream().flatMap(id -> documents.catchUpPlans(id).stream())
                .anyMatch(p -> p.status() != ManagedCatchUpStatus.COMPLETE
                        && p.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED);
        var work = documents.nextCatchUpWorkExcluding(excluded);
        if (historyOutstanding && work.isEmpty()) return new Selection(null, null, excluded, true);
        var live = adapter.nextRootLiveInput(root, entries);
        if (work.isPresent()) {
            var order = documents.managedEpochEvidence(work.get().sourceDocumentId(), work.get().sourceEpoch())
                    .receipt().sourceOrder().orElseThrow();
            if (live.isEmpty() || order.compareTo(live.get().entry().sourceOrderKey()) <= 0) {
                return new Selection(null, work.get(), excluded, false);
            }
        }
        return new Selection(live.orElse(null), null, excluded, false);
    }

    record Selection(ContractsClosureAdapter.FrozenBatch live, ManagedEpochApplicationWork historical,
                     Set<DocumentId> excludedConsumers, boolean blocked) {
        Selection { excludedConsumers = Set.copyOf(excludedConsumers); }
    }
}
