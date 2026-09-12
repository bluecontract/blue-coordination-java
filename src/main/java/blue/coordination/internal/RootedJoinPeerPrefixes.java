package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ExternalEventCause;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Orders independent receiving prefixes of one proved pending join, never unrelated observers. */
final class RootedJoinPeerPrefixes {
    private RootedJoinPeerPrefixes() { }

    /** A successful local calculation is not yet its unowned receiver's original publication. */
    static ReceivingPublication pendingReceivingCause(ContractsClosureAdapter.FrozenBatch batch,
            ContractsClosureAdapter.CohortInvocation originalInput, ContractsClosureAdapter.CohortInvocation invocation,
            blue.language.processor.closure.ClosureProcessResult result,
            InMemoryDocumentStore documents, Function<DocumentId, Optional<ContractsClosureAdapter.FrozenBatch>> nextLive) {
        if (invocation.rootedEvidence() == null || !result.commits()
                || !(invocation.input().cause() instanceof ExternalEventCause cause)) return null;
        var projection = result.rootedProjection();
        var snapshot = projection.resultingSnapshot();
        var entryOwners = owners(invocation);
        for (var row : snapshot.occurrences()) {
            if (row.active() || row.pendingHistoricalEpoch() == null || projection.owns(row.sourceDocumentId())
                    || invocation.directDeliveries().stream().noneMatch(delivery ->
                            delivery.targetDocumentId().equals(row.sourceDocumentId()))) continue;
            var consumer = ContractsClosureAdapter.coordinationId(row.sourceDocumentId());
            var consumerAnchor = snapshot.components().stream().filter(component -> component.orderedMemberDocumentIds()
                    .contains(row.sourceDocumentId())).findFirst().orElseThrow().orderedMemberDocumentIds().stream()
                    .map(ContractsClosureAdapter::coordinationId).min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow();
            // Candidate topology is read from the verified result, never installed
            // as a source plan, committed index, or authoritative ownership claim.
            var candidates = RootedJoinEligibility.candidateMembers(snapshot, consumerAnchor);
            if (java.util.Collections.disjoint(entryOwners, candidates)) continue;
            var sourceOwners = snapshot.components().stream().filter(component -> component.orderedMemberDocumentIds()
                    .contains(row.targetDocumentId())).findFirst().orElseThrow().orderedMemberDocumentIds().stream()
                    .map(ContractsClosureAdapter::coordinationId).collect(java.util.stream.Collectors.toSet());
            boolean independentPeer = candidates.stream().anyMatch(peer -> !sourceOwners.contains(peer)
                    && !peer.equals(consumer) && !entryOwners.contains(peer)
                    && !reaches(originalInput.input().snapshot(), entryOwners, Set.of(peer))
                    && documents.find(peer).isPresent() && documents.require(peer).rootedView() != null
                    && !reaches(documents.require(peer).rootedView().snapshot(),
                            componentOwners(peer, documents), entryOwners));
            if (!independentPeer) continue;
            var session = documents.find(consumer).orElse(null);
            if (session == null || session.rootedView() == null) continue;
            var view = session.rootedView();
            // Initial admission has no rooted LIVE publication to authenticate.
            // A rooted result still requires the complete retained-cause proof.
            var published = view.result().rootedProjection() == null ? null
                    : RootedTerminalEvidence.originalLocalCause(view, documents);
            if (published != null && published.causeIdentity().equals(cause.causeIdentity())
                    && published.sourceOrder().equals(cause.sourceOrder())) continue;
            var next = nextLive.apply(consumer).orElse(null);
            if (next == null || !next.entry().blueId().equals(batch.entry().blueId())) continue;
            boolean originalReceiver = next.invocations().stream().anyMatch(selected -> selected.rootedEvidence() != null
                    && selected.input().cause().causeIdentity().equals(cause.causeIdentity())
                    && selected.directDeliveries().stream().anyMatch(delivery -> selected.rootedEvidence().context()
                            .entryOwners().contains(delivery.targetDocumentId())));
            if (originalReceiver) return new ReceivingPublication(consumer);
        }
        return null;
    }

    /** Host prerequisite, not a Language resource demand or a terminal feeder decision. */
    record ReceivingPublication(DocumentId consumer) {
        ReceivingPublication { java.util.Objects.requireNonNull(consumer, "consumer"); }
    }

