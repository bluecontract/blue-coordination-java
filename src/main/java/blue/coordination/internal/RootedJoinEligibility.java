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
                var receivers = new LinkedHashSet<>(affected);
                view.snapshot().components().stream().filter(component -> component.orderedMemberDocumentIds().contains(row.targetDocumentId()))
                        .findFirst().orElseThrow().orderedMemberDocumentIds().stream().map(ContractsClosureAdapter::coordinationId)
                        .forEach(receivers::remove);
                String cause;
                var consumer = ContractsClosureAdapter.coordinationId(row.sourceDocumentId());
                if (owners.contains(consumer)) {
                    var plan = matchingPlans(documents, consumer, row).stream().filter(value ->
                            value.nextSourceEpoch() == row.pendingHistoricalEpoch() + 1L).findFirst().orElseThrow();
                    cause = documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow().causedByIdentity();
                } else {
                    cause = null;
                    var original = RootedTerminalEvidence.originalLocalCause(view, documents);
                    if (original != null) {
                        if (!original.sourceOrder().equals(boundary)) throw ContractsClosureAdapter.stale("Local join changed its original cause boundary");
                        cause = original.causeIdentity();
                    }
                }
                fences.add(new Fence(boundary, affected, receivers, cause, row, terminal(documents, row, receivers, boundary, cause)));
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

    private static List<blue.coordination.api.ManagedOccurrenceCatchUpPlan> matchingPlans(InMemoryDocumentStore documents,
            DocumentId consumer, ManagedOccurrenceBinding row) {
        return documents.catchUpPlans(consumer).stream().filter(plan ->
                plan.targetOccurrenceIdentity().equals(row.occurrenceIdentity())
                        && plan.activationGeneration() == row.activationGeneration()
                        && plan.targetPath().equals(row.sourcePath())
                        && plan.sourceDocumentId().value().equals(row.targetDocumentId().value())
                        && plan.status() != ManagedCatchUpStatus.COMPLETE
                        && plan.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED).toList();
    }

    /** A local row is not a registered plan: use the independently published consumer's real canonical work. */
    private static Terminal terminal(InMemoryDocumentStore documents, ManagedOccurrenceBinding row,
            Set<DocumentId> receivers, ExternalOrderKey boundary, String originalCause) {
        if (originalCause == null) return null;
        var consumer = ContractsClosureAdapter.coordinationId(row.sourceDocumentId());
        var plans = matchingPlans(documents, consumer, row).stream().filter(plan ->
                plan.status() == ManagedCatchUpStatus.RUNNING).toList();
        if (plans.size() != 1) return null;
        var plan = plans.get(0);
        var barrier = documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow();
        if (!barrier.causeOrder().equals(boundary) || !barrier.causedByIdentity().equals(originalCause)
                || !barrier.consumerDocumentId().equals(consumer)
                || !barrier.planIdentities().contains(plan.planIdentity())) return null;
        var view = documents.require(consumer).rootedView();
        var owners = view.snapshot().components().stream().filter(component -> component.orderedMemberDocumentIds()
                .contains(row.sourceDocumentId())).findFirst().orElseThrow().orderedMemberDocumentIds().stream()
                .map(ContractsClosureAdapter::coordinationId).collect(java.util.stream.Collectors.toSet());
        if (view.snapshot().occurrences().stream().noneMatch(current -> current.occurrenceIdentity().equals(row.occurrenceIdentity())
                && current.activationGeneration() == row.activationGeneration() && current.sourceDocumentId().equals(row.sourceDocumentId())
                && current.targetDocumentId().equals(row.targetDocumentId()) && current.sourcePath().equals(row.sourcePath())
                && !current.active() && current.pendingHistoricalEpoch() != null
                && current.pendingHistoricalEpoch() + 1L == plan.nextSourceEpoch())) return null;
        var pending = documents.catchUpPlansSnapshot().pendingWorkForPlan(plan.planIdentity());
        if (!pending.found()) return null;
        var work = pending.work();
        if (work.sourceEpoch() != plan.requiredThroughSourceEpoch() || work.successorRepresentationCause().isPresent()
                || work.isRepresentationApplication() && !work.representationCause().orElseThrow().terminalPositionReached()) return null;
        var excluded = documents.sessions().stream().map(DocumentSession::documentId).filter(id -> !owners.contains(id))
                .collect(java.util.stream.Collectors.toSet());
        if (documents.nextCatchUpWorkExcluding(excluded).filter(next -> next.workIdentity().equals(work.workIdentity())).isEmpty()) return null;
        var interior = new LinkedHashSet<>(receivers);
        interior.removeAll(owners);
        if (interior.isEmpty()) return null;
        return new Terminal(work, interior, excluded);
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
        return fences.stream().anyMatch(fence -> order.compareTo(fence.boundary()) >= 0
                && !java.util.Collections.disjoint(owners, fence.owners()));
    }

    /** Same order alone is not authority: require the exact original external cause and receiving path. */
    static boolean blocks(List<Fence> fences, Set<DocumentId> owners, ContractsClosureAdapter.FrozenBatch batch) {
        return fences.stream().anyMatch(fence -> batch.entry().sourceOrderKey().compareTo(fence.boundary()) >= 0
                && !java.util.Collections.disjoint(owners, fence.owners()) && !sameCauseReceiver(fence, owners, batch));
    }

    static boolean sameCauseReceiver(Fence fence, Set<DocumentId> owners, ContractsClosureAdapter.FrozenBatch batch) {
        return fence.causeIdentity() != null && fence.receivers().containsAll(owners)
                && batch.entry().sourceOrderKey().equals(fence.boundary()) && !batch.invocations().isEmpty()
                && batch.invocations().stream().allMatch(invocation -> invocation.rootedEvidence() != null
                    && invocation.input().cause() instanceof blue.language.processor.closure.ExternalEventCause cause
                    && cause.causeIdentity().equals(fence.causeIdentity())
                    && cause.sourceOrder().equals(fence.boundary()) && cause.eventBlueId().equals(batch.entry().blueId()));
    }

    record Terminal(blue.coordination.api.ManagedEpochApplicationWork work,
            Set<DocumentId> interiorOwners, Set<DocumentId> excludedConsumers) {
        Terminal { interiorOwners = Set.copyOf(interiorOwners); excludedConsumers = Set.copyOf(excludedConsumers); }
    }

    record Fence(ExternalOrderKey boundary, Set<DocumentId> owners, Set<DocumentId> receivers, String causeIdentity,
            ManagedOccurrenceBinding occurrence, Terminal terminal) {
        Fence { java.util.Objects.requireNonNull(boundary); owners = Set.copyOf(owners); receivers = Set.copyOf(receivers); }
    }
}
