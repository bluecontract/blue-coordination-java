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

    /** A same/later join fence does not make an exclusive source prefix incomplete. Never executes the unfenced selection. */
    boolean completeBefore(DocumentId root, List<TimelineEntry> entries, ExternalOrderKey cutoff) {
        if (RootedJoinPrerequisites.pendingBefore(root, documents.require(root).rootedViewBefore(cutoff),
                cutoff, documents) != null) return false;
        var next = baseSelection(root, entries, List.of());
        if (next.blocked()) return false;
        ExternalOrderKey origin = next.live() != null ? next.live().entry().sourceOrderKey()
                : next.historical() != null ? documents.catchUpBarrier(next.historical().barrierIdentity()).orElseThrow().causeOrder()
                : next.localHistorical() != null ? next.localHistorical().anchor().sourceOrderKey() : null;
        return origin == null || origin.compareTo(cutoff) >= 0;
    }

    private Selection select(DocumentId root, List<TimelineEntry> entries, List<RootedJoinEligibility.Fence> joins) {
        return RootedJoinScheduling.select(root, baseSelection(root, entries, joins), joins, documents,
                selected -> baseSelection(selected, entries, joins),
                (selected, boundary) -> completeThrough(selected, entries, boundary),
                adapter::captureTerminalPeers);
    }

    /** Positive completion evidence for a join, not permission to execute a later fenced input. */
    private boolean completeThrough(DocumentId root, List<TimelineEntry> entries, ExternalOrderKey boundary) {
        var view = documents.require(root).rootedView();
        var local = adapter.nextRootLocalHistory(root, entries);
        if (local.pending() && (view.logicalBoundary() == null || view.logicalBoundary().compareTo(boundary) <= 0)) return false;
        var owners = view.snapshot().components().stream().filter(component -> component.orderedMemberDocumentIds()
                .contains(ContractsClosureAdapter.closureId(root))).findFirst().orElseThrow().orderedMemberDocumentIds();
        for (var owner : owners) for (var plan : documents.catchUpPlans(ContractsClosureAdapter.coordinationId(owner))) {
            if (plan.status() != ManagedCatchUpStatus.COMPLETE && plan.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED
                    && documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow().causeOrder().compareTo(boundary) <= 0) return false;
        }
        var next = baseSelection(root, entries, List.of());
        return !next.blocked() && next.historical() == null && next.localHistorical() == null
                && (next.live() == null || next.live().entry().sourceOrderKey().compareTo(boundary) > 0);
    }

    private Selection baseSelection(DocumentId root, List<TimelineEntry> entries, List<RootedJoinEligibility.Fence> joins) {
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
        ExternalOrderKey retainedOrder = work.map(this::order).orElse(null);
        if (local.step() != null && (retainedOrder == null || local.step().sourceOrder().compareTo(retainedOrder) < 0)) {
            retainedOrder = local.step().sourceOrder();
        }
        var live = adapter.nextRootLiveInput(root, entries, retainedOrder);
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
        if (live.isPresent() && RootedJoinEligibility.blocks(joins, owners, live.get())) {
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