    static boolean blocks(ContractsClosureAdapter.FrozenBatch batch, List<RootedJoinEligibility.Fence> fences,
            InMemoryDocumentStore documents, Function<DocumentId, RootedCheckpointDriver.Selection> next) {
        for (var input : batch.invocations()) {
            if (input.rootedEvidence() == null) continue;
            var owners = owners(input);
            for (var fence : fences) {
                if (!RootedJoinEligibility.sameCauseReceiver(fence, owners, batch)) continue;
                for (var peer : precedingAbsentPeers(input, fence, documents)) {
                    var selected = next.apply(peer);
                    if (fence.terminal() != null && RootedJoinScheduling.matches(
                            fence.terminal().work(), selected.localHistorical(), fence)) continue;
                    if (selected.blocked()) return true;
                    var order = selected.live() != null ? selected.live().entry().sourceOrderKey()
                            : selected.localHistorical() != null ? selected.localHistorical().anchor().sourceOrderKey()
                            : selected.historical() != null ? documents.catchUpBarrier(selected.historical().barrierIdentity())
                                    .orElseThrow().causeOrder() : null;
                    if (order != null && order.compareTo(fence.boundary()) <= 0) return true;
                }
            }
        }
        return false;
    }

    /** An absent peer may be read at its own proved same-cause prefix, not at an ambient newer head. */
    static Map<DocumentId, RootedDocumentView> select(ContractsClosureAdapter.CohortInvocation current,
            ManagedOccurrenceResolver.Resolution resolution, InMemoryDocumentStore documents,
            Function<DocumentId, RootedLocalHistory.Selection> next,
            BiConsumer<RootedJoinEligibility.Fence, RootedLocalHistory.Step> verifyRegisteredJoin) {
        if (current.rootedEvidence() == null || !(current.input().cause() instanceof ExternalEventCause cause)) return Map.of();
        var selected = new LinkedHashMap<DocumentId, RootedDocumentView>();
        for (var fence : RootedJoinEligibility.captureForRoot(documents, current.rootedAnchor())) {
            var terminal = fence.terminal();
            if (terminal == null || !cause.causeIdentity().equals(fence.causeIdentity())
                    || !cause.sourceOrder().equals(fence.boundary()) || !fence.receivers().containsAll(owners(current))
                    || !resolution.existingTargets().contains(terminal.work().sourceDocumentId())) continue;
            var source = terminal.work().sourceDocumentId();
            if (current.input().snapshot().contains(ContractsClosureAdapter.closureId(source))) continue;
            var sourceView = documents.require(source).rootedViewBefore(cause.sourceOrder());
            var sourceProof = sourceView.retainedSnapshot();
            var frozenSource = sourceProof.managedDocument(ContractsClosureAdapter.closureId(source));
            sourceView.requirePublishedHead(source, frozenSource.epoch(), frozenSource.blueId());
            documents.require(source).rootedPublicationPrefix(sourceView);
            for (var peer : precedingAbsentPeers(current, fence, documents)) {
                var local = next.apply(peer).step();
                if (!RootedJoinScheduling.matches(terminal.work(), local, fence)) continue;
                local.requireCurrentInput(documents);
                // This is the unchanged ordinary capturer, including source proof, plan,
                // occurrence, original-cause, current consumer and exact frozen-source checks.
                verifyRegisteredJoin.accept(fence, local);
                var session = documents.require(peer);
                var view = session.rootedView();
                var original = RootedTerminalEvidence.originalLocalCause(view, documents);
                if (original == null || !original.causeIdentity().equals(cause.causeIdentity())
                        || !original.sourceOrder().equals(cause.sourceOrder())
                        || !cause.sourceOrder().equals(view.logicalBoundary()))
                    throw ContractsClosureAdapter.stale("Peer prefix changed the original receiving cause");
                view.requirePublishedHead(peer, session.epoch(), session.currentRepresentation().blueId());
                session.rootedPublicationPrefix(view);
                var peerOwners = view.result().rootedProjection().ownedDocumentIds();
                if (!view.result().commits() || !new LinkedHashSet<>(peerOwners).equals(
                        new LinkedHashSet<>(local.invocation().rootedEvidence().context().entryOwners())))
                    throw ContractsClosureAdapter.stale("Peer prefix is not its own independently published operation");
                requireSameSource(frozenSource, local.invocation().input().snapshot()
                        .managedDocument(frozenSource.documentId()));
                for (var member : peerOwners) {
                    var id = ContractsClosureAdapter.coordinationId(member);
                    if (current.input().snapshot().contains(member)) continue;
                    if (sourceProof.managedDocument(member) == null)
                        throw ContractsClosureAdapter.stale("Peer acquisition is outside the complete frozen source proof");
                    var previous = selected.putIfAbsent(id, view);
                    if (previous != null && (!previous.retainedSnapshot().closureIdentity().equals(view.retainedSnapshot().closureIdentity())
                            || !previous.result().invocationIdentity().equals(view.result().invocationIdentity())
                            || !previous.result().commitCompanion().companionIdentity().equals(view.result().commitCompanion().companionIdentity())))
                        throw ContractsClosureAdapter.stale("Peer acquisition has competing original prefixes");
                }
            }
        }
        return Map.copyOf(selected);
    }

