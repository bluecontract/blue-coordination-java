package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import java.util.ArrayList;
import java.util.Comparator;
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
        return select(root, entries, RootedJoinEligibility.captureForRoot(documents, root));
    }

    private Selection select(DocumentId root, List<TimelineEntry> entries, List<RootedJoinEligibility.Fence> joins) {
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
        var local = adapter.nextRootLocalHistory(root, entries);
        if (local.pending() && local.step() == null) return new Selection(null, null, excluded, true, null);
        var live = adapter.nextRootLiveInput(root, entries);
        if (local.step() != null && (work.isEmpty() || local.step().sourceOrder().compareTo(order(work.get())) <= 0)
                && (live.isEmpty() || local.step().sourceOrder().compareTo(live.get().entry().sourceOrderKey()) <= 0)) {
            return new Selection(null, null, excluded, false, local.step());
        }
        if (work.isPresent()) {
            var order = order(work.get());
            if (live.isEmpty() || order.compareTo(live.get().entry().sourceOrderKey()) <= 0) {
                return new Selection(null, work.get(), excluded, false);
            }
        }
        if (live.isPresent() && RootedJoinEligibility.blocks(joins, owners, live.get().entry().sourceOrderKey())) {
            return new Selection(null, null, excluded, true);
        }
        return new Selection(live.orElse(null), null, excluded, false);
    }

    /** A transport entry is not a substitute for each root's retained progress. */
    Scan scan(List<TimelineEntry> entries, ExternalOrderKey cutoff) {
        List<Head> heads = new ArrayList<>();
        Set<DocumentId> blocked = new LinkedHashSet<>();
        var joins = RootedJoinEligibility.capture(documents);
        for (DocumentSession session : documents.sessions()) {
            if (session.rootedView() == null) continue;
            var component = session.rootedView().snapshot().components().stream()
                    .filter(c -> c.orderedMemberDocumentIds().contains(
                            ContractsClosureAdapter.closureId(session.documentId())))
                    .findFirst().orElseThrow();
            DocumentId anchor = component.orderedMemberDocumentIds().stream()
                    .map(ContractsClosureAdapter::coordinationId)
                    .min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow();
            if (!anchor.equals(session.documentId())) continue;
            Selection selected = select(anchor, entries, joins);
            if (selected.blocked()) blocked.add(anchor);
            ExternalOrderKey order = selected.live() != null ? selected.live().entry().sourceOrderKey()
                    : selected.historical() != null ? order(selected.historical())
                    : selected.localHistorical() != null ? selected.localHistorical().sourceOrder() : null;
            if (order != null && (cutoff == null || order.compareTo(cutoff) <= 0)) {
                heads.add(new Head(anchor, selected, order));
            }
        }
        // Across otherwise independent roots at the same external position,
        // retain the direct source lane before a one-way observer's local work.
        // This host tie never changes either root's own input or receiving set.
        heads.sort(Comparator.comparing(Head::order).thenComparing(head -> !head.directOwner())
                .thenComparing(Head::root, EmbeddingBinding.DOCUMENT_ORDER));
        return new Scan(heads, blocked);
    }

    private ExternalOrderKey order(ManagedEpochApplicationWork work) {
        return documents.managedEpochEvidence(work.sourceDocumentId(), work.sourceEpoch())
                .receipt().sourceOrder().orElseThrow();
    }

    record Head(DocumentId root, Selection selection, ExternalOrderKey order) {
        boolean directOwner() {
            return selection.live() != null && selection.live().invocations().stream().anyMatch(invocation ->
                    invocation.directDeliveries().stream().anyMatch(delivery -> invocation.rootedEvidence()
                            .context().entryOwners().contains(delivery.targetDocumentId())));
        }
    }

    record Scan(List<Head> heads, Set<DocumentId> blockedRoots) {
        Scan { heads = List.copyOf(heads); blockedRoots = Set.copyOf(blockedRoots); }
        boolean quiescent() { return heads.isEmpty() && blockedRoots.isEmpty(); }
    }

    record Selection(ContractsClosureAdapter.FrozenBatch live, ManagedEpochApplicationWork historical,
                     Set<DocumentId> excludedConsumers, boolean blocked, RootedLocalHistory.Step localHistorical) {
        Selection(ContractsClosureAdapter.FrozenBatch live, ManagedEpochApplicationWork historical,
                Set<DocumentId> excludedConsumers, boolean blocked) {
            this(live, historical, excludedConsumers, blocked, null);
        }
        Selection { excludedConsumers = Set.copyOf(excludedConsumers); }
    }
}
