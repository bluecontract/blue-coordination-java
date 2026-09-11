package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** No-overtake eligibility for an exact pending terminal join, before cross-root fairness. */
final class RootedJoinEligibility {
    private RootedJoinEligibility() { }

    static List<Fence> capture(InMemoryDocumentStore documents) {
        return capture(documents, documents.sessions(), Set.of());
    }

    /** Point lookup uses retained candidate membership, not the requested root's possibly newer graph. */
    static List<Fence> captureForRoot(InMemoryDocumentStore documents, DocumentId root) {
        var session = documents.require(root);
        var snapshot = java.util.Objects.requireNonNull(session.rootedView()).snapshot();
        var owners = snapshot.components().stream().filter(component -> component.orderedMemberDocumentIds()
                .contains(ContractsClosureAdapter.closureId(root))).findFirst().orElseThrow().orderedMemberDocumentIds().stream()
                .map(ContractsClosureAdapter::coordinationId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var index = documents.occurrenceResolutionSnapshot().componentIndex();
        var roots = new LinkedHashSet<DocumentId>();
        for (var owner : owners) roots.addAll(index.pendingJoinRootsFor(owner));
        var candidates = roots.stream().map(documents::require).toList();
        return capture(documents, candidates, owners);
    }

    /** Derived membership only; no plan/body loading and no scheduling authority. */
    static Set<DocumentId> candidateMembers(blue.language.processor.closure.AffectedClosureSnapshot snapshot, DocumentId root) {
        var owners = snapshot.components().stream().filter(component -> component.orderedMemberDocumentIds()
                .contains(ContractsClosureAdapter.closureId(root))).findFirst().orElseThrow().orderedMemberDocumentIds().stream()
                .map(ContractsClosureAdapter::coordinationId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!root.equals(owners.stream().min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow())) return Set.of();
        var graph = graph(snapshot);
        var affected = new LinkedHashSet<DocumentId>();
        for (var row : snapshot.occurrences()) affected.addAll(affected(graph, owners, row));
        return Set.copyOf(affected);
    }

    private static List<Fence> capture(InMemoryDocumentStore documents, java.util.Collection<DocumentSession> candidates,
            Set<DocumentId> interestedOwners) {
        var fences = new ArrayList<Fence>();
        for (var session : candidates) {
            var view = session.rootedView();
            if (view == null) continue;
            var id = ContractsClosureAdapter.closureId(session.documentId());
            var owners = view.snapshot().components().stream().filter(component -> component.orderedMemberDocumentIds().contains(id))
                    .findFirst().orElseThrow().orderedMemberDocumentIds().stream()
                    .map(ContractsClosureAdapter::coordinationId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!session.documentId().equals(owners.stream().min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow())) continue;
            var forward = graph(view.snapshot());
            for (var row : view.snapshot().occurrences()) {
                var affected = affected(forward, owners, row);
                if (affected.isEmpty()) continue;
                if (!interestedOwners.isEmpty() && java.util.Collections.disjoint(affected, interestedOwners)) continue;
                ExternalOrderKey boundary = boundary(documents, view, owners, row);
                fences.add(new Fence(boundary, affected));
            }
        }
        return List.copyOf(fences);
    }

    private static Map<DocumentId, Set<DocumentId>> graph(blue.language.processor.closure.AffectedClosureSnapshot snapshot) {
        Map<DocumentId, Set<DocumentId>> graph = new LinkedHashMap<>();
        for (var row : snapshot.occurrences()) if (row.active())
            graph.computeIfAbsent(ContractsClosureAdapter.coordinationId(row.sourceDocumentId()), ignored -> new LinkedHashSet<>())
                    .add(ContractsClosureAdapter.coordinationId(row.targetDocumentId()));
        return graph;
    }

    private static Set<DocumentId> affected(Map<DocumentId, Set<DocumentId>> graph, Set<DocumentId> owners, ManagedOccurrenceBinding row) {
        if (row.active() || row.pendingHistoricalEpoch() == null) return Set.of();
        var approach = paths(graph, owners, Set.of(ContractsClosureAdapter.coordinationId(row.sourceDocumentId())));
        if (approach.isEmpty()) return Set.of();
        var returning = paths(graph, Set.of(ContractsClosureAdapter.coordinationId(row.targetDocumentId())), owners);
        if (returning.isEmpty()) return Set.of(); // A one-way source remains independently eligible.
        approach.addAll(returning);
        return approach;
    }

    private static ExternalOrderKey boundary(InMemoryDocumentStore documents, RootedDocumentView view,
            Set<DocumentId> owners, ManagedOccurrenceBinding row) {
        var consumer = ContractsClosureAdapter.coordinationId(row.sourceDocumentId());
        if (!owners.contains(consumer)) {
            return java.util.Objects.requireNonNull(view.logicalBoundary(), "Pending root-local join lacks its original boundary");
        }
        var plans = documents.catchUpPlans(consumer).stream().filter(plan ->
                plan.targetOccurrenceIdentity().equals(row.occurrenceIdentity())
                        && plan.activationGeneration() == row.activationGeneration()
                        && plan.sourceDocumentId().value().equals(row.targetDocumentId().value())
                        && plan.targetPath().equals(row.sourcePath())
                        && plan.nextSourceEpoch() == Math.addExact(row.pendingHistoricalEpoch(), 1L)
                        && plan.status() != ManagedCatchUpStatus.COMPLETE
                        && plan.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED).toList();
        if (plans.size() != 1) throw ContractsClosureAdapter.stale("Pending terminal join lacks one exact retained plan");
        var plan = plans.get(0);
        var barrier = documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow();
        if (!barrier.consumerDocumentId().equals(consumer) || !barrier.planIdentities().contains(plan.planIdentity()))
            throw ContractsClosureAdapter.stale("Pending terminal join lacks its exact owning barrier");
        return barrier.causeOrder();
    }

    /** Only vertices on a start-to-goal path participate; reachable side branches are not protected. */
    private static Set<DocumentId> paths(Map<DocumentId, Set<DocumentId>> graph, Set<DocumentId> starts, Set<DocumentId> goals) {
        var reachable = reachable(graph, starts);
        if (java.util.Collections.disjoint(reachable, goals)) return Set.of();
        Map<DocumentId, Set<DocumentId>> reverse = new LinkedHashMap<>();
        graph.forEach((source, targets) -> targets.forEach(target ->
                reverse.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source)));
        reachable.retainAll(reachable(reverse, goals));
        return reachable;
    }

    private static Set<DocumentId> reachable(Map<DocumentId, Set<DocumentId>> graph, Set<DocumentId> starts) {
        var result = new LinkedHashSet<DocumentId>();
        var pending = new ArrayDeque<>(starts);
        while (!pending.isEmpty()) {
            var next = pending.removeFirst();
            if (result.add(next)) pending.addAll(graph.getOrDefault(next, Set.of()));
        }
        return result;
    }

    static boolean blocks(List<Fence> fences, Set<DocumentId> owners, ExternalOrderKey order) {
        return fences.stream().anyMatch(fence -> order.compareTo(fence.boundary()) > 0
                && !java.util.Collections.disjoint(owners, fence.owners()));
    }

    record Fence(ExternalOrderKey boundary, Set<DocumentId> owners) {
        Fence { java.util.Objects.requireNonNull(boundary); owners = Set.copyOf(owners); }
    }
}