    private static List<DocumentId> precedingAbsentPeers(ContractsClosureAdapter.CohortInvocation input,
            RootedJoinEligibility.Fence fence, InMemoryDocumentStore documents) {
        var owner = owners(input).stream().min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow();
        var consumer = ContractsClosureAdapter.coordinationId(fence.occurrence().sourceDocumentId());
        var consumerOwners = componentOwners(consumer, documents);
        var roots = new LinkedHashSet<DocumentId>();
        for (var receiver : fence.receivers()) {
            if (consumerOwners.contains(receiver)) continue;
            var group = componentOwners(receiver, documents);
            if (!fence.receivers().containsAll(group)) continue;
            var peer = group.stream().min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow();
            // Existing forward dependencies keep their selected views; this is only
            // the independent peer that the new source proof would first disclose.
            if (EmbeddingBinding.DOCUMENT_ORDER.compare(peer, owner) < 0
                    && group.stream().noneMatch(id -> input.input().snapshot().contains(ContractsClosureAdapter.closureId(id)))
                    && !reaches(documents.require(peer).rootedView().snapshot(), group, owners(input)))
                roots.add(peer);
        }
        return roots.stream().sorted(EmbeddingBinding.DOCUMENT_ORDER).toList();
    }

    private static boolean reaches(blue.language.processor.closure.AffectedClosureSnapshot snapshot,
            Set<DocumentId> roots, Set<DocumentId> targets) {
        var visited = new LinkedHashSet<DocumentId>();
        var pending = new java.util.ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            var next = pending.removeFirst();
            if (targets.contains(next)) return true;
            if (!visited.add(next)) continue;
            snapshot.occurrences().stream().filter(row -> row.active() && row.sourceDocumentId().value().equals(next.value()))
                    .forEach(row -> pending.addLast(ContractsClosureAdapter.coordinationId(row.targetDocumentId())));
        }
        return false;
    }

    private static Set<DocumentId> componentOwners(DocumentId member, InMemoryDocumentStore documents) {
        return documents.require(member).rootedView().snapshot().components().stream()
                .filter(component -> component.orderedMemberDocumentIds().contains(ContractsClosureAdapter.closureId(member)))
                .findFirst().orElseThrow().orderedMemberDocumentIds().stream().map(ContractsClosureAdapter::coordinationId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<DocumentId> owners(ContractsClosureAdapter.CohortInvocation input) {
        return input.rootedEvidence().context().entryOwners().stream().map(ContractsClosureAdapter::coordinationId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void requireSameSource(blue.language.processor.closure.ManagedDocumentSnapshot expected,
            blue.language.processor.closure.ManagedDocumentSnapshot actual) {
        if (actual == null || expected.epoch() != actual.epoch() || !expected.blueId().equals(actual.blueId())
                || expected.componentGeneration() != actual.componentGeneration()
                || expected.initialized() != actual.initialized() || expected.terminated() != actual.terminated()
                || !blue.language.model.NodeWireForm.get(expected.document()).equals(blue.language.model.NodeWireForm.get(actual.document())))
            throw ContractsClosureAdapter.stale("Peer prefix changed the registered frozen source");
    }
}
